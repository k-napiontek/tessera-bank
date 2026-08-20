package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * One leg of a posting, as 120 bytes of MOVEREC.
 *
 * <p>The offsets are <strong>not counted by hand here</strong>. They come from
 * {@code contracts/check-copybook-offsets.py --json MOVEREC}, which parses the copybook and computes
 * the layout from the picture clauses - the same arithmetic {@code validate.sh} runs on every
 * change. If a field is resized in the copybook, this test fails naming the field rather than
 * producing a plausible record with everything after it shifted by a few bytes.
 */
class MovementRecordTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** name -> {start, end, size}, 1-based inclusive, straight from the contracts checker. */
    private static Map<String, int[]> layout;

    private static int declaredLength;

    @BeforeAll
    static void readTheLayoutFromTheCopybook() throws Exception {
        Process checker = new ProcessBuilder(
                "python3", "contracts/check-copybook-offsets.py", "--json", "MOVEREC")
                .directory(new File("../.."))
                .start();

        byte[] out = drain(checker.getInputStream());
        byte[] err = drain(checker.getErrorStream());
        assertEquals(0, checker.waitFor(),
                "the copybook checker refused to describe MOVEREC: " + new String(err, ASCII));

        JsonNode document = JSON.readTree(out);
        declaredLength = document.get("length").asInt();

        layout = new LinkedHashMap<String, int[]>();
        for (JsonNode field : document.get("fields")) {
            layout.put(field.get("name").asText(), new int[] {
                    field.get("start").asInt(), field.get("end").asInt(), field.get("size").asInt()});
        }
    }

    @Test
    void theRecordIsTheLengthTheCopybookDeclares() {
        assertEquals(120, declaredLength, "the copybook no longer says 120 bytes");
        assertEquals(declaredLength, MovementRecord.LENGTH);
        assertEquals(declaredLength, aRecord().length);
    }

    @Test
    void everyFieldLandsWhereTheCopybookPutsIt() {
        byte[] record = aRecord();

        assertField(record, "MOV-TRANSFER-REF", "TB000000000000000101");
        assertField(record, "MOV-LEG-NO", "01");
        assertField(record, "MOV-ACCT-REF", "TB00000000000001");
        assertField(record, "MOV-DIRECTION", "D");
        assertField(record, "MOV-CURRENCY", "PLN");
        assertField(record, "MOV-VALUE-DATE", "20260820");
        assertField(record, "MOV-POSTED-TS", "20260820093000");
        assertField(record, "MOV-REFERENCE", "SALARY                             ");
    }

    @Test
    void theAmountIsPackedWhereTheCopybookSaysAndNowhereElse() {
        byte[] record = aRecord();
        int[] where = layout.get("MOV-AMOUNT");

        byte[] packed = new byte[where[2]];
        System.arraycopy(record, where[0] - 1, packed, 0, where[2]);

        assertArrayEquals(Comp3.encode(2_500L), packed);
    }

    @Test
    void thePaddingIsWhatEachPictureClauseRequires() {
        byte[] record = aRecord();

        // PIC X(n): left-justified, right-padded with spaces. Never null-padded - check-records.py
        // fails outright on a 0x00 in a display field.
        assertEquals("SALARY                             ", fieldOf(record, "MOV-REFERENCE"));
        assertEquals("             ", fieldOf(record, "FILLER"));
        assertTrue(fieldOf(record, "FILLER").trim().isEmpty(), "FILLER is not blank");

        // PIC 9(n): right-justified, left-padded with '0'.
        assertEquals("01", fieldOf(record, "MOV-LEG-NO"));
        assertEquals("20260820", fieldOf(record, "MOV-VALUE-DATE"));

        // Every field except the packed one. MOV-AMOUNT is full of legitimate 0x00 bytes - the
        // leading zero digits of a small amount pack two to a byte - which is exactly why the file
        // can never be line sequential.
        for (Map.Entry<String, int[]> field : layout.entrySet()) {
            if ("MOV-AMOUNT".equals(field.getKey())) {
                continue;
            }
            int[] where = field.getValue();
            for (int i = where[0] - 1; i < where[1]; i++) {
                assertTrue(record[i] != 0x00,
                        field.getKey() + " was null-padded rather than space- or zero-padded");
            }
        }
    }

    /**
     * The copybook is explicit: {@code MOV-AMOUNT IS ALWAYS POSITIVE. MOV-DIRECTION CARRIES THE
     * SIGN}. An encoder that signs the amount instead produces a file {@code ACCTPOST} rejects
     * wholesale as {@code R006 AMOUNT NOT POSITIVE} - and the run looks like a data problem.
     */
    @Test
    void theDirectionCarriesTheSignAndTheAmountStaysPositive() {
        byte[] debit = MovementRecord.of("TB000000000000000101", 1, "TB00000000000001",
                "DEBIT", "PLN", 2_500L, "20260820", "20260820093000", "SALARY");
        byte[] credit = MovementRecord.of("TB000000000000000101", 2, "TB00000000000002",
                "CREDIT", "PLN", 2_500L, "20260820", "20260820093000", "SALARY");

        assertEquals("D", fieldOf(debit, "MOV-DIRECTION"));
        assertEquals("C", fieldOf(credit, "MOV-DIRECTION"));

        byte[] positive = Comp3.encode(2_500L);
        assertArrayEquals(positive, amountOf(debit), "the debit leg's amount is not positive");
        assertArrayEquals(positive, amountOf(credit));
    }

    @Test
    void aNegativeAmountIsRefusedRatherThanEncoded() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> MovementRecord.of("TB000000000000000101", 1, "TB00000000000001",
                        "DEBIT", "PLN", -1L, "20260820", "20260820093000", "SALARY"));

        assertEquals(FailureStage.ENCODE, refused.stage());
        assertTrue(refused.isPermanent());
    }

    @Test
    void aCurrencyTheMainframeCannotRepresentIsRefusedAtTheEncoderToo() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> MovementRecord.of("TB000000000000000101", 1, "TB00000000000001",
                        "DEBIT", "JPY", 1_000L, "20260820", "20260820093000", "SALARY"));

        assertEquals(FailureStage.ENCODE, refused.stage());
        assertTrue(refused.getMessage().contains("JPY"));
    }

    @Test
    void aValueTooLongForItsFieldIsRefusedRatherThanTruncated() {
        // The canonical schema caps reference at 35, and the transformer validates against it before
        // anything reaches here - so arriving with 36 means the schema and this encoder disagree.
        // Truncating would silently drop what a paying customer wrote.
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> MovementRecord.of("TB000000000000000101", 1, "TB00000000000001",
                        "DEBIT", "PLN", 2_500L, "20260820", "20260820093000",
                        "123456789012345678901234567890123456"));

        assertEquals(FailureStage.ENCODE, refused.stage());
        assertTrue(refused.getMessage().contains("MOV-REFERENCE"),
                "the message should name the field that would not fit: " + refused.getMessage());
    }

    /**
     * {@code PIC X(n)} is one byte per character. A reference carrying anything outside ASCII would
     * be silently replaced by {@code ?} on a lossy encode, or would overrun the field on a
     * multi-byte one - so it is refused where it can still be explained.
     */
    @Test
    void aCharacterThatIsNotOneByteIsRefused() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> MovementRecord.of("TB000000000000000101", 1, "TB00000000000001",
                        "DEBIT", "PLN", 2_500L, "20260820", "20260820093000", "OPLATA ZA WYNAJEM ł"));

        assertEquals(FailureStage.ENCODE, refused.stage());
    }

    @Test
    void anAbsentReferenceLeavesTheFieldBlankRatherThanAbsent() {
        byte[] record = MovementRecord.of("TB000000000000000101", 1, "TB00000000000001",
                "DEBIT", "PLN", 2_500L, "20260820", "20260820093000", null);

        assertEquals(120, record.length);
        assertTrue(fieldOf(record, "MOV-REFERENCE").trim().isEmpty());
    }

    /**
     * The strongest check available, and the one the work package asks for: a record this class
     * builds, against a record the WP-03 generator actually wrote through {@code comp3.py}. All 120
     * bytes, or one of the two is wrong about the mainframe's format.
     *
     * <p>The expected record is found by its transfer reference and leg rather than by position, so
     * this fails with something legible if the generator's ordering ever changes.
     */
    @Test
    void aRecordMatchesOneTheGeneratorWroteByteForByte() throws Exception {
        byte[] fromTheGenerator = recordOf("TB202608170000000020", "02");

        byte[] built = MovementRecord.of("TB202608170000000020", 2, "TB00000000000002",
                "CREDIT", "PLN", 1_163_074L, "20260817", "20260817091500",
                "TRANSFER 20260817 SEQ 000020");

        assertArrayEquals(fromTheGenerator, built,
                "this encoder and mainframe/data/comp3.py disagree about MOVEREC");
    }

    /** One record out of the generated movement file, by transfer reference and leg. */
    private static byte[] recordOf(String transferRef, String legNo) throws Exception {
        File file = new File("../../mainframe/data/out/MOVEMENT.DAT");
        if (!file.isFile()) {
            Comp3Test.generateTheFixtures();
        }
        byte[] all = java.nio.file.Files.readAllBytes(file.toPath());
        assertEquals(0, all.length % MovementRecord.LENGTH,
                file + " is not a whole number of 120-byte records");

        for (int at = 0; at < all.length; at += MovementRecord.LENGTH) {
            String ref = new String(all, at, 20, ASCII);
            String leg = new String(all, at + 20, 2, ASCII);
            if (transferRef.equals(ref) && legNo.equals(leg)) {
                byte[] record = new byte[MovementRecord.LENGTH];
                System.arraycopy(all, at, record, 0, MovementRecord.LENGTH);
                return record;
            }
        }
        throw new AssertionError("no leg " + legNo + " of " + transferRef + " in " + file);
    }

    // -- helpers -------------------------------------------------------------------------------

    private static byte[] aRecord() {
        return MovementRecord.of("TB000000000000000101", 1, "TB00000000000001", "DEBIT", "PLN",
                2_500L, "20260820", "20260820093000", "SALARY");
    }

    private static byte[] amountOf(byte[] record) {
        int[] where = layout.get("MOV-AMOUNT");
        byte[] packed = new byte[where[2]];
        System.arraycopy(record, where[0] - 1, packed, 0, where[2]);
        return packed;
    }

    private static String fieldOf(byte[] record, String field) {
        int[] where = layout.get(field);
        return new String(record, where[0] - 1, where[2], ASCII);
    }

    private static void assertField(byte[] record, String field, String expected) {
        assertEquals(expected, fieldOf(record, field), field + " is not where the copybook puts it");
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
}
