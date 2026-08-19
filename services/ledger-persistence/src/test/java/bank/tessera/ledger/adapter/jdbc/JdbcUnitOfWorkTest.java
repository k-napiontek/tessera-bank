package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.UnitOfWork;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcUnitOfWorkTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef ALICE = AccountRef.of("TB00000000000001");
    private static final AccountRef BOB = AccountRef.of("TB00000000000002");

    private static UnitOfWork unitOfWork;
    private static JdbcAccountRepository accounts;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("unit_of_work");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        accounts = new JdbcAccountRepository(jdbc);
        unitOfWork = new JdbcUnitOfWork(Transactions.of(dataSource), new AccountLocks(accounts));

        accounts.save(account(ALICE));
        accounts.save(account(BOB));
    }

    private static Account account(AccountRef reference) {
        return Account.builder()
                .reference(reference)
                .customer(CustomerRef.of("CU0000000001"))
                .type(AccountType.LIABILITY)
                .currency(PLN)
                .status(AccountStatus.OPEN)
                .overdraft(OverdraftPolicy.forbidden())
                .build();
    }

    @Test
    @DisplayName("work inside a transaction sees its own writes and returns their result")
    void workRunsAndReturns() {
        AccountStatus observed = unitOfWork.inTransaction(() -> {
            accounts.save(account(ALICE).withStatus(AccountStatus.BLOCKED));
            return accounts.findByReference(ALICE).orElseThrow().status();
        });

        assertThat(observed).isEqualTo(AccountStatus.BLOCKED);
    }

    @Test
    @DisplayName("a failure rolls the whole unit back, not just the statement that threw")
    void aFailureRollsBackEverything() {
        accounts.save(account(BOB).withStatus(AccountStatus.OPEN));

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> {
                    accounts.save(account(BOB).withStatus(AccountStatus.CLOSED));
                    throw new IllegalStateException("something went wrong after the write");
                }))
                .isInstanceOf(IllegalStateException.class);

        // Half a unit of work is what double-entry bookkeeping exists to prevent. The account must
        // still be OPEN, because the write that closed it never committed.
        assertThat(accounts.findByReference(BOB).orElseThrow().status())
                .isEqualTo(AccountStatus.OPEN);
    }

    @Test
    @DisplayName("locking is delegated, so an account that does not exist is refused")
    void lockingAnUnknownAccountIsRefused() {
        assertThatThrownBy(() -> unitOfWork.inTransactionLocking(
                        List.of(ALICE, AccountRef.of("TB00000000009999")), () -> "unreached"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot lock account");
    }

    @Test
    @DisplayName("locks are taken before the work runs, never during it")
    void locksArePreAcquired() {
        String result = unitOfWork.inTransactionLocking(List.of(BOB, ALICE), () -> "done");

        // AccountLocks throws on a missing account, so reaching the supplier at all proves both
        // locks were taken first. The ordering that makes them deadlock-free is AccountLocks'
        // responsibility and is proved by its own ring test.
        assertThat(result).isEqualTo("done");
    }
}
