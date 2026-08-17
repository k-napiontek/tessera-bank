package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import java.util.Optional;

/** Retrieval and storage of accounts, in domain terms only. */
public interface AccountRepository {

    Optional<Account> findByReference(AccountRef reference);

    /**
     * Loads an account for update, taking whatever lock the adapter needs.
     *
     * <p>Named so at the port because the caller must know it is doing something stronger than a
     * read - WP-07 implements it as {@code SELECT ... FOR UPDATE} in deterministic account order,
     * which is what keeps concurrent transfers from deadlocking.
     */
    Optional<Account> findForUpdate(AccountRef reference);

    Account save(Account account);
}
