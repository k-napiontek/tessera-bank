package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One leg of a posting, as a statement reports it.
 *
 * <p>{@link bank.tessera.ledger.domain.Posting} is what the books hold: an account, a direction and
 * an amount, and nothing else, because nothing else takes part in a double-entry invariant. A
 * statement line needs more - which entry the leg belongs to, which leg it is, what date it takes
 * effect, when the ledger recorded it, and what the payment was for - and every one of those is
 * carried by the entry or by the row rather than by the posting.
 *
 * <p>So this is a projection, assembled by the read model, not an aggregate. It is the {@code
 * Movement} of the canonical data model and of the OpenAPI contract.
 */
public final class Movement {

    private final EntryRef entry;
    private final int legNo;
    private final AccountRef account;
    private final Direction direction;
    private final Money amount;
    private final LocalDate valueDate;
    private final Instant postedAt;
    private final String reference;

    private Movement(
            EntryRef entry,
            int legNo,
            AccountRef account,
            Direction direction,
            Money amount,
            LocalDate valueDate,
            Instant postedAt,
            String reference) {
        this.entry = entry;
        this.legNo = legNo;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.valueDate = valueDate;
        this.postedAt = postedAt;
        this.reference = reference;
    }

    /**
     * @param legNo 1 for the debit leg, 2 for the credit leg, per the canonical data model
     * @param reference remittance information, or null
     */
    public static Movement of(
            EntryRef entry,
            int legNo,
            AccountRef account,
            Direction direction,
            Money amount,
            LocalDate valueDate,
            Instant postedAt,
            String reference) {
        if (legNo < 1 || legNo > 99) {
            throw new IllegalArgumentException("Leg number must be between 1 and 99, but was: " + legNo);
        }
        return new Movement(
                Objects.requireNonNull(entry, "entry"),
                legNo,
                Objects.requireNonNull(account, "account"),
                Objects.requireNonNull(direction, "direction"),
                Objects.requireNonNull(amount, "amount"),
                Objects.requireNonNull(valueDate, "valueDate"),
                Objects.requireNonNull(postedAt, "postedAt"),
                reference);
    }

    /** The entry reference, a hyphen, then the two-digit leg number. */
    public String movementReference() {
        return entry.value() + "-" + String.format("%02d", legNo);
    }

    public EntryRef entry() {
        return entry;
    }

    public int legNo() {
        return legNo;
    }

    public AccountRef account() {
        return account;
    }

    public Direction direction() {
        return direction;
    }

    /** Always positive. Direction carries the sign, the amount never does. */
    public Money amount() {
        return amount;
    }

    public LocalDate valueDate() {
        return valueDate;
    }

    public Instant postedAt() {
        return postedAt;
    }

    public Optional<String> reference() {
        return Optional.ofNullable(reference);
    }

    @Override
    public String toString() {
        return "Movement[" + movementReference() + " " + direction + " " + amount + "]";
    }
}
