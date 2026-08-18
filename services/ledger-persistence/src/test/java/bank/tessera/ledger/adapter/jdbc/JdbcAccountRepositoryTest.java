package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.AccountRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcAccountRepositoryTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static AccountRepository repository;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("account_adapter");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new JdbcAccountRepository(jdbc);
    }

    @Test
    @DisplayName("every field of an account survives the round trip")
    void everyFieldRoundTrips() {
        Account saved = repository.save(account("TB00000000000001", OverdraftPolicy.upTo(Money.of(500_00, PLN))));

        Account found = repository.findByReference(saved.reference()).orElseThrow();

        assertThat(found.reference()).isEqualTo(AccountRef.of("TB00000000000001"));
        assertThat(found.customer()).isEqualTo(CustomerRef.of("CU0000000001"));
        assertThat(found.type()).isEqualTo(AccountType.LIABILITY);
        assertThat(found.currency()).isEqualTo(PLN);
        assertThat(found.status()).isEqualTo(AccountStatus.OPEN);
        assertThat(found.overdraft().limitOr(PLN)).isEqualTo(Money.of(500_00, PLN));
        assertThat(found).isEqualTo(saved);
    }

    @Test
    @DisplayName("a forbidden overdraft comes back forbidden, not as a limit of zero")
    void aForbiddenOverdraftStaysForbidden() {
        repository.save(account("TB00000000000002", OverdraftPolicy.forbidden()));

        Account found = repository.findByReference(AccountRef.of("TB00000000000002")).orElseThrow();

        // A zero limit is a different policy - an account permitted to reach exactly zero. Storing
        // forbidden as 0 would silently grant an overdraft of nothing to accounts that must not have
        // one, and every balance check downstream would take the wrong branch.
        assertThat(found.overdraft().isForbidden()).isTrue();
        assertThat(found.overdraft()).isEqualTo(OverdraftPolicy.forbidden());
    }

    @Test
    @DisplayName("an overdraft limit of exactly zero is not forbidden")
    void aZeroLimitIsNotForbidden() {
        repository.save(account("TB00000000000003", OverdraftPolicy.upTo(Money.zero(PLN))));

        Account found = repository.findByReference(AccountRef.of("TB00000000000003")).orElseThrow();

        assertThat(found.overdraft().isForbidden()).isFalse();
    }

    @Test
    @DisplayName("an unknown reference is empty, never an exception")
    void anUnknownReferenceIsEmpty() {
        assertThat(repository.findByReference(AccountRef.of("TB00000000009999"))).isEmpty();
        assertThat(repository.findForUpdate(AccountRef.of("TB00000000009999"))).isEmpty();
    }

    @Test
    @DisplayName("findForUpdate returns the same account as a plain read")
    void findForUpdateReturnsTheSameAccount() {
        Account saved = repository.save(account("TB00000000000004", OverdraftPolicy.forbidden()));

        Optional<Account> locked = repository.findForUpdate(saved.reference());

        assertThat(locked).contains(saved);
    }

    @Test
    @DisplayName("saving an account opens its balance at zero")
    void savingAnAccountOpensItsBalance() {
        repository.save(account("TB00000000000005", OverdraftPolicy.forbidden()));

        Long booked = jdbc.queryForObject(
                "SELECT booked_minor FROM balance WHERE account_ref = :ref",
                java.util.Map.of("ref", "TB00000000000005"), Long.class);

        // An account with no balance row is a hole balanceOf would have to paper over, and a missing
        // row is indistinguishable from a genuine zero. The row is created with the account instead.
        assertThat(booked).isZero();
    }

    @Test
    @DisplayName("saving twice updates rather than duplicating")
    void savingTwiceUpdates() {
        Account first = repository.save(account("TB00000000000006", OverdraftPolicy.forbidden()));
        repository.save(Account.builder()
                .reference(first.reference())
                .customer(first.customer())
                .type(first.type())
                .currency(first.currency())
                .status(AccountStatus.BLOCKED)
                .overdraft(first.overdraft())
                .build());

        Account found = repository.findByReference(first.reference()).orElseThrow();

        assertThat(found.status()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM account WHERE reference = :ref",
                        java.util.Map.of("ref", "TB00000000000006"), Integer.class))
                .isEqualTo(1);
    }

    private static Account account(String reference, OverdraftPolicy overdraft) {
        return Account.builder()
                .reference(AccountRef.of(reference))
                .customer(CustomerRef.of("CU0000000001"))
                .type(AccountType.LIABILITY)
                .currency(PLN)
                .status(AccountStatus.OPEN)
                .overdraft(overdraft)
                .build();
    }
}
