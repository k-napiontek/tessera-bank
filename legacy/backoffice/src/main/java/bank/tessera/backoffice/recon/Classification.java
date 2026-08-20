package bank.tessera.backoffice.recon;

/**
 * The four ways an account can break, as {@code TB-RECON-BREAKS-V1} names them.
 *
 * <p>{@code TIMING} is the one that is <strong>not actionable</strong>. It means the master holds
 * exactly what the cut-off says it should and the difference is movements posted after the cycle's
 * input was cut - expected, and listed so that it is visibly not drift. A screen that invites an
 * operator to work one undoes what ADR 0015 was for, and paging on it is how a report becomes one
 * nobody reads.
 */
public enum Classification {

    VALUE_DRIFT(true, "Both systems hold the account and the booked balances differ"),
    MISSING_ON_MASTER(true, "The ledger holds the account, the master does not"),
    MISSING_IN_LEDGER(true, "The master holds the account, the ledger does not"),
    TIMING(false, "Explained by movements posted after the cut-off - expected");

    private final boolean actionable;
    private final String meaning;

    private Classification(boolean actionable, String meaning) {
        this.actionable = actionable;
        this.meaning = meaning;
    }

    /** Whether an operator has anything to do about it. False for {@code TIMING} alone. */
    public boolean isActionable() {
        return actionable;
    }

    public String getMeaning() {
        return meaning;
    }
}
