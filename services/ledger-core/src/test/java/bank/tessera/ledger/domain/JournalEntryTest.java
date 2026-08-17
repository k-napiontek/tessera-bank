package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final AccountRef CASH = AccountRef.of("TB00000000000001");
    private static final AccountRef CUSTOMER = AccountRef.of("TB00000000000002");
    private static final EntryRef REF = EntryRef.of("TB202608170000000042");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 17);

    private static Posting debit(AccountRef account, long minor) {
        return Posting.of(account, Direction.DEBIT, Money.of(minor, PLN));
    }

    private static Posting credit(AccountRef account, long minor) {
        return Posting.of(account, Direction.CREDIT, Money.of(minor, PLN));
    }

    private static JournalEntry entryOf(List<Posting> postings) {
        return JournalEntry.of(REF, VALUE_DATE, postings);
    }

    @Test
    @DisplayName("a balanced pair is accepted")
    void acceptsABalancedPair() {
        JournalEntry entry = entryOf(List.of(debit(CASH, 250_00), credit(CUSTOMER, 250_00)));
        assertThat(entry.reference()).isEqualTo(REF);
        assertThat(entry.postings()).hasSize(2);
        assertThat(entry.totalDebits()).isEqualTo(Money.of(250_00, PLN));
        assertThat(entry.totalCredits()).isEqualTo(Money.of(250_00, PLN));
        assertThat(entry.currency()).isEqualTo(PLN);
    }

    @Test
    @DisplayName("a balanced entry of more than two legs is accepted - double entry is not two-entry")
    void acceptsAMultiLegEntry() {
        JournalEntry entry = entryOf(
                List.of(debit(CASH, 100_00), credit(CUSTOMER, 60_00), credit(CUSTOMER, 40_00)));
        assertThat(entry.postings()).hasSize(3);
    }

    @Test
    @DisplayName("debits and credits that do not sum equal are rejected")
    void rejectsAnUnbalancedEntry() {
        assertThatThrownBy(() -> entryOf(List.of(debit(CASH, 250_00), credit(CUSTOMER, 249_99))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("250.00")
                .hasMessageContaining("249.99");
    }

    @Test
    @DisplayName("a single leg is not a journal entry")
    void rejectsASingleLeg() {
        assertThatThrownBy(() -> entryOf(List.of(debit(CASH, 250_00))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void rejectsAnEmptyEntry() {
        assertThatThrownBy(() -> entryOf(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("mixing currencies is rejected outright - there is no conversion in this ledger")
    void rejectsAMixedCurrencyEntry() {
        List<Posting> mixed = List.of(
                Posting.of(CASH, Direction.DEBIT, Money.of(100_00, PLN)),
                Posting.of(CUSTOMER, Direction.CREDIT, Money.of(100_00, EUR)));
        assertThatThrownBy(() -> entryOf(mixed))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("a posting amount must be strictly positive - direction carries the sign")
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> Posting.of(CASH, Direction.DEBIT, Money.zero(PLN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Posting.of(CASH, Direction.DEBIT, Money.of(-1, PLN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the posting list cannot be changed after construction, from inside or out")
    void isImmutable() {
        List<Posting> mutable = new java.util.ArrayList<>(
                List.of(debit(CASH, 250_00), credit(CUSTOMER, 250_00)));
        JournalEntry entry = entryOf(mutable);

        mutable.add(debit(CASH, 999_00));
        assertThat(entry.postings()).hasSize(2);

        assertThatThrownBy(() -> entry.postings().add(debit(CASH, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the net effect on the books is always zero, which is the whole point")
    void netsToZero() {
        JournalEntry entry = entryOf(List.of(debit(CASH, 250_00), credit(CUSTOMER, 250_00)));
        assertThat(entry.totalDebits().minus(entry.totalCredits())).isEqualTo(Money.zero(PLN));
    }
}
