package bank.tessera.ledger.domain;

/**
 * Thrown when a journal entry's debits and credits do not sum equal.
 *
 * <p>This is the invariant the whole ledger rests on. It is enforced at construction, so an
 * unbalanced {@link JournalEntry} does not exist anywhere in the system - not briefly, not in a
 * partially built state, not in a test fixture.
 */
public class UnbalancedEntryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnbalancedEntryException(Money debits, Money credits) {
        super("Journal entry does not balance: debits total " + debits.toPlainString()
                + " but credits total " + credits.toPlainString() + " " + debits.currency());
    }
}
