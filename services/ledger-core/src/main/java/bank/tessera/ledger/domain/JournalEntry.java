package bank.tessera.ledger.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An atomic, balanced, immutable set of postings - the unit the ledger actually records.
 *
 * <p>Three rules are enforced at construction, so a journal entry that breaks any of them cannot
 * exist:
 *
 * <ol>
 *   <li>debits and credits sum equal;
 *   <li>there are at least two postings;
 *   <li>every posting is in the same currency.
 * </ol>
 *
 * <p>Rule 3 is stricter than it may look. The canonical data model states single currency throughout
 * with no conversion anywhere, so a mixed-currency entry is rejected outright rather than accepted
 * with an FX leg. FX belongs to {@code payment-engine}, which carries an explicit rate and its own
 * audit trail. See the Tasks section of WP-06 for why this reading satisfies both documents.
 *
 * <p>There is no mutating operation and no way to reach one. A correction is a {@link #reverse}
 * entry that references this one.
 *
 * <p>The rest of the estate calls this a {@code Transfer}; see
 * docs/architecture/canonical-data-model.md.
 */
public final class JournalEntry {

    private final EntryRef reference;
    private final LocalDate valueDate;
    private final List<Posting> postings;
    private final EntryRef reverses;

    private JournalEntry(EntryRef reference, LocalDate valueDate, List<Posting> postings, EntryRef reverses) {
        this.reference = reference;
        this.valueDate = valueDate;
        this.postings = postings;
        this.reverses = reverses;
    }

    public static JournalEntry of(EntryRef reference, LocalDate valueDate, List<Posting> postings) {
        return create(reference, valueDate, postings, null);
    }

    private static JournalEntry create(
            EntryRef reference, LocalDate valueDate, List<Posting> postings, EntryRef reverses) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(postings, "postings");

        // Defensive copy first: the caller keeps their list, and ours can never be changed underneath us.
        List<Posting> copy = Collections.unmodifiableList(new ArrayList<>(postings));

        if (copy.size() < 2) {
            throw new IllegalArgumentException(
                    "A journal entry needs at least two postings - one side alone is not double entry -"
                            + " but had: " + copy.size());
        }
        if (copy.contains(null)) {
            throw new IllegalArgumentException("A journal entry cannot contain a null posting");
        }

        CurrencyCode currency = copy.get(0).amount().currency();
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (Posting posting : copy) {
            // Money.plus refuses to mix currencies, so this both totals and validates rule 3.
            if (posting.isDebit()) {
                debits = debits.plus(posting.amount());
            } else {
                credits = credits.plus(posting.amount());
            }
        }
        if (!debits.equals(credits)) {
            throw new UnbalancedEntryException(debits, credits);
        }

        return new JournalEntry(reference, valueDate, copy, reverses);
    }

    public EntryRef reference() {
        return reference;
    }

    public LocalDate valueDate() {
        return valueDate;
    }

    /** The postings, as an unmodifiable list. */
    public List<Posting> postings() {
        return postings;
    }

    /** The single currency of this entry. */
    public CurrencyCode currency() {
        return postings.get(0).amount().currency();
    }

    public Money totalDebits() {
        return total(true);
    }

    public Money totalCredits() {
        return total(false);
    }

    /** The entry this one reverses, or empty when it is an original. */
    public java.util.Optional<EntryRef> reverses() {
        return java.util.Optional.ofNullable(reverses);
    }

    public boolean isReversal() {
        return reverses != null;
    }

    /**
     * A new entry that undoes this one: every direction flipped, every amount unchanged, and a
     * reference back to the original.
     *
     * <p>This entry is not modified, and no operation exists that would modify it. That is the whole
     * of the correction mechanism - there is no update and no delete.
     *
     * @param newReference the reference of the reversing entry, distinct from this one's
     * @param newValueDate the value date on which the reversal takes effect
     */
    public JournalEntry reverse(EntryRef newReference, LocalDate newValueDate) {
        Objects.requireNonNull(newReference, "newReference");
        if (newReference.equals(reference)) {
            throw new IllegalArgumentException(
                    "A reversal needs its own reference; it is a new entry, not a mutation of "
                            + reference);
        }
        List<Posting> flipped = new ArrayList<>(postings.size());
        for (Posting posting : postings) {
            flipped.add(posting.reversed());
        }
        return create(newReference, newValueDate, flipped, reference);
    }

    private Money total(boolean debits) {
        Money sum = Money.zero(currency());
        for (Posting posting : postings) {
            if (posting.isDebit() == debits) {
                sum = sum.plus(posting.amount());
            }
        }
        return sum;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JournalEntry that && reference.equals(that.reference);
    }

    @Override
    public int hashCode() {
        return reference.hashCode();
    }

    @Override
    public String toString() {
        return "JournalEntry[" + reference + " " + valueDate + " " + postings.size() + " postings, "
                + totalDebits() + "]";
    }
}
