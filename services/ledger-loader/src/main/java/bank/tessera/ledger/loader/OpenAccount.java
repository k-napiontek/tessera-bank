package bank.tessera.ledger.loader;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CustomerRef;
import java.util.Objects;

/**
 * One account the loaded estate holds.
 *
 * <p>The references are the domain's own types rather than strings, so a stream carrying a malformed
 * reference is refused at the boundary rather than by PostgreSQL, three million rows into a load.
 *
 * @param cohort the cohort of the customer holding it, empty for the treasury
 */
public record OpenAccount(
        CustomerRef customer, AccountRef account, AccountType type, String cohort, boolean treasury) {

    public OpenAccount {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(type, "type");
        if (treasury == (cohort != null && !cohort.isEmpty())) {
            throw new IllegalArgumentException(
                    "The treasury belongs to no cohort and every customer account belongs to one, but "
                            + account + " is treasury=" + treasury + " in cohort '" + cohort + "'");
        }
    }
}
