package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The packed-decimal encoder, held to bytes it did not produce.
 *
 * <p>Two independent arbiters, and neither is this encoder's own arithmetic:
 *
 * <ul>
 * <li>The <strong>worked examples</strong> in {@code docs/architecture/canonical-data-model.md}
 * section 2, transcribed as literals exactly as {@code mainframe/data/test_comp3.py} transcribes
 * them - whose docstring names them "the agreement WP-11's Java encoder will be held to, byte for
 * byte". A test that recomputes its expectation from the implementation proves only that the
 * implementation agrees with itself.</li>
 * <li>The <strong>real fixture file</strong> {@code mainframe/data/out/ACCTMAST.DAT}, produced by
 * {@code comp3.py} through the WP-03 generator. Those bytes are what a COBOL runtime actually reads,
 * and the generator seeds zero, the maximum representable balance and two negatives on purpose so
 * that an encoder which gets any of them wrong cannot pass.</li>
 * </ul>
 *
 * <p>The generator is deterministic on its seed, so the fixture is regenerated rather than skipped
 * when it is absent. A control that quietly does not run is worse than one that fails.
 */
class Comp3Test {

    /** Where the WP-03 generator puts the account master, relative to this module. */
    private static final File MASTER = new File("../../mainframe/data/out/ACCTMAST.DAT");

    private static final int ACCTREC_LENGTH = 100;

    /** 1-based 48-55 in the copybook, so 47 zero-based. */
    private static final int BOOKED_BALANCE_AT = 47;

    private static byte[] master;

    @BeforeAll
    static void readTheMainframesOwnBytes() throws Exception {
        if (!MASTER.isFile()) {
            generateTheFixtures();
        }
        master = Files.readAllBytes(MASTER.toPath());
        assertEquals(0, master.length % ACCTREC_LENGTH,
                MASTER + " is not a whole number of 100-byte ACCTREC records");
    }

    // -- the canonical model's worked examples -------------------------------------------------

    @Test
    void aPositiveAmountEndsWithSignNibbleC() {
        // 1 234 567.89 PLN, so amountMinor = 123456789.
        assertArrayEquals(hex("000000123456789C"), Comp3.encode(123_456_789L));
    }

    @Test
    void aNegativeAmountDiffersOnlyInTheSignNibble() {
        // -1 234 567.89 PLN. Every digit byte is identical to the positive case above.
        assertArrayEquals(hex("000000123456789D"), Comp3.encode(-123_456_789L));
    }

    @Test
    void zeroIsAlwaysPositive() {
        // A negative zero would break byte-for-byte comparison here and produce a phantom
        // reconciliation break in WP-16 that no posting caused.
        assertArrayEquals(hex("000000000000000C"), Comp3.encode(0L));
        assertArrayEquals(hex("000000000000000C"), Comp3.encode(-0L));
    }

    @Test
    void theMaximumRepresentableAmountIsAllNines() {
        assertArrayEquals(hex("999999999999999C"), Comp3.encode(999_999_999_999_999L));
    }

    @Test
    void everyAmountOccupiesEightBytes() {
        long[] amounts = {0L, 1L, -1L, 5000L, 999_999_999_999_999L, -999_999_999_999_999L};
        for (long amount : amounts) {
            assertEquals(8, Comp3.encode(amount).length, "wrong width for " + amount);
        }
    }

    /**
     * The last byte is not sign-only: it carries the fifteenth digit in its high nibble. An encoder
     * that treats it as a sign byte writes {@code 0x0C} where {@code 0x1C} belongs and loses a unit
     * of currency in the one case nobody eyeballs.
     */
    @Test
    void theFifteenthDigitSharesTheLastByteWithTheSign() {
        assertArrayEquals(hex("000000000000001C"), Comp3.encode(1L));
        assertArrayEquals(hex("000000000000001D"), Comp3.encode(-1L));
    }

    // -- refusal rather than truncation --------------------------------------------------------

    @Test
    void anAmountTooLargeForThePictureClauseIsRefused() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> Comp3.encode(1_000_000_000_000_000L));

        assertEquals(FailureStage.ENCODE, refused.stage());
        assertTrue(refused.isPermanent(), "a value out of range will still be out of range later");
        assertTrue(refused.getMessage().contains("S9(13)V99"),
                "the message should name the picture clause that cannot hold it: "
                        + refused.getMessage());
    }

    @Test
    void anAmountTooSmallForThePictureClauseIsRefused() {
        assertThrows(TransferHandlingException.class,
                () -> Comp3.encode(-1_000_000_000_000_000L));
    }

    // -- the mainframe's own fixture bytes -----------------------------------------------------

    /**
     * Record 2 of the generated master holds exactly zero, seeded so that the sign nibble of a zero
     * balance is checked against a real file rather than against an opinion.
     */
    @Test
    void zeroMatchesTheAccountMasterByteForByte() {
        assertArrayEquals(bookedBalanceOf(1), Comp3.encode(0L));
    }

    /** Record 3 holds 9 999 999 999 999.99 - the largest amount the copybook can carry. */
    @Test
    void theMaximumMatchesTheAccountMasterByteForByte() {
        assertArrayEquals(bookedBalanceOf(2), Comp3.encode(999_999_999_999_999L));
    }

    /** Record 4 is overdrawn by 1 250.00; record 5 by one minor unit. */
    @Test
    void negativeBalancesMatchTheAccountMasterByteForByte() {
        assertArrayEquals(bookedBalanceOf(3), Comp3.encode(-125_000L));
        assertArrayEquals(bookedBalanceOf(4), Comp3.encode(-1L));
    }

    /** Record 6 is one minor unit positive - the {@code 0x1C} case, from the real file. */
    @Test
    void oneMinorUnitMatchesTheAccountMasterByteForByte() {
        assertArrayEquals(bookedBalanceOf(5), Comp3.encode(1L));
    }

    // -- helpers -------------------------------------------------------------------------------

    private static byte[] bookedBalanceOf(int recordIndex) {
        int from = recordIndex * ACCTREC_LENGTH + BOOKED_BALANCE_AT;
        byte[] balance = new byte[Comp3.SIZE];
        System.arraycopy(master, from, balance, 0, Comp3.SIZE);
        return balance;
    }

    private static byte[] hex(String digits) {
        byte[] raw = new byte[digits.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return raw;
    }

    /** Shared with {@link MovementRecordTest}: both are held to the generator's own bytes. */
    static void generateTheFixtures() throws IOException, InterruptedException {
        Process generator = new ProcessBuilder(
                "python3", "mainframe/data/generate.py", "--seed", "42")
                .directory(new File("../.."))
                .redirectErrorStream(true)
                .start();
        int status = generator.waitFor();
        assertEquals(0, status, "python3 mainframe/data/generate.py exited " + status
                + "; this tier's tests are held to the mainframe's own bytes and cannot run without"
                + " them");
    }
}
