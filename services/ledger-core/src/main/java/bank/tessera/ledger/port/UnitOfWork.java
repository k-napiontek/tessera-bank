package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.AccountRef;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * The transaction boundary a use case runs inside.
 *
 * <p>A transfer reads two accounts, decides, and appends an entry. All three have to happen in one
 * transaction with both accounts locked, or two concurrent transfers can each read a balance the
 * other is about to change. The use case knows it needs that; it must not know how a transaction is
 * begun, and this port is why it does not have to.
 *
 * <p><strong>Locking more than one account goes through {@link #inTransactionLocking}, never through
 * repeated calls to {@code AccountRepository.findForUpdate}.</strong> The adapter acquires the locks
 * in a deterministic order - WP-07 proved that rule load-bearing by deleting it and watching
 * PostgreSQL report five deadlocks - and that ordering stays in the adapter where it belongs. This
 * port asks for a set of accounts, not a sequence, precisely so that no caller can choose an order.
 */
public interface UnitOfWork {

    /** Runs {@code work} in one transaction, taking no locks up front. */
    <T> T inTransaction(Supplier<T> work);

    /**
     * Runs {@code work} in one transaction with every named account locked for update.
     *
     * @param accounts the accounts to lock; duplicates are collapsed and the order is the adapter's
     * @throws IllegalStateException if any named account does not exist
     */
    <T> T inTransactionLocking(Collection<AccountRef> accounts, Supplier<T> work);
}
