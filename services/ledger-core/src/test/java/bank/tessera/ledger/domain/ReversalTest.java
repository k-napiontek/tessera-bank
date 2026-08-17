package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Corrections happen by reversal and by nothing else.
 *
 * <p>The ledger has no update and no delete. A mistake is undone by a new entry that references the
 * one it undoes, so the original stays in the record and the correction is visible as a correction
 * rather than as history that quietly changed.
 */
class ReversalTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef CASH = AccountRef.of("TB00000000000001");
    private static final AccountRef CUSTOMER = AccountRef.of("TB00000000000002");
    private static final EntryRef ORIGINAL = EntryRef.of("TB202608170000000042");
    private static final EntryRef REVERSAL = EntryRef.of("TB202608180000000043");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 17);

    private static JournalEntry original() {
        return JournalEntry.of(
                ORIGINAL,
                VALUE_DATE,
                List.of(
                        Posting.of(CASH, Direction.DEBIT, Money.of(250_00, PLN)),
                        Posting.of(CUSTOMER, Direction.CREDIT, Money.of(250_00, PLN))));
    }

    @Test
    @DisplayName("every direction is flipped and every amount is unchanged")
    void flipsDirectionsAndKeepsAmounts() {
        JournalEntry reversal = original().reverse(REVERSAL, VALUE_DATE.plusDays(1));

        assertThat(reversal.postings())
                .containsExactly(
                        Posting.of(CASH, Direction.CREDIT, Money.of(250_00, PLN)),
                        Posting.of(CUSTOMER, Direction.DEBIT, Money.of(250_00, PLN)));
    }

    @Test
    @DisplayName("the reversal balances, like any other entry")
    void theReversalBalances() {
        JournalEntry reversal = original().reverse(REVERSAL, VALUE_DATE.plusDays(1));
        assertThat(reversal.totalDebits()).isEqualTo(reversal.totalCredits());
    }

    @Test
    @DisplayName("it names the entry it reverses, so the pair can always be found")
    void referencesTheOriginal() {
        JournalEntry reversal = original().reverse(REVERSAL, VALUE_DATE.plusDays(1));
        assertThat(reversal.isReversal()).isTrue();
        assertThat(reversal.reverses()).contains(ORIGINAL);
    }

    @Test
    @DisplayName("the original is untouched - it is a new entry, not an edit")
    void theOriginalIsUnchanged() {
        JournalEntry entry = original();
        List<Posting> before = List.copyOf(entry.postings());

        entry.reverse(REVERSAL, VALUE_DATE.plusDays(1));

        assertThat(entry.postings()).isEqualTo(before);
        assertThat(entry.isReversal()).isFalse();
        assertThat(entry.reverses()).isEmpty();
    }

    @Test
    @DisplayName("a reversal carries its own value date - undoing yesterday happens today")
    void theReversalHasItsOwnValueDate() {
        JournalEntry reversal = original().reverse(REVERSAL, VALUE_DATE.plusDays(1));
        assertThat(reversal.valueDate()).isEqualTo(VALUE_DATE.plusDays(1));
        assertThat(original().valueDate()).isEqualTo(VALUE_DATE);
    }

    @Test
    @DisplayName("reusing the original's reference is refused - that would be a mutation in disguise")
    void refusesToReuseTheOriginalReference() {
        assertThatThrownBy(() -> original().reverse(ORIGINAL, VALUE_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own reference");
    }

    @Test
    @DisplayName("reversing a reversal returns to the original shape")
    void reversingTwiceRestoresTheShape() {
        JournalEntry entry = original();
        JournalEntry back = entry.reverse(REVERSAL, VALUE_DATE)
                .reverse(EntryRef.of("TB202608190000000044"), VALUE_DATE);

        assertThat(back.postings()).isEqualTo(entry.postings());
    }

    @Test
    @DisplayName("no mutating method exists on the entry at all")
    void exposesNoMutator() {
        for (Method method : JournalEntry.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            assertThat(method.getName())
                    .as("a public setter on an append-only type: %s", method.getName())
                    .doesNotStartWith("set")
                    .doesNotStartWith("add")
                    .doesNotStartWith("remove")
                    .doesNotStartWith("update")
                    .doesNotStartWith("delete");
        }
    }
}
