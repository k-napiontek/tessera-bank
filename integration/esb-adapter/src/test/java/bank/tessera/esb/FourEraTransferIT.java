package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One transfer, across all four eras, for real.
 *
 * <p><strong>This is what the work package exists to claim, and nothing in this repository has done
 * it before.</strong> A 2023 ledger event goes into a real Kafka; this component transforms it by
 * XSLT, validates it against the canonical schema, calls a 2011 SOAP endpoint deployed as its own
 * WAR on a real Tomcat 8.5 over a real Oracle, and appends COMP-3 packed-decimal records to a
 * fixed-width file; then the 1995 overnight cycle - GnuCOBOL, the real {@code ACCTPOST}, the real
 * sort steps - applies them to the account master. The test asserts that the balance moved by the
 * same amount in 2011 and in 1995.
 *
 * <p>It is an integration test rather than a walkthrough script on purpose. A script is not in
 * {@code make test} and nothing goes red when it breaks, which is the gap F-17 records about
 * documents drifting away from what is true.
 *
 * <p>Needs Docker, a JDK 8, <strong>GnuCOBOL and python3</strong>. It fails rather than skips when
 * the mainframe toolchain is missing: {@code make test} already needs GnuCOBOL for
 * {@code test-mainframe}, and a control that quietly does not run is the false assurance the
 * Definition of Done's honesty clause exists to prevent.
 */
@SpringBootTest(classes = EsbAdapterApplication.class)
class FourEraTransferIT {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final String TOPIC = "tessera.ledger.transfer-posted.v1";

    private static final String CUSTOMER = "CU0000000042";
    private static final String DEBIT_ACCOUNT = "TB00000000000001";
    private static final String CREDIT_ACCOUNT = "TB00000000000002";

    private static final long DEBIT_OPENING = 1_000_00L;
    private static final long CREDIT_OPENING = 500_00L;
    private static final long AMOUNT = 25_00L;

    private static final String TRANSFER_REF = "TB000000000000000201";
    private static final String BUSINESS_DATE = "20260820";

    private static final int ACCTREC_LENGTH = 100;
    private static final int BOOKED_BALANCE_AT = 47;
    private static final int AVAILABLE_BALANCE_AT = 55;

    /** Everything this test writes lives under target/, never under mainframe/data/out/. */
    private static final File WORK = new File("target/four-era");
    private static final File MOVEMENT_FILE = new File(WORK, "MOVEMENT.DAT");
    private static final File MASTER_IN = new File(WORK, "ACCTMAST.DAT");
    private static final File EOD_WORK = new File(WORK, "eod");

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private static CustomerMasterUnderTest customerMaster;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @BeforeAll
    static void bringUpAllFourEras() throws Exception {
        requireTheMainframeToolchain();
        prepareTheWorkDirectory();

        KAFKA.start();
        customerMaster = CustomerMasterUnderTest.start(new File("target/customer-master-four-era"));
        seedTheSameTwoAccountsInBothSystems();
    }

    @AfterAll
    static void takeItDown() {
        if (customerMaster != null) {
            customerMaster.stop();
        }
        KAFKA.stop();
    }

    @DynamicPropertySource
    static void pointTheAdapterAtThem(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("tessera.esb.customer-master-endpoint", () -> customerMaster.endpoint());
        registry.add("tessera.contracts.dir", () -> new File("../../contracts").getAbsolutePath());
        registry.add("tessera.esb.movement-file", () -> MOVEMENT_FILE.getAbsolutePath());
    }

    /**
     * The journey, told once and in order. It is a single test because every step depends on the
     * one before it, and splitting it would create tests that pass only in a particular order.
     */
    @Test
    void aTransferPostedIn2023ReachesThe1995AccountMasterOvernight() throws Exception {
        // -- 2023 -> 2019. The ledger publishes; this component consumes.
        kafka.send(TOPIC, TRANSFER_REF, event());

        // -- 2019 -> 2011. The system of record is told over SOAP, and its balances move.
        awaitApplied();
        assertEquals(DEBIT_OPENING - AMOUNT, oracleBalanceOf(DEBIT_ACCOUNT),
                "the debit leg did not reach the system of record");
        assertEquals(CREDIT_OPENING + AMOUNT, oracleBalanceOf(CREDIT_ACCOUNT));

        // -- 2011 -> 1995. Two fixed-width records, sharing one transfer reference.
        awaitMovementFile(2);
        byte[] movements = Files.readAllBytes(MOVEMENT_FILE.toPath());
        assertEquals(240, movements.length, "a transfer is two legs, and only two");
        assertEquals(TRANSFER_REF, text(movements, 0, 20));
        assertEquals(TRANSFER_REF, text(movements, 120, 20));
        assertEquals("01", text(movements, 20, 2));
        assertEquals("02", text(movements, 140, 2));
        assertEquals("D", text(movements, 38, 1));
        assertEquals("C", text(movements, 158, 1));
        assertArrayEquals(Comp3.encode(AMOUNT), slice(movements, 42, 8),
                "the packed amount is not what the mainframe's own encoder would have written");

        // -- 1995. The overnight cycle applies them to the account master.
        String cycle = runTheOvernightCycle();

        File newMaster = new File(new File(EOD_WORK, BUSINESS_DATE), "ACCTNEW.DAT");
        assertTrue(newMaster.isFile(), "the cycle produced no new master:\n" + cycle);
        assertEquals(0, new File(new File(EOD_WORK, BUSINESS_DATE), "REJECTS.DAT").length(),
                "ACCTPOST rejected a movement this component wrote:\n" + cycle);

        byte[] master = Files.readAllBytes(newMaster.toPath());
        assertEquals(2 * ACCTREC_LENGTH, master.length);

        // The same movement, in two eras, arrived at the same number.
        assertArrayEquals(Comp3.encode(DEBIT_OPENING - AMOUNT), booked(master, 0),
                "the COBOL master's debit balance is not what Oracle's is");
        assertArrayEquals(Comp3.encode(CREDIT_OPENING + AMOUNT), booked(master, 1));
        assertEquals(oracleBalanceOf(DEBIT_ACCOUNT), DEBIT_OPENING - AMOUNT);

        // -- and once only. Delivery is at-least-once, so this is not a hypothetical.
        kafka.send(TOPIC, TRANSFER_REF, event());
        Thread.sleep(5000);

        assertArrayEquals(movements, Files.readAllBytes(MOVEMENT_FILE.toPath()),
                "the redelivery changed the movement file");
        assertEquals(1, appliedCount(), "the redelivery was applied twice by the system of record");
    }

    // -- the 1995 cycle ------------------------------------------------------------------------

    private static String runTheOvernightCycle() throws Exception {
        Process cycle = new ProcessBuilder("bash", "mainframe/jcl/run-eod.sh",
                "--business-date", BUSINESS_DATE,
                "--master", MASTER_IN.getAbsolutePath(),
                "--movements", MOVEMENT_FILE.getAbsolutePath(),
                "--work", EOD_WORK.getAbsolutePath())
                .directory(new File("../.."))
                .redirectErrorStream(true)
                .start();

        String output = new String(drain(cycle.getInputStream()), ASCII);
        assertEquals(0, cycle.waitFor(), "the overnight cycle abended:\n" + output);
        return output;
    }

    private static void requireTheMainframeToolchain() throws Exception {
        require("cobc", "--version", "GnuCOBOL is not on the PATH. Stratum 0 is compiled and run"
                + " for real by this test - see CLAUDE.md: brew install gnucobol");
        require("python3", "--version", "python3 is not on the PATH. The cycle's sort steps and the"
                + " contracts checker are Python.");
    }

    private static void require(String command, String flag, String why) throws Exception {
        try {
            Process probe = new ProcessBuilder(command, flag).redirectErrorStream(true).start();
            drain(probe.getInputStream());
            assertEquals(0, probe.waitFor(), why);
        } catch (java.io.IOException notThere) {
            throw new IllegalStateException(why, notThere);
        }
    }

    // -- fixtures ------------------------------------------------------------------------------

    private static void prepareTheWorkDirectory() throws Exception {
        deleteRecursively(WORK);
        if (!WORK.mkdirs()) {
            throw new IllegalStateException("cannot create " + WORK);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    /**
     * The two accounts, in 2011 and in 1995, opened with the same balances.
     *
     * <p>That they are held twice is not a mistake - it is ADR 0010, and reconciling them is what
     * {@code batch/recon} will exist for. This test asserts that one transfer moves both by the same
     * amount, which is the property the reconciliation depends on being true.
     */
    private static void seedTheSameTwoAccountsInBothSystems() throws Exception {
        try (Connection connection = customerMaster.connection();
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO customer (customer_ref, family_name, given_name,"
                    + " date_of_birth, national_id, onboarded_date) VALUES ('" + CUSTOMER
                    + "', 'TESSERA-0002', 'SYNTHETIC', DATE '1980-01-01', 'SYN-0000000002',"
                    + " DATE '2011-01-01')");
            statement.execute(openAccount(DEBIT_ACCOUNT, DEBIT_OPENING));
            statement.execute(openAccount(CREDIT_ACCOUNT, CREDIT_OPENING));
        }

        // ACCTREC is 100 bytes and the file is ascending by ACCT-REF, which these two already are.
        byte[] master = new byte[2 * ACCTREC_LENGTH];
        System.arraycopy(acctrec(DEBIT_ACCOUNT, DEBIT_OPENING), 0, master, 0, ACCTREC_LENGTH);
        System.arraycopy(acctrec(CREDIT_ACCOUNT, CREDIT_OPENING), 0, master, ACCTREC_LENGTH,
                ACCTREC_LENGTH);
        Files.write(MASTER_IN.toPath(), master);
    }

    private static String openAccount(String accountRef, long balanceMinor) {
        return "INSERT INTO account (account_ref, customer_ref, account_type, currency, status,"
                + " booked_balance, opened_date) VALUES ('" + accountRef + "', '" + CUSTOMER
                + "', 'LIABILITY', 'PLN', 'OPEN', " + balanceMinor + ", DATE '2011-01-01')";
    }

    /** One ACCTREC, per contracts/copybook/ACCTREC.CPY. */
    private static byte[] acctrec(String accountRef, long balanceMinor) {
        byte[] record = new byte[ACCTREC_LENGTH];
        java.util.Arrays.fill(record, (byte) ' ');
        put(record, 0, accountRef);
        put(record, 16, "CU0000000042");
        put(record, 28, "LIABILITY");
        put(record, 37, "PLN");
        put(record, 40, "OPEN");
        System.arraycopy(Comp3.encode(balanceMinor), 0, record, BOOKED_BALANCE_AT, Comp3.SIZE);
        System.arraycopy(Comp3.encode(balanceMinor), 0, record, AVAILABLE_BALANCE_AT, Comp3.SIZE);
        put(record, 63, "20110101");
        put(record, 71, "20110101");
        return record;
    }

    private static void put(byte[] record, int at, String value) {
        byte[] raw = value.getBytes(ASCII);
        System.arraycopy(raw, 0, record, at, raw.length);
    }

    // -- waiting and reading -------------------------------------------------------------------

    private static void awaitApplied() throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (appliedCount() > 0) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("transfer " + TRANSFER_REF + " never reached the system of record");
    }

    private static void awaitMovementFile(int records) throws Exception {
        long wanted = (long) records * 120;
        for (int attempt = 0; attempt < 60; attempt++) {
            if (MOVEMENT_FILE.length() >= wanted) {
                // One more beat, so a file caught mid-append fails the length assertion rather than
                // passing by luck.
                Thread.sleep(500);
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the movement file never reached " + wanted + " bytes; it is "
                + MOVEMENT_FILE.length());
    }

    private static int appliedCount() throws Exception {
        try (Connection connection = customerMaster.connection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT COUNT(*) FROM applied_transfer WHERE transfer_ref = ?")) {
            query.setString(1, TRANSFER_REF);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static long oracleBalanceOf(String accountRef) throws Exception {
        try (Connection connection = customerMaster.connection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT booked_balance FROM account WHERE account_ref = ?")) {
            query.setString(1, accountRef);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static byte[] booked(byte[] master, int record) {
        return slice(master, record * ACCTREC_LENGTH + BOOKED_BALANCE_AT, Comp3.SIZE);
    }

    private static byte[] slice(byte[] from, int at, int size) {
        byte[] taken = new byte[size];
        System.arraycopy(from, at, taken, 0, size);
        return taken;
    }

    private static String text(byte[] from, int at, int size) {
        return new String(from, at, size, ASCII);
    }

    private static byte[] drain(InputStream stream) throws Exception {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            collected.write(buffer, 0, read);
        }
        return collected.toByteArray();
    }

    /** Shaped by contracts/asyncapi/ledger-events.yaml - what the ledger's outbox really publishes. */
    private static String event() {
        String money = "{\"amountMinor\":" + AMOUNT + ",\"currency\":\"PLN\"}";
        return "{"
                + "\"transferRef\":\"" + TRANSFER_REF + "\","
                + "\"debitAccountRef\":\"" + DEBIT_ACCOUNT + "\","
                + "\"creditAccountRef\":\"" + CREDIT_ACCOUNT + "\","
                + "\"amount\":" + money + ","
                + "\"reference\":\"SALARY AUGUST\","
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
                + "\"correlationId\":\"22222222-3333-4444-5555-666666666666\""
                + "}";
    }
}
