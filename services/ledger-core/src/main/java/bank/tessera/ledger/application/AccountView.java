package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.port.AccountDates;
import java.util.Objects;

/**
 * An account as the API contract describes it: the aggregate, its two balances, and the two dates
 * the read model keeps.
 *
 * <p>Assembled by a use case rather than by a controller, so the three pieces are always fetched
 * inside the same transaction and cannot disagree about the account they describe.
 */
public final class AccountView {

    private final Account account;
    private final Balance balance;
    private final AccountDates dates;

    private AccountView(Account account, Balance balance, AccountDates dates) {
        this.account = account;
        this.balance = balance;
        this.dates = dates;
    }

    public static AccountView of(Account account, Balance balance, AccountDates dates) {
        return new AccountView(
                Objects.requireNonNull(account, "account"),
                Objects.requireNonNull(balance, "balance"),
                Objects.requireNonNull(dates, "dates"));
    }

    public Account account() {
        return account;
    }

    public Balance balance() {
        return balance;
    }

    public AccountDates dates() {
        return dates;
    }

    @Override
    public String toString() {
        return "AccountView[" + account + " " + balance + " " + dates + "]";
    }
}
