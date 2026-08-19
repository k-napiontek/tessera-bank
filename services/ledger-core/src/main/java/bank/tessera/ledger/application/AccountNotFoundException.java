package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.AccountRef;

/** Thrown when an operation names an account the ledger does not hold. */
public class AccountNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountNotFoundException(AccountRef reference) {
        super("No such account: " + reference + ".");
    }
}
