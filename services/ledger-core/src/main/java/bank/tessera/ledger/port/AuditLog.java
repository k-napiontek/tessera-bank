package bank.tessera.ledger.port;

/**
 * The append-only trail of what the ledger was told to do and what it did.
 *
 * <p>One method, and it returns nothing. An audit log a caller can query is a read model; an audit
 * log a caller can amend is not an audit log. Reading and verifying the chain is an operator's job
 * and lives with the adapter, not on the path a transfer takes.
 *
 * <p><strong>The append joins the caller's transaction.</strong> It opens none of its own, so an
 * audit row cannot survive a rolled-back transfer and a committed transfer cannot lack one. That is
 * the same argument the outbox makes, for the same reason.
 */
public interface AuditLog {

    /** Appends one entry, chained onto whatever is currently last. */
    void append(AuditEntry entry);
}
