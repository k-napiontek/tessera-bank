package bank.tessera.backoffice.recon;

import java.util.Collections;
import java.util.List;

/** A morning's reconciliation, as {@code batch/recon} wrote it. */
public final class BreakReport {

    private final String businessDate;
    private final long ledgerPosition;
    private final String ledgerChainHash;
    private final String movementFile;
    private final int transferRefCount;
    private final String masterFileName;
    private final int masterRecordCount;
    private final List<Break> breaks;
    private final int accountsCompared;
    private final int accountsMatched;
    private final int accountsBroken;
    private final long totalAbsoluteDriftMinor;

    BreakReport(String businessDate, long ledgerPosition, String ledgerChainHash,
            String movementFile, int transferRefCount, String masterFileName, int masterRecordCount,
            List<Break> breaks, int accountsCompared, int accountsMatched, int accountsBroken,
            long totalAbsoluteDriftMinor) {
        this.businessDate = businessDate;
        this.ledgerPosition = ledgerPosition;
        this.ledgerChainHash = ledgerChainHash;
        this.movementFile = movementFile;
        this.transferRefCount = transferRefCount;
        this.masterFileName = masterFileName;
        this.masterRecordCount = masterRecordCount;
        this.breaks = Collections.unmodifiableList(breaks);
        this.accountsCompared = accountsCompared;
        this.accountsMatched = accountsMatched;
        this.accountsBroken = accountsBroken;
        this.totalAbsoluteDriftMinor = totalAbsoluteDriftMinor;
    }

    public String getBusinessDate() {
        return businessDate;
    }

    /** Which cut of the ledger this is. An operator working a break needs to know. */
    public long getLedgerPosition() {
        return ledgerPosition;
    }

    public String getLedgerChainHash() {
        return ledgerChainHash;
    }

    public String getMovementFile() {
        return movementFile;
    }

    /**
     * How many transfers the cut-off admitted. A report cut against an empty or wrong movement file
     * shows an implausible count here - the runbook tells operators to check it first.
     */
    public int getTransferRefCount() {
        return transferRefCount;
    }

    public String getMasterFileName() {
        return masterFileName;
    }

    public int getMasterRecordCount() {
        return masterRecordCount;
    }

    public List<Break> getBreaks() {
        return breaks;
    }

    public int getAccountsCompared() {
        return accountsCompared;
    }

    public int getAccountsMatched() {
        return accountsMatched;
    }

    public int getAccountsBroken() {
        return accountsBroken;
    }

    public java.math.BigDecimal getTotalAbsoluteDrift() {
        return java.math.BigDecimal.valueOf(totalAbsoluteDriftMinor, 2);
    }

    /** How many breaks an operator actually has to work. Timing is not one of them. */
    public int getActionableCount() {
        int actionable = 0;
        for (int i = 0; i < breaks.size(); i++) {
            if (breaks.get(i).isActionable()) {
                actionable++;
            }
        }
        return actionable;
    }
}
