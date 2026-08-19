package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.HoldRef;

/** Thrown when an operation names a hold the ledger does not hold. */
public class HoldNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HoldNotFoundException(HoldRef reference) {
        super("No such hold: " + reference + ".");
    }
}
