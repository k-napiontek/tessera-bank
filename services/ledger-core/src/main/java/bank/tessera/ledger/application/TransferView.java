package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A transfer as the API contract describes it: the journal entry that is its accounting form, when
 * the ledger recorded it, and its derived status.
 *
 * <p>The estate calls one thing by two names on purpose. A transfer is the customer's intent; the
 * journal entry is what the books hold. They share a reference so the two views can never disagree
 * about which is which, and this type is where the mapping happens once instead of in every caller.
 */
public final class TransferView {

    private final JournalEntry entry;
    private final Instant postedAt;
    private final EntryRef reversedBy;
    private final String reference;

    private TransferView(JournalEntry entry, Instant postedAt, EntryRef reversedBy, String reference) {
        this.entry = entry;
        this.postedAt = postedAt;
        this.reversedBy = reversedBy;
        this.reference = reference;
    }

    /**
     * @param reversedBy the entry reversing this one, or null if none has been posted
     * @param reference remittance information, or null
     */
    public static TransferView of(
            JournalEntry entry, Instant postedAt, EntryRef reversedBy, String reference) {
        return new TransferView(
                Objects.requireNonNull(entry, "entry"),
                Objects.requireNonNull(postedAt, "postedAt"),
                reversedBy,
                reference);
    }

    public JournalEntry entry() {
        return entry;
    }

    /** The estate-wide reference the contract calls {@code transferRef}. */
    public EntryRef transferReference() {
        return entry.reference();
    }

    public Instant postedAt() {
        return postedAt;
    }

    public Optional<EntryRef> reversedBy() {
        return Optional.ofNullable(reversedBy);
    }

    /** Remittance information, which the contract calls {@code reference}. */
    public Optional<String> remittanceReference() {
        return Optional.ofNullable(reference);
    }

    public TransferStatus status() {
        return reversedBy == null ? TransferStatus.POSTED : TransferStatus.REVERSED;
    }

    /** The debit leg's account, which the contract calls {@code debitAccountRef}. */
    public bank.tessera.ledger.domain.AccountRef debitAccount() {
        return entry.postings().stream()
                .filter(bank.tessera.ledger.domain.Posting::isDebit)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Entry " + entry.reference() + " has no debit leg."))
                .account();
    }

    /** The credit leg's account, which the contract calls {@code creditAccountRef}. */
    public bank.tessera.ledger.domain.AccountRef creditAccount() {
        return entry.postings().stream()
                .filter(posting -> !posting.isDebit())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Entry " + entry.reference() + " has no credit leg."))
                .account();
    }

    /** The amount both legs carry. An entry that balances has exactly one. */
    public bank.tessera.ledger.domain.Money amount() {
        return entry.totalDebits();
    }

    @Override
    public String toString() {
        return "TransferView[" + entry.reference() + " " + status() + "]";
    }
}
