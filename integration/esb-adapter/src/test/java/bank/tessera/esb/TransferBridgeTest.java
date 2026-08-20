package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.tessera.esb.ws.Account;
import bank.tessera.esb.ws.CustomerMasterPortType;
import bank.tessera.esb.ws.Movement;
import bank.tessera.esb.ws.ServiceFault;
import bank.tessera.esb.ws.ServiceFaultMessage;
import bank.tessera.esb.ws.Transfer;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import javax.xml.ws.Holder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole bridge, with only the far end stubbed.
 *
 * <p>The transformation, the schema validation, the encoder and the file are all the real ones. What
 * is replaced is the SOAP port, because this class is about <strong>the order the two hops happen
 * in</strong> and what happens when the first one refuses - and that ordering is a constraint the
 * work package states outright: nothing may be written to the movement file unless the call into the
 * system of record succeeded.
 *
 * <p>{@code TransferBridgeIT} makes the same journey against a really-deployed customer-master.
 */
class TransferBridgeTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final String TRANSFER_REF = "TB000000000000000101";
    private static final String DEBIT_ACCOUNT = "TB00000000000001";
    private static final String CREDIT_ACCOUNT = "TB00000000000002";

    @TempDir
    File directory;

    private File movementFile;
    private StubPort port;

    @BeforeEach
    void setUp() {
        movementFile = new File(directory, "MOVEMENT.DAT");
        port = new StubPort();
    }

    @Test
    void aTransferReachesTheSystemOfRecordAndThenTheMovementFile() throws Exception {
        bridge().handle(event(2_500L, "PLN"));

        assertEquals(1, port.calls, "the system of record was not told");
        assertEquals(240, movementFile.length(), "two legs, one per side of the posting");
        assertEquals(TRANSFER_REF, fieldAt(0, 0, 20));
        assertEquals("01", fieldAt(0, 20, 2));
        assertEquals(DEBIT_ACCOUNT, fieldAt(0, 22, 16));
        assertEquals("D", fieldAt(0, 38, 1));
        assertEquals("02", fieldAt(1, 20, 2));
        assertEquals(CREDIT_ACCOUNT, fieldAt(1, 22, 16));
        assertEquals("C", fieldAt(1, 38, 1));
    }

    /**
     * The constraint the era boundary was drawn on. A transfer the system of record refused has not
     * happened as far as 2011 is concerned, and a movement record for it would tell 1995 otherwise -
     * leaving two halves of the estate permanently disagreeing about a payment.
     */
    @Test
    void nothingIsWrittenWhenTheSystemOfRecordRefuses() {
        port.refuseWith("ACCT_NOT_FOUND");

        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> bridge().handle(event(2_500L, "PLN")));

        assertEquals(FailureStage.SOAP, refused.stage());
        assertFalse(movementFile.exists(), "a refused transfer reached the mainframe's file");
    }

    @Test
    void nothingIsWrittenWhenTheSystemOfRecordCannotBeReached() {
        port.beUnreachable();

        TransferHandlingException failed = assertThrows(TransferHandlingException.class,
                () -> bridge().handle(event(2_500L, "PLN")));

        assertEquals(FailureStage.SOAP, failed.stage());
        assertFalse(failed.isPermanent());
        assertFalse(movementFile.exists());
    }

    /**
     * A redelivery does not write a second pair of records, and the reason it does not is the file
     * rather than {@code alreadyApplied}.
     */
    @Test
    void aRedeliveredTransferDoesNotGainASecondPairOfRecords() throws Exception {
        TransferBridge bridge = bridge();
        bridge.handle(event(2_500L, "PLN"));
        byte[] afterFirst = Files.readAllBytes(movementFile.toPath());

        port.answerAlreadyApplied();
        bridge.handle(event(2_500L, "PLN"));

        assertEquals(2, port.calls, "the far end was not told the second time");
        assertTrue(java.util.Arrays.equals(afterFirst, Files.readAllBytes(movementFile.toPath())),
                "the redelivery changed the movement file");
    }

    /**
     * The crash window WP-11a's design could not close. The far end already holds the transfer and
     * says so, but this process died before the record landed - so the file, not the answer, decides.
     */
    @Test
    void aTransferTheFarEndAlreadyHeldButTheFileDoesNotStillGetsWritten() throws Exception {
        port.answerAlreadyApplied();

        bridge().handle(event(2_500L, "PLN"));

        assertEquals(240, movementFile.length(),
                "a movement lost between the SOAP call and the write was never recovered");
    }

    /**
     * Refused before the call, so the system of record is never told about a transfer that stratum 0
     * could not represent. WP-11a made this assertion at the bridge; the encoder now makes it again.
     */
    @Test
    void aCurrencyTheMainframeCannotRepresentNeverReachesEitherHop() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> bridge().handle(event(1_000L, "JPY")));

        assertEquals(FailureStage.ENCODE, refused.stage());
        assertTrue(refused.isPermanent());
        assertEquals(0, port.calls, "the system of record was told about a JPY transfer");
        assertFalse(movementFile.exists());
    }

    /**
     * {@code MOV-VALUE-DATE} is {@code PIC 9(08)} and {@code MOV-POSTED-TS} is {@code PIC 9(14)},
     * both plain digits with no separator and no zone. The event carries an offset; a 1995 domestic
     * core has no concept of one, so the instant is normalised to UTC rather than to an invented
     * local zone.
     */
    @Test
    void theTimestampsAreWrittenAsPlainDigitsInUtc() throws Exception {
        bridge().handle(event(2_500L, "PLN"));

        assertEquals("20260820", fieldAt(0, 50, 8));
        assertEquals("20260820093000", fieldAt(0, 58, 14));
    }

    @Test
    void theRemittanceReferenceIsCarriedIntoTheRecord() throws Exception {
        bridge().handle(event(2_500L, "PLN"));

        assertEquals("SALARY                             ", fieldAt(0, 72, 35));
    }

    // -- helpers -------------------------------------------------------------------------------

    private TransferBridge bridge() {
        CanonicalTransformer transformer = new CanonicalTransformer(
                new File(System.getProperty("tessera.contracts.dir", "../../contracts"),
                        "xsd/canonical-v1.xsd"));
        return new TransferBridge(transformer, new CustomerMasterClient(port),
                new MovementFileWriter(movementFile));
    }

    private String fieldAt(int record, int offset, int size) throws Exception {
        byte[] all = Files.readAllBytes(movementFile.toPath());
        return new String(all, record * 120 + offset, size, ASCII);
    }

    /** Shaped by contracts/asyncapi/ledger-events.yaml - what the ledger's outbox really publishes. */
    private static String event(long amountMinor, String currency) {
        String money = "{\"amountMinor\":" + amountMinor + ",\"currency\":\"" + currency + "\"}";
        return "{"
                + "\"transferRef\":\"" + TRANSFER_REF + "\","
                + "\"debitAccountRef\":\"" + DEBIT_ACCOUNT + "\","
                + "\"creditAccountRef\":\"" + CREDIT_ACCOUNT + "\","
                + "\"amount\":" + money + ","
                + "\"reference\":\"SALARY\","
                + "\"postedAt\":\"2026-08-20T09:30:00Z\","
                + "\"movements\":["
                + "{\"movementRef\":\"" + TRANSFER_REF + "-01\",\"transferRef\":\"" + TRANSFER_REF
                + "\",\"legNo\":1,\"accountRef\":\"" + DEBIT_ACCOUNT + "\",\"direction\":\"DEBIT\","
                + "\"amount\":" + money + ",\"valueDate\":\"2026-08-20\","
                + "\"postedAt\":\"2026-08-20T09:30:00Z\"},"
                + "{\"movementRef\":\"" + TRANSFER_REF + "-02\",\"transferRef\":\"" + TRANSFER_REF
                + "\",\"legNo\":2,\"accountRef\":\"" + CREDIT_ACCOUNT + "\",\"direction\":\"CREDIT\","
                + "\"amount\":" + money + ",\"valueDate\":\"2026-08-20\","
                + "\"postedAt\":\"2026-08-20T09:30:00Z\"}"
                + "],"
                + "\"correlationId\":\"11111111-2222-3333-4444-555555555555\""
                + "}";
    }

    /** The 2011 end, stubbed. Only its answers matter here; TransferBridgeIT uses the real one. */
    private static final class StubPort implements CustomerMasterPortType {

        private int calls;
        private String faultCode;
        private boolean unreachable;
        private boolean alreadyApplied;

        void refuseWith(String code) {
            this.faultCode = code;
        }

        void beUnreachable() {
            this.unreachable = true;
        }

        void answerAlreadyApplied() {
            this.alreadyApplied = true;
        }

        @Override
        public void notifyTransferPosted(Transfer transfer, List<Movement> movements,
                Holder<String> transferRef, Holder<Boolean> applied) throws ServiceFaultMessage {
            calls++;
            if (unreachable) {
                throw new javax.xml.ws.WebServiceException("connection refused");
            }
            if (faultCode != null) {
                ServiceFault fault = new ServiceFault();
                fault.setFaultCode(faultCode);
                throw new ServiceFaultMessage("refused", fault);
            }
            transferRef.value = transfer.getTransferRef();
            applied.value = Boolean.valueOf(alreadyApplied);
        }

        @Override
        public Account getAccount(String accountRef) throws ServiceFaultMessage {
            throw new UnsupportedOperationException("not used by the bridge");
        }

        @Override
        public List<Account> getAccountsByCustomer(String customerRef) throws ServiceFaultMessage {
            throw new UnsupportedOperationException("not used by the bridge");
        }
    }
}
