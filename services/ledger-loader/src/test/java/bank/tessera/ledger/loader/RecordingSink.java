package bank.tessera.ledger.loader;

import bank.tessera.ledger.loader.LedgerRows.AccountRow;
import bank.tessera.ledger.loader.LedgerRows.AuditRow;
import bank.tessera.ledger.loader.LedgerRows.BalanceRow;
import bank.tessera.ledger.loader.LedgerRows.EntryRow;
import bank.tessera.ledger.loader.LedgerRows.HoldRow;
import bank.tessera.ledger.loader.LedgerRows.PostingRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A sink that keeps every row, so the loader's arithmetic can be checked without a database.
 *
 * <p>It records the checkpoints too. That the {@code COPY} writer commits at those points is what
 * bounds the deferred balanced-entry trigger's pending queue, and a loader that stopped calling them
 * would still pass every other test here.
 */
final class RecordingSink implements RowSink {

    final List<AccountRow> accounts = new ArrayList<>();
    final List<EntryRow> entries = new ArrayList<>();
    final List<PostingRow> postings = new ArrayList<>();
    final List<HoldRow> holds = new ArrayList<>();
    final List<BalanceRow> balances = new ArrayList<>();
    final List<AuditRow> audit = new ArrayList<>();
    final List<LocalDate> checkpoints = new ArrayList<>();
    boolean closed;

    @Override
    public void account(AccountRow row) {
        accounts.add(row);
    }

    @Override
    public void entry(EntryRow row) {
        entries.add(row);
    }

    @Override
    public void posting(PostingRow row) {
        postings.add(row);
    }

    @Override
    public void hold(HoldRow row) {
        holds.add(row);
    }

    @Override
    public void balance(BalanceRow row) {
        balances.add(row);
    }

    @Override
    public void audit(AuditRow row) {
        audit.add(row);
    }

    @Override
    public void checkpoint(LocalDate businessDate) {
        checkpoints.add(businessDate);
    }

    @Override
    public void close() {
        closed = true;
    }
}
