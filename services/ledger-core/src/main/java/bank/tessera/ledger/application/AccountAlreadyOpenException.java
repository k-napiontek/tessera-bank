package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.AccountRef;

/**
 * Thrown when an account is opened at a reference that already exists.
 *
 * <p>A conflict rather than a validation failure: the request was well formed, and the caller may
 * simply be retrying. Opening is idempotent by reference precisely so that a retry is safe - the
 * second attempt is refused rather than silently opening a second account.
 */
public class AccountAlreadyOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountAlreadyOpenException(AccountRef reference) {
        super("Account " + reference + " is already open.");
    }
}
