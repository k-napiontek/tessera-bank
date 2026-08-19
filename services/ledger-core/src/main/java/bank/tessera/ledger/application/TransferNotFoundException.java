package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.EntryRef;

/** Thrown when an operation names a transfer the ledger does not hold. */
public class TransferNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransferNotFoundException(EntryRef reference) {
        super("No such transfer: " + reference + ".");
    }
}
