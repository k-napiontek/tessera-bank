package bank.tessera.customer.data;

import java.sql.Date;

/** One generated account. Balances are a signed count of minor units, never a decimal. */
public final class SyntheticAccount {

    private final String accountRef;
    private final String customerRef;
    private final String accountType;
    private final String currency;
    private final String status;
    private final long bookedBalanceMinor;
    private final Date openedDate;
    private final Date lastMovementDate;

    SyntheticAccount(String accountRef, String customerRef, String accountType, String currency,
            String status, long bookedBalanceMinor, Date openedDate, Date lastMovementDate) {
        this.accountRef = accountRef;
        this.customerRef = customerRef;
        this.accountType = accountType;
        this.currency = currency;
        this.status = status;
        this.bookedBalanceMinor = bookedBalanceMinor;
        this.openedDate = openedDate;
        this.lastMovementDate = lastMovementDate;
    }

    public String accountRef() {
        return accountRef;
    }

    public String customerRef() {
        return customerRef;
    }

    public String accountType() {
        return accountType;
    }

    public String currency() {
        return currency;
    }

    public String status() {
        return status;
    }

    public long bookedBalanceMinor() {
        return bookedBalanceMinor;
    }

    public Date openedDate() {
        return openedDate;
    }

    /** Null until the first movement posts, exactly as the canonical model says. */
    public Date lastMovementDate() {
        return lastMovementDate;
    }
}
