package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.port.UnitOfWork;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary, against PostgreSQL.
 *
 * <p>The interesting half is {@link #inTransactionLocking}, which delegates to {@link AccountLocks}
 * rather than locking the accounts itself. That keeps the deterministic ordering rule in the one
 * place WP-07 put it: sorting by reference before taking each {@code SELECT ... FOR UPDATE} is what
 * stops two transfers over the same pair of accounts deadlocking, and it was proved load-bearing by
 * deleting the sort and watching PostgreSQL report five deadlocks.
 *
 * <p>Locks are taken <em>before</em> the work runs, not lazily inside it. A use case that read one
 * account, decided, and only then locked the second would have made its decision on a balance
 * another transaction was free to change.
 */
public final class JdbcUnitOfWork implements UnitOfWork {

    private final TransactionTemplate transactions;
    private final AccountLocks locks;

    public JdbcUnitOfWork(TransactionTemplate transactions, AccountLocks locks) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return transactions.execute(status -> work.get());
    }

    @Override
    public <T> T inTransactionLocking(Collection<AccountRef> accounts, Supplier<T> work) {
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(work, "work");
        return transactions.execute(status -> {
            locks.lockInOrder(accounts);
            return work.get();
        });
    }
}
