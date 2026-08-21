package bank.tessera.ledger.loader;

import bank.tessera.ledger.loader.LedgerRows.AccountRow;
import bank.tessera.ledger.loader.LedgerRows.AuditRow;
import bank.tessera.ledger.loader.LedgerRows.BalanceRow;
import bank.tessera.ledger.loader.LedgerRows.EntryRow;
import bank.tessera.ledger.loader.LedgerRows.HoldRow;
import bank.tessera.ledger.loader.LedgerRows.PostingRow;
import java.time.LocalDate;

/**
 * Where the rows go.
 *
 * <p>An interface so that the arithmetic can be tested without a database and the database can be
 * driven without restating the arithmetic. {@code endOfDay} is the seam that matters at volume: it is
 * where the {@code COPY} writer commits, and it exists because
 * {@code posting_entry_balances} is a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger fired
 * for each row - PostgreSQL holds one pending event per posting until the transaction commits, so a
 * year in one transaction is a queue that spills to disk rather than a load that fails.
 */
public interface RowSink extends AutoCloseable {

    void account(AccountRow row);

    void entry(EntryRow row);

    void posting(PostingRow row);

    void hold(HoldRow row);

    void balance(BalanceRow row);

    void audit(AuditRow row);

    /**
     * Everything written so far forms a consistent set. A durable sink commits here.
     *
     * <p>Called at the end of every business date, and more than once during the opening phase - an
     * account row, its funding entry and that entry's postings are complete the moment they are
     * written, so the opening of three hundred thousand accounts need not be one transaction either.
     */
    void checkpoint(LocalDate businessDate);

    @Override
    void close();
}
