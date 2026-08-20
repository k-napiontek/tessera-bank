package bank.tessera.backoffice.recon;

import java.math.BigDecimal;

/** One account the two cores do not agree about. */
public final class Break {

    private final String accountRef;
    private final Classification classification;
    private final String currency;
    private final Long masterBookedMinor;
    private final Long ledgerBookedMinor;
    private final Long differenceMinor;

    Break(String accountRef, Classification classification, String currency, Long masterBookedMinor,
            Long ledgerBookedMinor, Long differenceMinor) {
        this.accountRef = accountRef;
        this.classification = classification;
        this.currency = currency;
        this.masterBookedMinor = masterBookedMinor;
        this.ledgerBookedMinor = ledgerBookedMinor;
        this.differenceMinor = differenceMinor;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public Classification getClassification() {
        return classification;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActionable() {
        return classification.isActionable();
    }

    /** Null when the master has no such account - see the contract on why that is not a zero. */
    public BigDecimal getMasterBooked() {
        return amount(masterBookedMinor);
    }

    public BigDecimal getLedgerBooked() {
        return amount(ledgerBookedMinor);
    }

    /**
     * Null when either side is absent. A difference implies two figures were compared, and printing
     * one of them as a difference invites an operator to read a missing account as drift.
     */
    public BigDecimal getDifference() {
        return amount(differenceMinor);
    }

    private static BigDecimal amount(Long minorUnits) {
        return minorUnits == null ? null : BigDecimal.valueOf(minorUnits.longValue(), 2);
    }
}
