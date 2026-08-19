package bank.tessera.customer.domain;

import java.util.Date;

/**
 * Account metadata as this tier holds it.
 *
 * <p>Carries no customer identity beyond the reference. The name and the national identifier sit in
 * the customer table and never leave this component - the canonical schema gives the wire a
 * customerRef and nothing else, which is what keeps every other component out of scope for an
 * erasure request.
 */
public final class Account {

    private final String accountRef;
    private final String customerRef;
    private final String accountType;
    private final String status;
    private final Money bookedBalance;
    private final Date openedDate;
    private final Date lastMovementDate;

    public Account(String accountRef, String customerRef, String accountType, String status,
            Money bookedBalance, Date openedDate, Date lastMovementDate) {
        this.accountRef = accountRef;
        this.customerRef = customerRef;
        this.accountType = accountType;
        this.status = status;
        this.bookedBalance = bookedBalance;
        this.openedDate = openedDate;
        this.lastMovementDate = lastMovementDate;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return bookedBalance.getCurrency();
    }

    public String getStatus() {
        return status;
    }

    public Money getBookedBalance() {
        return bookedBalance;
    }

    /**
     * Equal to the booked balance, always, and this is a statement rather than an omission.
     *
     * <p>A hold lives in the ledger and nothing tells this component about one, so the honest
     * available balance here is the booked balance. Storing a second column and letting it drift
     * would be worse, and computing booked-less-holds from holds this tier cannot see would be a
     * number invented to fill a mandatory field. The contract requires the element; this returns
     * the only value it can defend, and the README says so.
     */
    public Money getAvailableBalance() {
        return bookedBalance;
    }

    public Date getOpenedDate() {
        return openedDate;
    }

    /** Null until the first movement posts. */
    public Date getLastMovementDate() {
        return lastMovementDate;
    }
}
