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

    private TransferView(JournalEntry entry, Instant postedAt, EntryRef reversedBy) {
        this.entry = entry;
        this.postedAt = postedAt;
        this.reversedBy = reversedBy;
    }

    /** @param reversedBy the entry reversing this one, or null if none has been posted */
    public static TransferView of(JournalEntry entry, Instant postedAt, EntryRef reversedBy) {
        return new TransferView(
                Objects.requireNonNull(entry, "entry"),
                Objects.requireNonNull(postedAt, "postedAt"),
                reversedBy);
    }

    public JournalEntry entry() {
        return entry;
    }

    public EntryRef reference() {
        return entry.reference();
    }

    public Instant postedAt() {
        return postedAt;
    }

    public Optional<EntryRef> reversedBy() {
        return Optional.ofNullable(reversedBy);
    }

    public TransferStatus status() {
        return reversedBy == null ? TransferStatus.POSTED : TransferStatus.REVERSED;
    }

    @Override
    public String toString() {
        return "TransferView[" + entry.reference() + " " + status() + "]";
    }
}
