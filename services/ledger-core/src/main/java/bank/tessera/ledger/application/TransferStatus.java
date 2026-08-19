package bank.tessera.ledger.application;

/**
 * The status of a transfer as the API reports it.
 *
 * <p><strong>Derived, never stored.</strong> {@code ACCEPTED} and {@code REJECTED} describe a
 * request that has not become a journal entry, and a rejected transfer is a Problem document rather
 * than a row - storing it would put a record in the ledger for money that never moved. Anything the
 * ledger holds is therefore {@code POSTED}, or {@code REVERSED} once another entry names it.
 */
public enum TransferStatus {
    ACCEPTED,
    POSTED,
    REJECTED,
    REVERSED
}
