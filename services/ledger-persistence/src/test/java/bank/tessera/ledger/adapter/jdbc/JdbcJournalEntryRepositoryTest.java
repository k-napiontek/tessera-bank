package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.JournalEntryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The money path.
 *
 * <p>Every expected figure is written out as a number. This harness never recomputes a balance by
 * re-implementing the sign convention in the test - that proves only that two copies of the rule agree,
 * and the whole risk here is that the copy in the adapter is wrong.
 */
class JdbcJournalEntryRepositoryTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 18);

    private static NamedParameterJdbcTemplate jdbc;
    private static JournalEntryRepository entries;
    private static HoldRepository holds;
    private static JdbcAccountRepository accounts;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("entry_adapter");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        accounts = new JdbcAccountRepository(jdbc);
        holds = new JdbcHoldRepository(jdbc);
        entries = new JdbcJournalEntryRepository(jdbc, holds, Transactions.of(dataSource));
    }

    @Test
    @DisplayName("an appended entry reads back with its postings in order")
    void anEntryRoundTrips() {
        givenAccounts("TB00000000000201", "TB00000000000202");
        JournalEntry entry = transfer("TB202608180000000101", "TB00000000000201", "TB00000000000202", 100_00);

        entries.append(entry);
        JournalEntry found = entries.findByReference(entry.reference()).orElseThrow();

        assertThat(found).isEqualTo(entry);
        assertThat(found.postings()).hasSize(2);
        assertThat(found.postings().get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(found.postings().get(1).direction()).isEqualTo(Direction.CREDIT);
        assertThat(found.valueDate()).isEqualTo(VALUE_DATE);
    }

    @Test
    @DisplayName("a liability account falls on the debit and rises on the credit")
    void aLiabilityMovesAgainstTheDebit() {
        givenAccounts("TB00000000000203", "TB00000000000204");
        entries.append(transfer("TB202608180000000102", "TB00000000000203", "TB00000000000204", 100_00));

        // Both are LIABILITY, whose normal balance is CREDIT. Debiting one takes it to -100.00 and
        // crediting the other to +100.00. Getting this backwards is the error that looks correct until
        // a balance sheet is drawn.
        assertThat(booked("TB00000000000203")).isEqualTo(Money.of(-100_00, PLN));
        assertThat(booked("TB00000000000204")).isEqualTo(Money.of(100_00, PLN));
    }

    @Test
    @DisplayName("an asset account rises on the debit - the opposite of a liability")
    void anAssetMovesWithTheDebit() {
        accounts.save(account("TB00000000000205", AccountType.ASSET));
        accounts.save(account("TB00000000000206", AccountType.LIABILITY));
        entries.append(transfer("TB202608180000000103", "TB00000000000205", "TB00000000000206", 250_00));

        assertThat(booked("TB00000000000205")).isEqualTo(Money.of(250_00, PLN));
        assertThat(booked("TB00000000000206")).isEqualTo(Money.of(250_00, PLN));
    }

    @Test
    @DisplayName("several entries accumulate on the same account")
    void severalEntriesAccumulate() {
        givenAccounts("TB00000000000207", "TB00000000000208");
        entries.append(transfer("TB202608180000000104", "TB00000000000207", "TB00000000000208", 30_00));
        entries.append(transfer("TB202608180000000105", "TB00000000000208", "TB00000000000207", 10_00));

        // 207: debited 30.00 then credited 10.00, so -30.00 + 10.00 = -20.00 on a LIABILITY.
        assertThat(booked("TB00000000000207")).isEqualTo(Money.of(-20_00, PLN));
        assertThat(booked("TB00000000000208")).isEqualTo(Money.of(20_00, PLN));
    }

    @Test
    @DisplayName("available balance drops by an active hold and ignores a released one")
    void availableBalanceRespectsHolds() {
        givenAccounts("TB00000000000209", "TB00000000000210");
        entries.append(transfer("TB202608180000000106", "TB00000000000209", "TB00000000000210", 500_00));

        holds.save(Hold.place(
                HoldRef.of("HL202608180000000101"),
                AccountRef.of("TB00000000000210"),
                Money.of(120_00, PLN),
                Instant.parse("2026-08-18T03:00:00Z"),
                null));
        holds.save(Hold.place(
                        HoldRef.of("HL202608180000000102"),
                        AccountRef.of("TB00000000000210"),
                        Money.of(80_00, PLN),
                        Instant.parse("2026-08-18T03:00:00Z"),
                        null)
                .release(Instant.parse("2026-08-18T04:00:00Z")));

        Balance balance = entries.balanceOf(AccountRef.of("TB00000000000210"));

        // Booked 500.00, one active hold of 120.00, one released hold of 80.00 that reserves nothing.
        assertThat(balance.booked()).isEqualTo(Money.of(500_00, PLN));
        assertThat(balance.available()).isEqualTo(Money.of(380_00, PLN));
    }

    @Test
    @DisplayName("an account with no postings has a zero balance, not a missing one")
    void anUntouchedAccountIsZero() {
        accounts.save(account("TB00000000000211", AccountType.LIABILITY));

        Balance balance = entries.balanceOf(AccountRef.of("TB00000000000211"));

        assertThat(balance.booked()).isEqualTo(Money.zero(PLN));
        assertThat(balance.available()).isEqualTo(Money.zero(PLN));
    }

    @Test
    @DisplayName("the value-date range is inclusive at both ends")
    void theDateRangeIsInclusive() {
        givenAccounts("TB00000000000212", "TB00000000000213");
        appendOn(LocalDate.of(2026, 8, 17), "TB202608180000000107", "TB00000000000212", "TB00000000000213");
        appendOn(LocalDate.of(2026, 8, 18), "TB202608180000000108", "TB00000000000212", "TB00000000000213");
        appendOn(LocalDate.of(2026, 8, 19), "TB202608180000000109", "TB00000000000212", "TB00000000000213");

        List<JournalEntry> found = entries.findByAccount(
                AccountRef.of("TB00000000000212"), LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 19));

        assertThat(found).hasSize(3);
        assertThat(entries.findByAccount(
                        AccountRef.of("TB00000000000212"),
                        LocalDate.of(2026, 8, 18),
                        LocalDate.of(2026, 8, 18)))
                .hasSize(1);
    }

    @Test
    @DisplayName("entries come back oldest first")
    void entriesComeBackOldestFirst() {
        givenAccounts("TB00000000000214", "TB00000000000215");
        appendOn(LocalDate.of(2026, 8, 19), "TB202608180000000110", "TB00000000000214", "TB00000000000215");
        appendOn(LocalDate.of(2026, 8, 17), "TB202608180000000111", "TB00000000000214", "TB00000000000215");

        List<JournalEntry> found = entries.findByAccount(
                AccountRef.of("TB00000000000214"), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(found.get(0).valueDate()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(found.get(1).valueDate()).isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    @DisplayName("an entry for another account does not appear")
    void otherAccountsEntriesDoNotAppear() {
        givenAccounts("TB00000000000216", "TB00000000000217");
        givenAccounts("TB00000000000218", "TB00000000000219");
        appendOn(LocalDate.of(2026, 8, 18), "TB202608180000000112", "TB00000000000216", "TB00000000000217");
        appendOn(LocalDate.of(2026, 8, 18), "TB202608180000000113", "TB00000000000218", "TB00000000000219");

        assertThat(entries.findByAccount(
                        AccountRef.of("TB00000000000216"),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31)))
                .hasSize(1);
    }

    @Test
    @DisplayName("an unknown entry reference is empty")
    void anUnknownEntryIsEmpty() {
        assertThat(entries.findByReference(EntryRef.of("TB999999999999999999"))).isEmpty();
    }

    @Test
    @DisplayName("appending the same entry twice is refused, and the balance moves once")
    void appendingTwiceIsRefused() {
        givenAccounts("TB00000000000220", "TB00000000000221");
        JournalEntry entry = transfer("TB202608180000000114", "TB00000000000220", "TB00000000000221", 60_00);
        entries.append(entry);

        assertThatThrownBy(() -> entries.append(entry)).isInstanceOf(RuntimeException.class);

        // The append is one transaction: a rejected second attempt must leave the balance where the
        // first put it, not at 120.00.
        assertThat(booked("TB00000000000221")).isEqualTo(Money.of(60_00, PLN));
    }

    @Test
    @DisplayName("a missing balance row fails the append instead of silently losing the movement")
    void aMissingBalanceRowFailsTheAppend() {
        givenAccounts("TB00000000000222", "TB00000000000223");
        jdbc.update(
                "DELETE FROM balance WHERE account_ref = :ref",
                java.util.Map.of("ref", "TB00000000000223"));

        JournalEntry entry = transfer("TB202608180000000115", "TB00000000000222", "TB00000000000223", 90_00);

        // An account is opened with its balance row, so a missing one means something else deleted it.
        // The alternative to failing here is an UPDATE that matches nothing, an append that reports
        // success, and a ledger whose postings and balances silently disagree from then on - the exact
        // drift BalanceReconciliation exists to find, introduced by the code meant to prevent it.
        assertThatThrownBy(() -> entries.append(entry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No balance row")
                .hasMessageContaining("TB00000000000223");

        assertThat(entries.findByReference(entry.reference()))
                .as("the failed append must roll back the entry too")
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------

    private static void givenAccounts(String... references) {
        for (String reference : references) {
            accounts.save(account(reference, AccountType.LIABILITY));
        }
    }

    private static Account account(String reference, AccountType type) {
        return Account.builder()
                .reference(AccountRef.of(reference))
                .customer(CustomerRef.of("CU0000000001"))
                .type(type)
                .currency(PLN)
                .status(AccountStatus.OPEN)
                .overdraft(OverdraftPolicy.forbidden())
                .build();
    }

    private static JournalEntry transfer(String entry, String debited, String credited, long amountMinor) {
        return JournalEntry.of(
                EntryRef.of(entry),
                VALUE_DATE,
                List.of(
                        Posting.of(AccountRef.of(debited), Direction.DEBIT, Money.of(amountMinor, PLN)),
                        Posting.of(AccountRef.of(credited), Direction.CREDIT, Money.of(amountMinor, PLN))));
    }

    private static void appendOn(LocalDate valueDate, String entry, String debited, String credited) {
        entries.append(JournalEntry.of(
                EntryRef.of(entry),
                valueDate,
                List.of(
                        Posting.of(AccountRef.of(debited), Direction.DEBIT, Money.of(1_00, PLN)),
                        Posting.of(AccountRef.of(credited), Direction.CREDIT, Money.of(1_00, PLN)))));
    }

    private static Money booked(String reference) {
        return entries.balanceOf(AccountRef.of(reference)).booked();
    }
}
