package bank.tessera.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A reservation against an account's available balance.
 *
 * <p>A hold moves no money. It makes part of the booked balance unavailable until it is captured
 * into a journal entry, released, or expires. Booked balance is untouched throughout - which is
 * exactly why capture must post the entry and clear the hold together, or available balance would be
 * reduced twice for one payment.
 *
 * <p>Immutable: each transition returns a new instance, which carries the instant of that
 * transition. It did not always: until WP-09 every transition validated the instant it was given and
 * then rebuilt from {@code placedAt}, so a released hold could not say when it was released
 * (follow-up F-21). The audit chain records when a thing happened, and the placement instant is not
 * an answer to that.
 *
 * <p>Traces to {@code Hold} in docs/architecture/canonical-data-model.md. Strata 3 and 4 only; the
 * mainframe has no such concept.
 */
public final class Hold {

    private final HoldRef reference;
    private final AccountRef account;
    private final Money amount;
    private final HoldStatus status;
    private final Instant placedAt;
    private final Instant expiresAt;
    private final Instant transitionedAt;
    private final EntryRef capturedBy;

    private Hold(
            HoldRef reference,
            AccountRef account,
            Money amount,
            HoldStatus status,
            Instant placedAt,
            Instant expiresAt,
            Instant transitionedAt,
            EntryRef capturedBy) {
        this.reference = reference;
        this.account = account;
        this.amount = amount;
        this.status = status;
        this.placedAt = placedAt;
        this.expiresAt = expiresAt;
        this.transitionedAt = transitionedAt;
        this.capturedBy = capturedBy;
    }

    /**
     * @param expiresAt when the hold lapses on its own, or {@code null} if it does not
     */
    public static Hold place(
            HoldRef reference, AccountRef account, Money amount, Instant placedAt, Instant expiresAt) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(placedAt, "placedAt");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A hold amount must be strictly positive, but was: " + amount);
        }
        return new Hold(reference, account, amount, HoldStatus.PLACED, placedAt, expiresAt, null, null);
    }

    public HoldRef reference() {
        return reference;
    }

    public AccountRef account() {
        return account;
    }

    public Money amount() {
        return amount;
    }

    public HoldStatus status() {
        return status;
    }

    public Instant placedAt() {
        return placedAt;
    }

    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    public Optional<EntryRef> capturedBy() {
        return Optional.ofNullable(capturedBy);
    }

    /** When this hold left {@code PLACED}, or empty while it is still placed. */
    public Optional<Instant> transitionedAt() {
        return Optional.ofNullable(transitionedAt);
    }

    /** Whether this hold still reduces available balance. */
    public boolean isActive() {
        return status.isActive();
    }

    public Hold capture(EntryRef entry, Instant at) {
        Objects.requireNonNull(entry, "entry");
        return transitionTo(HoldStatus.CAPTURED, at, entry);
    }

    public Hold release(Instant at) {
        return transitionTo(HoldStatus.RELEASED, at, null);
    }

    public Hold expire(Instant at) {
        return transitionTo(HoldStatus.EXPIRED, at, null);
    }

    private Hold transitionTo(HoldStatus target, Instant at, EntryRef entry) {
        Objects.requireNonNull(at, "at");
        if (!status.isActive()) {
            throw new IllegalStateException(
                    "Hold " + reference + " is already " + status
                            + " and cannot become " + target + "; every transition out of PLACED is final.");
        }
        if (at.isBefore(placedAt)) {
            // Kept rather than clamped. A hold that ended before it began is a caller passing the
            // wrong instant, and the audit chain would record the wrong instant as fact.
            throw new IllegalArgumentException(
                    "Hold " + reference + " cannot become " + target + " at " + at
                            + ", which is before it was placed at " + placedAt + ".");
        }
        return new Hold(reference, account, amount, target, placedAt, expiresAt, at, entry);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Hold that && reference.equals(that.reference) && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(reference, status);
    }

    @Override
    public String toString() {
        return "Hold[" + reference + " " + amount + " " + status + "]";
    }
}
