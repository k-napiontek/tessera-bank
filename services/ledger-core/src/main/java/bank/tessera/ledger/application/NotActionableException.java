package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.Money;

/**
 * A request that is well formed but cannot be carried out.
 *
 * <p>One type for the whole class of outcome, because the contract has one status for it: the
 * OpenAPI document's {@code 422} covers "insufficient funds, currency mismatch, an account that is
 * not OPEN, or an amount that is not strictly positive". Splitting these into four exception classes
 * would produce four ways to say the same thing to a client, and a handler that had to remember all
 * of them.
 *
 * <p>Distinct from {@link AccountNotFoundException}, which is a {@code 404}, and from
 * {@code IllegalArgumentException}, which means the request was malformed and is a {@code 400}. The
 * difference matters to a caller: a {@code 422} will keep failing until something about the account
 * changes, and a {@code 400} will keep failing until the caller changes.
 */
public class NotActionableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotActionableException(String message) {
        super(message);
    }

    public static NotActionableException accountNotOpen(AccountRef account, AccountStatus status) {
        return new NotActionableException("Account " + account + " is " + status + " and cannot be posted to.");
    }

    public static NotActionableException amountNotPositive(Money amount) {
        // Direction carries the sign in this ledger, so a negative amount is not "a debit written
        // the other way" - it is a request that would post a negative credit, and the domain would
        // reject it several layers deeper with a message about postings.
        return new NotActionableException(
                "Amount must be strictly positive, but was " + amount.toPlainString() + " " + amount.currency() + ".");
    }

    public static NotActionableException sameAccount(AccountRef account) {
        return new NotActionableException(
                "A transfer must name two different accounts, but both legs named " + account + ".");
    }
}
