package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.port.AccountRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acquires row locks on several accounts in a deterministic order.
 *
 * <p><strong>This is the only sanctioned way to lock more than one account.</strong> Calling
 * {@link AccountRepository#findForUpdate} twice in whatever order the caller happens to hold the
 * references is how two transfers deadlock: thread A locks account 1 then waits for account 2 while
 * thread B holds account 2 and waits for account 1. Neither can proceed, and PostgreSQL resolves it by
 * killing one transaction - so the symptom is a failed transfer under load and nothing at all in
 * testing.
 *
 * <p>Sorting by account reference removes the cycle. Every transaction that needs the same two accounts
 * takes them in the same order, so one waits and then proceeds instead of the pair blocking each other.
 * The order itself is arbitrary; that it is <em>the same order every time</em> is the whole mechanism.
 *
 * <p>The port cannot express this: {@code findForUpdate} takes one reference, and widening it to take a
 * collection would be an adapter's concern reaching back into the domain's interface. So the rule lives
 * here, where the ring-transfer test can prove it holds.
 */
public final class AccountLocks {

    private final AccountRepository accounts;
    private final LockWaits lockWaits;

    public AccountLocks(AccountRepository accounts) {
        this(accounts, LockWaits.UNMEASURED);
    }

    public AccountLocks(AccountRepository accounts, LockWaits lockWaits) {
        this.accounts = accounts;
        this.lockWaits = lockWaits;
    }

    /**
     * Locks every named account, in ascending reference order.
     *
     * @return the locked accounts, in the order they were locked
     * @throws IllegalStateException if any account does not exist - locking for money movement against
     *     an account that is not there is never valid, and returning a short map would let the caller
     *     move money on the assumption it succeeded
     */
    public Map<AccountRef, Account> lockInOrder(Collection<AccountRef> references) {
        List<AccountRef> ordered = references.stream()
                .distinct()
                .sorted(Comparator.comparing(AccountRef::value))
                .toList();

        // Timed as one acquisition rather than one per account: what a transfer waits for is all
        // of its locks, and a per-account figure would report two short waits where there was one
        // long one.
        return lockWaits.timing(LockWaits.Kind.ACCOUNT, () -> {
            Map<AccountRef, Account> locked = new LinkedHashMap<>();
            for (AccountRef reference : ordered) {
                Account account = accounts
                        .findForUpdate(reference)
                        .orElseThrow(() -> new IllegalStateException(
                                "Cannot lock account " + reference + ": it does not exist."));
                locked.put(reference, account);
            }
            return locked;
        });
    }
}
