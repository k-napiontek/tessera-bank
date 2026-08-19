package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.EntryRef;

/**
 * Thrown when a transfer that has already been reversed is reversed again.
 *
 * <p>A conflict, answered with {@code 409}. Reversing twice credits the account twice for a single
 * erroneous debit, and the second reversal balances perfectly - so nothing downstream reports it.
 * The unique index on {@code journal_entry.reverses} enforces the same rule in the database, because
 * two concurrent reversal requests both pass a check made in application code.
 */
public class AlreadyReversedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AlreadyReversedException(EntryRef original, EntryRef reversal) {
        super("Transfer " + original + " was already reversed by " + reversal + ".");
    }
}
