package bank.tessera.ledger.loader;

import bank.tessera.ledger.loader.LedgerRows.AccountRow;
import bank.tessera.ledger.loader.LedgerRows.AuditRow;
import bank.tessera.ledger.loader.LedgerRows.BalanceRow;
import bank.tessera.ledger.loader.LedgerRows.EntryRow;
import bank.tessera.ledger.loader.LedgerRows.HoldRow;
import bank.tessera.ledger.loader.LedgerRows.PostingRow;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

/**
 * Writes rows into PostgreSQL with {@code COPY}.
 *
 * <p><strong>Bulk load, not the API.</strong> Millions of postings through {@code POST /transfers}
 * would take days, would serialise on the audit chain's advisory lock, and would measure the driver
 * rather than the database. Standing a test environment up by bulk load is what a bank actually does,
 * and it is the honest tool here - WP-21 is the package that measures the API.
 *
 * <p><strong>One transaction per checkpoint, never one per load.</strong>
 * {@code posting_entry_balances} is a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger fired
 * for each row, so PostgreSQL queues one pending trigger event per posting until the transaction
 * commits. Five million of them in one transaction is a queue that spills to disk, and the symptom is
 * a load that gets slower rather than one that fails. Nothing is disabled to avoid it: a loader that
 * has to switch a constraint off is writing rows the ledger would have refused.
 *
 * <p>The column list is spelled out in every statement rather than left to the table's order.
 * {@code COPY} takes positional text and PostgreSQL will accept a currency code in a status column
 * because both are {@code varchar} - so the one defence against a plausible, wrong load is that the
 * order is written down where it can be read against the migration.
 */
public final class CopyRowSink implements RowSink {

    private static final String ACCOUNT_COLUMNS =
            "reference, customer_ref, account_type, currency, status, overdraft_limit_minor,"
                    + " created_at, updated_at, opened_date";
    private static final String ENTRY_COLUMNS =
            "reference, value_date, currency, created_at, reverses, reference_text";
    // No id: posting.id is GENERATED ALWAYS AS IDENTITY, and the database allocates it.
    private static final String POSTING_COLUMNS =
            "entry_ref, seq, account_ref, direction, amount_minor, currency";
    private static final String BALANCE_COLUMNS = "account_ref, booked_minor, currency, updated_at";
    private static final String HOLD_COLUMNS =
            "reference, account_ref, amount_minor, currency, status, placed_at, expires_at,"
                    + " captured_by, transitioned_at";
    // No seq: audit_record.seq is GENERATED ALWAYS AS IDENTITY, and its order is the insert order.
    private static final String AUDIT_COLUMNS =
            "occurred_at, actor, action, subject_ref, correlation_id, before_state, after_state,"
                    + " previous_hash, hash";

    private final Connection connection;
    private final CopyManager copy;

    private final StringBuilder accounts = new StringBuilder();
    private final StringBuilder entries = new StringBuilder();
    private final StringBuilder postings = new StringBuilder();
    private final StringBuilder balances = new StringBuilder();
    private final StringBuilder holds = new StringBuilder();
    private final StringBuilder audit = new StringBuilder();

    private long rowsWritten;

    public CopyRowSink(Connection connection) throws SQLException {
        this.connection = connection;
        this.connection.setAutoCommit(false);
        this.copy = new CopyManager(connection.unwrap(BaseConnection.class));
    }

    @Override
    public void account(AccountRow row) {
        field(accounts, row.reference());
        field(accounts, row.customerRef());
        field(accounts, row.accountType());
        field(accounts, row.currency());
        field(accounts, row.status());
        field(accounts, row.overdraftLimitMinor() == null ? null : String.valueOf(row.overdraftLimitMinor()));
        field(accounts, row.createdAt().toString());
        field(accounts, row.updatedAt().toString());
        last(accounts, row.openedDate().toString());
    }

    @Override
    public void entry(EntryRow row) {
        field(entries, row.reference());
        field(entries, row.valueDate().toString());
        field(entries, row.currency());
        field(entries, row.createdAt().toString());
        field(entries, row.reverses());
        last(entries, row.referenceText());
    }

    @Override
    public void posting(PostingRow row) {
        field(postings, row.entryRef());
        field(postings, String.valueOf(row.seq()));
        field(postings, row.accountRef());
        field(postings, row.direction());
        field(postings, String.valueOf(row.amountMinor()));
        last(postings, row.currency());
    }

    @Override
    public void balance(BalanceRow row) {
        field(balances, row.accountRef());
        field(balances, String.valueOf(row.bookedMinor()));
        field(balances, row.currency());
        last(balances, row.updatedAt().toString());
    }

    @Override
    public void hold(HoldRow row) {
        field(holds, row.reference());
        field(holds, row.accountRef());
        field(holds, String.valueOf(row.amountMinor()));
        field(holds, row.currency());
        field(holds, row.status());
        field(holds, row.placedAt().toString());
        field(holds, row.expiresAt() == null ? null : row.expiresAt().toString());
        field(holds, row.capturedBy());
        last(holds, row.transitionedAt() == null ? null : row.transitionedAt().toString());
    }

    @Override
    public void audit(AuditRow row) {
        field(audit, row.occurredAt().toString());
        field(audit, row.actor());
        field(audit, row.action());
        field(audit, row.subjectRef());
        field(audit, row.correlationId());
        field(audit, row.beforeState());
        field(audit, row.afterState());
        field(audit, row.previousHash());
        last(audit, row.hash());
    }

    /**
     * Flushes every buffer in the order the foreign keys require, then commits.
     *
     * <p>Accounts before the entries that post to them, entries before their postings, and everything
     * before the balances and holds that point at it. The order is the schema's, not a preference.
     */
    @Override
    public void checkpoint(LocalDate businessDate) {
        try {
            flush("account", ACCOUNT_COLUMNS, accounts);
            flush("journal_entry", ENTRY_COLUMNS, entries);
            flush("posting", POSTING_COLUMNS, postings);
            flush("hold", HOLD_COLUMNS, holds);
            flush("balance", BALANCE_COLUMNS, balances);
            flush("audit_record", AUDIT_COLUMNS, audit);
            connection.commit();
        } catch (SQLException failed) {
            throw new IllegalStateException(
                    "The load failed at the checkpoint for " + businessDate + ": " + failed.getMessage(), failed);
        }
    }

    /** How many rows have been handed to PostgreSQL. */
    public long rowsWritten() {
        return rowsWritten;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing a connection that has already committed everything it holds. There is nothing
            // a caller could do about a failure here and nothing left to lose by it.
        }
    }

    private void flush(String table, String columns, StringBuilder buffer) throws SQLException {
        if (buffer.length() == 0) {
            return;
        }
        try {
            rowsWritten += copy.copyIn(
                    "COPY " + table + " (" + columns + ") FROM STDIN", new StringReader(buffer.toString()));
        } catch (java.io.IOException impossible) {
            // The reader is over a String in memory, so there is no I/O here to fail.
            throw new IllegalStateException("Reading a buffered " + table + " row failed.", impossible);
        }
        buffer.setLength(0);
    }

    private static void field(StringBuilder buffer, String value) {
        append(buffer, value);
        buffer.append('\t');
    }

    private static void last(StringBuilder buffer, String value) {
        append(buffer, value);
        buffer.append('\n');
    }

    /**
     * Appends one value in {@code COPY}'s text format.
     *
     * <p>{@code \N} is null and is why an empty string cannot be used for one: the two are different
     * values, and a null {@code reference_text} is not a payment whose remittance information was
     * blank. The four escapes are the format's own - a backslash or a tab that arrived inside a value
     * would otherwise end the field and shift every column after it.
     */
    private static void append(StringBuilder buffer, String value) {
        if (value == null) {
            buffer.append("\\N");
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> buffer.append("\\\\");
                case '\t' -> buffer.append("\\t");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                default -> buffer.append(character);
            }
        }
    }
}
