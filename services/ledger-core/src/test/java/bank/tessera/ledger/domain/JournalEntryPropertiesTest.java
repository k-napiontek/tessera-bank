package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The invariant this whole package exists to guarantee.
 *
 * <p>For any set of postings at all, the factory either returns a balanced entry or rejects the
 * input. There is no third outcome, and no path anywhere that produces an unbalanced
 * {@link JournalEntry}. An example test proves one case works; this proves no case does not.
 */
class JournalEntryPropertiesTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final EntryRef REF = EntryRef.of("TB202608170000000042");
    private static final EntryRef REVERSAL_REF = EntryRef.of("TB202608170000009999");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 17);

    @Provide
    Arbitrary<List<Posting>> arbitraryPostings() {
        Arbitrary<AccountRef> accounts =
                Arbitraries.integers().between(1, 99).map(JournalEntryPropertiesTest::account);
        Arbitrary<Direction> directions = Arbitraries.of(Direction.values());
        Arbitrary<CurrencyCode> currencies = Arbitraries.of(PLN, EUR);
        Arbitrary<Long> minor = Arbitraries.longs().between(1, 100_000_000L);

        Arbitrary<Posting> posting = Combinators.combine(accounts, directions, minor, currencies)
                .as((account, direction, amount, currency) ->
                        Posting.of(account, direction, Money.of(amount, currency)));

        return posting.list().ofMinSize(0).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Long>> arbitraryAmounts() {
        return Arbitraries.longs().between(1, 10_000_000L).list().ofMinSize(1).ofMaxSize(6);
    }

    @Property
    @Label("for any postings, the factory returns a balanced entry or rejects them - never anything else")
    void everyEntryEitherBalancesOrIsRejected(@ForAll("arbitraryPostings") List<Posting> generated) {
        JournalEntry entry;
        try {
            entry = JournalEntry.of(REF, VALUE_DATE, generated);
        } catch (IllegalArgumentException | UnbalancedEntryException | CurrencyMismatchException rejected) {
            return; // rejection is a correct outcome, and the only alternative to balancing
        }
        assertThat(entry.totalDebits())
                .as("an entry that was accepted must balance")
                .isEqualTo(entry.totalCredits());
    }

    @Property
    @Label("a deliberately balanced set of postings is always accepted")
    void aBalancedSetIsAlwaysAccepted(@ForAll("arbitraryAmounts") List<Long> amounts) {
        JournalEntry entry = JournalEntry.of(REF, VALUE_DATE, balancedPostings(amounts));
        assertThat(entry.totalDebits()).isEqualTo(entry.totalCredits());
        assertThat(entry.totalDebits()).isEqualTo(Money.of(sum(amounts), PLN));
    }

    @Property
    @Label("reversing a balanced entry yields a balanced entry and leaves the original untouched")
    void reversalBalancesAndDoesNotMutate(@ForAll("arbitraryAmounts") List<Long> amounts) {
        JournalEntry original = JournalEntry.of(REF, VALUE_DATE, balancedPostings(amounts));
        List<Posting> before = List.copyOf(original.postings());

        JournalEntry reversal = original.reverse(REVERSAL_REF, VALUE_DATE.plusDays(1));

        assertThat(reversal.totalDebits()).isEqualTo(reversal.totalCredits());
        assertThat(reversal.totalDebits()).isEqualTo(original.totalCredits());
        assertThat(reversal.reverses()).contains(original.reference());
        assertThat(original.postings()).isEqualTo(before);
        assertThat(original.isReversal()).isFalse();
    }

    @Property
    @Label("no accepted entry ever has fewer than two postings")
    void acceptedEntriesAlwaysHaveAtLeastTwoPostings(@ForAll("arbitraryPostings") List<Posting> generated) {
        try {
            assertThat(JournalEntry.of(REF, VALUE_DATE, generated).postings()).hasSizeGreaterThanOrEqualTo(2);
        } catch (IllegalArgumentException | UnbalancedEntryException | CurrencyMismatchException rejected) {
            // rejected, which is fine
        }
    }

    /** n debits of the given amounts, balanced by one credit for their total. */
    private static List<Posting> balancedPostings(List<Long> amounts) {
        List<Posting> postings = new ArrayList<>(amounts.size() + 1);
        for (int i = 0; i < amounts.size(); i++) {
            postings.add(Posting.of(account(i), Direction.DEBIT, Money.of(amounts.get(i), PLN)));
        }
        postings.add(Posting.of(account(99), Direction.CREDIT, Money.of(sum(amounts), PLN)));
        return postings;
    }

    private static long sum(List<Long> amounts) {
        long total = 0;
        for (long amount : amounts) {
            total += amount;
        }
        return total;
    }

    private static AccountRef account(int n) {
        return AccountRef.of(String.format("TB%014d", n));
    }
}
