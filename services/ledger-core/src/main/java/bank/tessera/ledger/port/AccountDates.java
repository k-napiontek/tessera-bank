package bank.tessera.ledger.port;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * The two business dates the ledger keeps for an account and the {@code Account} aggregate does not.
 *
 * <p>Neither participates in an invariant. {@code openedDate} is a business date supplied when the
 * account is opened - not the instant its row was inserted, which differs the moment an account is
 * migrated in from the mainframe. {@code lastMovementDate} is derived: the latest value date of any
 * posting on the account.
 *
 * <p>They live here rather than on the aggregate for the same reason it holds no balance. Adding a
 * derived field to an aggregate to satisfy a response schema is a persistence-shaped back door, and
 * this repository has already declined to open one for {@code Hold} (follow-up F-21).
 */
public final class AccountDates {

    private final LocalDate opened;
    private final LocalDate lastMovement;

    private AccountDates(LocalDate opened, LocalDate lastMovement) {
        this.opened = opened;
        this.lastMovement = lastMovement;
    }

    /** @param lastMovement null until the first movement posts */
    public static AccountDates of(LocalDate opened, LocalDate lastMovement) {
        return new AccountDates(Objects.requireNonNull(opened, "opened"), lastMovement);
    }

    public LocalDate opened() {
        return opened;
    }

    public Optional<LocalDate> lastMovement() {
        return Optional.ofNullable(lastMovement);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AccountDates that
                && opened.equals(that.opened)
                && Objects.equals(lastMovement, that.lastMovement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(opened, lastMovement);
    }

    @Override
    public String toString() {
        return "AccountDates[opened " + opened + ", lastMovement " + lastMovement + "]";
    }
}
