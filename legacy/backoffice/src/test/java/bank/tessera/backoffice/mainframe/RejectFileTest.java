package bank.tessera.backoffice.mainframe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The rejects reader, held to the copybook rather than to a hand count.
 *
 * <p>The offsets come from {@code contracts/check-copybook-offsets.py --json REJREC}, run as a
 * subprocess here exactly as {@code MovementRecordTest} does at stratum 2. If a field is moved or
 * resized in the copybook, this fails naming it.
 */
public class RejectFileTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String TRANSFER = "TB202608180000000001";
    private static final String ACCOUNT = "TB00000000000002";

    @Test
    public void everyOffsetComesFromTheContract() throws Exception {
        String json = contractsChecker("REJREC");
        assertEquals(200, intField(json, "\"length\":"));

        String[] fields = {"REJ-MOVEMENT", "REJ-REASON-CODE", "REJ-REASON-TEXT", "REJ-DETECTED-TS"};
        for (int i = 0; i < fields.length; i++) {
            int[] declared = RejectFile.offsetsOf(fields[i]);
            assertTrue(fields[i] + " is not declared by the reader", declared != null);
            int start = fieldValue(json, fields[i], "start");
            int size = fieldValue(json, fields[i], "size");
            assertEquals(fields[i] + " starts at " + start, start - 1, declared[0]);
            assertEquals(fields[i] + " is " + size + " bytes", size, declared[1]);
        }
    }

    @Test
    public void aRejectCarriesTheMovementThatFailed() throws Exception {
        File file = write(reject(TRANSFER, 1, ACCOUNT, "D", 25000L, "E003", "ACCOUNT NOT ON MASTER"));

        List<Reject> rejects = RejectFile.read(file);

        assertEquals(1, rejects.size());
        Reject reject = rejects.get(0);
        assertEquals(TRANSFER, reject.getTransferRef());
        assertEquals(1, reject.getLegNo());
        assertEquals(ACCOUNT, reject.getAccountRef());
        assertEquals("D", reject.getDirection());
        assertEquals("PLN", reject.getCurrency());
        assertEquals(25000L, reject.getAmountMinor());
        assertEquals(new BigDecimal("250.00"), reject.getAmount());
        assertEquals("E003", reject.getReasonCode());
        assertEquals("ACCOUNT NOT ON MASTER", reject.getReasonText());
    }

    /** A code an operator has to look up is a code they will guess at, so both are carried. */
    @Test
    public void bothTheReasonCodeAndTheTextAreAvailable() throws Exception {
        File file = write(reject(TRANSFER, 2, ACCOUNT, "C", 1L, "E007", "CURRENCY MISMATCH"));
        Reject reject = RejectFile.read(file).get(0);
        assertEquals("E007", reject.getReasonCode());
        assertEquals("CURRENCY MISMATCH", reject.getReasonText());
    }

    @Test
    public void theKeyIsALegRatherThanATransfer() throws Exception {
        File file = write(reject(TRANSFER, 2, ACCOUNT, "C", 1L, "E007", "CURRENCY MISMATCH"));
        assertEquals(TRANSFER + "/2", RejectFile.read(file).get(0).getKey());
    }

    @Test
    public void anEmptyFileIsACleanNightRatherThanAnError() throws Exception {
        assertEquals(0, RejectFile.read(write(new byte[0])).size());
    }

    /** A list missing its last entry is worse than none: that is the one nobody works. */
    @Test
    public void aFileThatIsNotWholeRecordsIsRefused() throws Exception {
        byte[] whole = reject(TRANSFER, 1, ACCOUNT, "D", 1L, "E003", "SHORT");
        byte[] truncated = new byte[whole.length - 5];
        System.arraycopy(whole, 0, truncated, 0, truncated.length);
        try {
            RejectFile.read(write(truncated));
            fail("a truncated rejects file was read");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not a whole number"));
        }
    }

    @Test
    public void aCorruptAmountNamesTheRecordRatherThanAByteOffset() throws Exception {
        byte[] record = reject(TRANSFER, 1, ACCOUNT, "D", 1L, "E003", "BAD");
        record[42 + 7] = 0x0A;
        try {
            RejectFile.read(write(record));
            fail("a corrupt COMP-3 amount was read");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("record 1"));
        }
    }

    // ---------------------------------------------------------------------------------------

    private File write(byte[] content) throws IOException {
        File file = folder.newFile("REJECTS-" + System.nanoTime() + ".DAT");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content);
        } finally {
            out.close();
        }
        return file;
    }

    /** One REJREC, built to the copybook. The COMP-3 amount is encoded here by hand. */
    private static byte[] reject(String transferRef, int legNo, String accountRef, String direction,
            long amountMinor, String reasonCode, String reasonText) {
        byte[] record = new byte[200];
        java.util.Arrays.fill(record, (byte) ' ');
        put(record, 0, transferRef, 20);
        put(record, 20, String.format("%02d", Integer.valueOf(legNo)), 2);
        put(record, 22, accountRef, 16);
        put(record, 38, direction, 1);
        put(record, 39, "PLN", 3);
        System.arraycopy(encode(amountMinor), 0, record, 42, 8);
        put(record, 50, "20260818", 8);
        put(record, 58, "20260818030000", 14);
        put(record, 120, reasonCode, 4);
        put(record, 124, reasonText, 40);
        put(record, 164, "20260818031500", 14);
        return record;
    }

    private static void put(byte[] record, int at, String value, int size) {
        byte[] bytes;
        try {
            bytes = value.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
        System.arraycopy(bytes, 0, record, at, Math.min(bytes.length, size));
    }

    /** The encoder this estate's other tiers have; written here so the decoder is not its own judge. */
    private static byte[] encode(long amountMinor) {
        String digits = String.format("%015d", Long.valueOf(Math.abs(amountMinor)));
        byte[] packed = new byte[8];
        for (int i = 0; i < 7; i++) {
            packed[i] = (byte) (((digits.charAt(i * 2) - '0') << 4)
                    | (digits.charAt(i * 2 + 1) - '0'));
        }
        int sign = amountMinor < 0 ? 0x0D : 0x0C;
        packed[7] = (byte) (((digits.charAt(14) - '0') << 4) | sign);
        return packed;
    }

    private static String contractsChecker(String record) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("python3",
                "contracts/check-copybook-offsets.py", "--json", record);
        builder.directory(new File(System.getProperty("user.dir")).getParentFile().getParentFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        } finally {
            reader.close();
        }
        assertEquals("the contracts checker failed: " + output, 0, process.waitFor());
        return output.toString();
    }

    private static int intField(String json, String key) {
        int at = json.indexOf(key);
        assertTrue("no " + key + " in the checker's output", at >= 0);
        int start = at + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == ' ')) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end).trim());
    }

    private static int fieldValue(String json, String fieldName, String property) {
        int at = json.indexOf("\"name\": \"" + fieldName + "\"");
        assertTrue(fieldName + " is not in the checker's output", at >= 0);
        return intField(json.substring(at), "\"" + property + "\":");
    }
}
