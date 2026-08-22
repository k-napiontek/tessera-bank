package bank.tessera.ledger.loader;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CustomerRef;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds and reversals, which are the two operations that make a loaded ledger look like one a bank
 * ran rather than one somebody filled with transfers.
 *
 * <p>Holds are in scope because {@code Balance.of} is the booked balance less the active holds, and
 * the balance read is one of the query plans this package has to capture. Reversals are in scope
 * because a correction in a double-entry ledger is a reversing entry, and nothing else in the loaded
 * data would ever set {@code journal_entry.reverses}.
 */
class HoldsAndReversalsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
    private static final String HOLDER = "TB00000000000001";
    private static final String MERCHANT = "TB00000000000002";
    private static final String TREASURY = "TB00000000000099";

    private RecordingSink sink;
    private DatasetLoader loader;

    @BeforeEach
    void openAnEstate() {
        sink = new RecordingSink();
        loader = new DatasetLoader(sink, 1_000_000);
        loader.population(new Header(
                "TB-WORKLOAD-DAY-V1", "1.0.0", "digest", 42, 1.0, DAY, DAY, 2, 1,
                "PLN", "LIABILITY", "ASSET", Map.of("retail", 100L), 20_000L, "CU0000000099", TREASURY));
        loader.open(new OpenAccount(
                CustomerRef.of("CU0000000099"), AccountRef.of(TREASURY), AccountType.ASSET, "", true));
        loader.open(new OpenAccount(
                CustomerRef.of("CU0000000001"), AccountRef.of(HOLDER), AccountType.LIABILITY, "retail", false));
        loader.open(new OpenAccount(
                CustomerRef.of("CU0000000002"), AccountRef.of(MERCHANT), AccountType.LIABILITY, "retail", false));
    }

    private DrawnAction action(String operation, long seq, long amountMinor, String holdRef) {
        return new DrawnAction(
                DAY, seq, seq * 1000, "retail", operation, "CU0000000001", HOLDER, MERCHANT,
                "TB20260302" + String.format("%010d", seq), holdRef, amountMinor, "PLN");
    }

    @Test
    void aPlacedHoldReservesAnAmountAndPostsNothing() {
        loader.action(action("placeHold", 1, 3_000, "HL202603020000000001"));
        loader.finish();

        assertThat(loader.counters()).containsEntry(Counter.HOLDS_PLACED, 1L);
        assertThat(sink.holds).singleElement().satisfies(hold -> {
            assertThat(hold.status()).isEqualTo("PLACED");
            assertThat(hold.amountMinor()).isEqualTo(3_000);
            assertThat(hold.capturedBy()).isNull();
            // V6's hold_transitioned_at_consistent: "null" and "still placed" are the same statement.
            assertThat(hold.transitionedAt()).isNull();
        });
        // Two opening legs each for the two customer accounts, and nothing from the hold: a reserved
        // amount is not a posted one, which is the entire difference between booked and available.
        assertThat(sink.postings).hasSize(4);
        assertThat(loader.require(HOLDER).bookedMinor).isEqualTo(20_000);
    }

    @Test
    void aCapturedHoldNamesTheEntryThatCapturedIt() {
        loader.action(action("placeHold", 1, 3_000, "HL202603020000000001"));
        loader.action(action("captureHold", 2, 3_000, "HL202603020000000002"));
        loader.finish();

        assertThat(loader.counters()).containsEntry(Counter.HOLDS_CAPTURED, 1L);
        assertThat(sink.holds).singleElement().satisfies(hold -> {
            assertThat(hold.status()).isEqualTo("CAPTURED");
            // V2's hold_captured_by_consistent asserts these two are the same statement.
            assertThat(hold.capturedBy()).isEqualTo("TB202603020000000002");
            assertThat(hold.transitionedAt()).isNotNull().isAfterOrEqualTo(hold.placedAt());
        });
        assertThat(loader.require(HOLDER).bookedMinor).isEqualTo(20_000 - 3_000);
        assertThat(loader.require(MERCHANT).bookedMinor).isEqualTo(20_000 + 3_000);
    }

    /**
     * Capturing more than was reserved is not a larger capture, it is a different transaction that
     * never passed an authorisation - which is what {@code CaptureHold} says when it refuses one.
     */
    @Test
    void aCaptureIsNeverLargerThanTheHoldItCaptures() {
        loader.action(action("placeHold", 1, 1_000, "HL202603020000000001"));
        loader.action(action("captureHold", 2, 9_999, "HL202603020000000002"));
        loader.finish();

        assertThat(sink.postings)
                .filteredOn(posting -> posting.entryRef().equals("TB202603020000000002"))
                .allSatisfy(posting -> assertThat(posting.amountMinor()).isEqualTo(1_000));
    }

    @Test
    void aReleasedHoldMovesNoMoney() {
        loader.action(action("placeHold", 1, 3_000, "HL202603020000000001"));
        int postingsBefore = sink.postings.size();
        loader.action(action("releaseHold", 2, 0, "HL202603020000000002"));
        loader.finish();

        assertThat(loader.counters()).containsEntry(Counter.HOLDS_RELEASED, 1L);
        assertThat(sink.postings).hasSize(postingsBefore);
        assertThat(sink.holds).singleElement().satisfies(hold -> {
            assertThat(hold.status()).isEqualTo("RELEASED");
            assertThat(hold.capturedBy()).isNull();
            assertThat(hold.transitionedAt()).isNotNull();
        });
    }

    @Test
    void aCaptureWithNoHoldToCaptureIsCountedAndNotWritten() {
        loader.action(action("captureHold", 1, 3_000, "HL202603020000000001"));
        loader.finish();

        assertThat(loader.counters()).containsEntry(Counter.HOLD_NOT_FOUND, 1L);
        assertThat(sink.holds).isEmpty();
        assertThat(sink.entries).filteredOn(entry -> entry.referenceText() == null).isEmpty();
    }

    @Test
    void aReversalMirrorsTheEntryItReversesAndNamesIt() {
        loader.action(action("createTransfer", 1, 5_000, null));
        loader.action(action("reverseTransfer", 2, 0, null));
        loader.finish();

        assertThat(loader.counters()).containsEntry(Counter.TRANSFERS_REVERSED, 1L);
        assertThat(sink.entries)
                .filteredOn(entry -> entry.reference().equals("TB202603020000000002"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.reverses()).isEqualTo("TB202603020000000001"));

        // The mirror puts both accounts back where they started.
        assertThat(loader.require(HOLDER).bookedMinor).isEqualTo(20_000);
        assertThat(loader.require(MERCHANT).bookedMinor).isEqualTo(20_000);
    }

    /**
     * {@code journal_entry_reverses_uq} makes reversing one entry twice a unique-index violation
     * rather than a second correction, and a loader that tried it would fail four million rows in.
     */
    @Test
    void anEntryIsReversedAtMostOnce() {
        loader.action(action("createTransfer", 1, 5_000, null));
        loader.action(action("reverseTransfer", 2, 0, null));
        loader.action(action("reverseTransfer", 3, 0, null));
        loader.finish();

        assertThat(loader.counters()).containsEntry(Counter.TRANSFERS_REVERSED, 1L);
        assertThat(loader.counters()).containsEntry(Counter.NOTHING_TO_REVERSE, 1L);
        assertThat(sink.entries)
                .extracting(LedgerRows.EntryRow::reverses)
                .filteredOn(reverses -> reverses != null)
                .doesNotHaveDuplicates();
    }

    @Test
    void aReversalWithNothingToReverseIsCounted() {
        loader.action(action("reverseTransfer", 1, 0, null));
        loader.finish();

        assertThat(loader.counters()).containsEntry(Counter.NOTHING_TO_REVERSE, 1L);
        assertThat(loader.counters()).doesNotContainKey(Counter.TRANSFERS_REVERSED);
    }
}
