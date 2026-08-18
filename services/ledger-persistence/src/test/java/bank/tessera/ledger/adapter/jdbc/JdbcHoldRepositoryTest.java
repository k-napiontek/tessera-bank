package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.domain.HoldStatus;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.HoldRepository;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcHoldRepositoryTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef ACCOUNT = AccountRef.of("TB00000000000100");

    // Microsecond precision, deliberately. PostgreSQL timestamptz stores microseconds and Instant
    // carries nanoseconds, so an instant with nanos does not survive the round trip and an equality
    // assertion would fail on a correct adapter.
    private static final Instant PLACED_AT = Instant.parse("2026-08-18T03:00:00Z");

    private static HoldRepository repository;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("hold_adapter");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new JdbcHoldRepository(jdbc);

        new JdbcAccountRepository(jdbc)
                .save(Account.builder()
                        .reference(ACCOUNT)
                        .customer(CustomerRef.of("CU0000000001"))
                        .type(AccountType.LIABILITY)
                        .currency(PLN)
                        .status(AccountStatus.OPEN)
                        .overdraft(OverdraftPolicy.forbidden())
                        .build());
        jdbc.update(
                "INSERT INTO journal_entry (reference, value_date, currency)"
                        + " VALUES (:ref, DATE '2026-08-18', 'PLN')",
                Map.of("ref", "TB202608180000000001"));
    }

    @Test
    @DisplayName("a placed hold survives the round trip")
    void aPlacedHoldRoundTrips() {
        Hold hold = Hold.place(
                HoldRef.of("HL202608180000000001"), ACCOUNT, Money.of(75_00, PLN), PLACED_AT, null);
        repository.save(hold);

        Hold found = repository.findByReference(hold.reference()).orElseThrow();

        assertThat(found).isEqualTo(hold);
        assertThat(found.amount()).isEqualTo(Money.of(75_00, PLN));
        assertThat(found.status()).isEqualTo(HoldStatus.PLACED);
        assertThat(found.expiresAt()).isEmpty();
    }

    @Test
    @DisplayName("an expiry instant survives the round trip")
    void anExpiryRoundTrips() {
        Instant expires = Instant.parse("2026-08-25T03:00:00Z");
        Hold hold = Hold.place(
                HoldRef.of("HL202608180000000002"), ACCOUNT, Money.of(10_00, PLN), PLACED_AT, expires);
        repository.save(hold);

        assertThat(repository.findByReference(hold.reference()).orElseThrow().expiresAt())
                .contains(expires);
    }

    @Test
    @DisplayName("a captured hold rebuilds equal to the original, capturing entry included")
    void aCapturedHoldRoundTrips() {
        EntryRef entry = EntryRef.of("TB202608180000000001");
        Hold captured = Hold.place(
                        HoldRef.of("HL202608180000000003"), ACCOUNT, Money.of(20_00, PLN), PLACED_AT, null)
                .capture(entry, Instant.parse("2026-08-18T04:00:00Z"));
        repository.save(captured);

        Hold found = repository.findByReference(captured.reference()).orElseThrow();

        // The adapter rebuilds this by placing then capturing, because Hold offers no reconstruction
        // factory. Equality is the proof that the rebuild is faithful rather than approximate.
        assertThat(found).isEqualTo(captured);
        assertThat(found.status()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(found.capturedBy()).contains(entry);
    }

    @Test
    @DisplayName("a released hold rebuilds equal to the original")
    void aReleasedHoldRoundTrips() {
        Hold released = Hold.place(
                        HoldRef.of("HL202608180000000004"), ACCOUNT, Money.of(30_00, PLN), PLACED_AT, null)
                .release(Instant.parse("2026-08-18T05:00:00Z"));
        repository.save(released);

        assertThat(repository.findByReference(released.reference()).orElseThrow()).isEqualTo(released);
    }

    @Test
    @DisplayName("only holds that still reduce available balance are active")
    void findActiveForOmitsHoldsThatNoLongerReserveAnything() {
        Hold active = Hold.place(
                HoldRef.of("HL202608180000000005"), ACCOUNT, Money.of(40_00, PLN), PLACED_AT, null);
        Hold released = Hold.place(
                        HoldRef.of("HL202608180000000006"), ACCOUNT, Money.of(50_00, PLN), PLACED_AT, null)
                .release(Instant.parse("2026-08-18T06:00:00Z"));
        repository.save(active);
        repository.save(released);

        assertThat(repository.findActiveFor(ACCOUNT))
                .as("a released hold reserves nothing and must not reduce available balance")
                .contains(active)
                .doesNotContain(released);
    }

    @Test
    @DisplayName("an unknown hold reference is empty, never an exception")
    void anUnknownReferenceIsEmpty() {
        assertThat(repository.findByReference(HoldRef.of("HL999999999999999999"))).isEmpty();
    }

    @Test
    @DisplayName("saving a captured hold updates the placed row rather than duplicating it")
    void capturingUpdatesInPlace() {
        HoldRef reference = HoldRef.of("HL202608180000000007");
        Hold placed = Hold.place(reference, ACCOUNT, Money.of(60_00, PLN), PLACED_AT, null);
        repository.save(placed);
        repository.save(placed.capture(
                EntryRef.of("TB202608180000000001"), Instant.parse("2026-08-18T07:00:00Z")));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM hold WHERE reference = :ref",
                        Map.of("ref", reference.value()), Integer.class))
                .isEqualTo(1);
        assertThat(repository.findByReference(reference).orElseThrow().status())
                .isEqualTo(HoldStatus.CAPTURED);
    }
}
