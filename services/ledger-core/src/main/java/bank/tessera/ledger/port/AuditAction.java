package bank.tessera.ledger.port;

/**
 * What an audit row says happened.
 *
 * <p>An enumeration rather than free text, because the audit trail is read by people looking for a
 * particular kind of event months after it happened, and a free-text action becomes six spellings of
 * the same thing. Adding a value is a deliberate act; a typo is not.
 */
public enum AuditAction {
    ACCOUNT_OPENED,
    TRANSFER_POSTED,
    TRANSFER_REVERSED,
    HOLD_PLACED,
    HOLD_CAPTURED,
    HOLD_RELEASED
}
