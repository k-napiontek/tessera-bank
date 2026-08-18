package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.JournalEntryRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class BalanceReconciliationTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 18);

    private static BalanceReconciliation reconciliation;
    private static JournalEntryRepository entries;
    private static JdbcAccountRepository accounts;
    private static NamedParameterJdbcTemplate jdbc;
    private static int entrySequence;

    @BeforeAll
    static void generateALedger() {
        DataSource dataSource = PostgresSupport.migratedSchema("reconciliation");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        accounts = new JdbcAccountRepository(jdbc);
        entries = new JdbcJournalEntryRepository(jdbc, new JdbcHoldRepository(jdbc), Transactions.of(dataSource));

        // A mix of types, because the sign convention differs between them and a reconciliation that
        // only ever sees liabilities would agree with a SQL expression that has the rule backwards.
        accounts.save(account("TB00000000000801", AccountType.ASSET));
        accounts.save(account("TB00000000000802", AccountType.LIABILITY));
        accounts.save(account("TB00000000000803", AccountType.LIABILITY));
        accounts.save(account("TB00000000000804", AccountType.EXPENSE));
        accounts.save(account("TB00000000000805", AccountType.REVENUE));
        accounts.save(account("TB00000000000806", AccountType.EQUITY));

        List<String> references = List.of(
                "TB00000000000801", "TB00000000000802", "TB00000000000803",
                "TB00000000000804", "TB00000000000805", "TB00000000000806");

        for (int index = 0; index < 60; index++) {
            String debited = references.get(ThreadLocalRandom.current().nextInt(references.size()));
            String credited = references.get(ThreadLocalRandom.current().nextInt(references.size()));
            if (debited.equals(credited)) {
                continue;
            }
            entries.append(transfer(debited, credited, ThreadLocalRandom.current().nextLong(1_00, 900_00)));
        }
    }

    @Test
    @DisplayName("a generated ledger reconciles to zero drift")
    void aGeneratedLedgerHasNoDrift() {
        // Every account type appears, so this also asserts that the SQL sign convention agrees with
        // AccountType.signedEffect for all five - the two are separate implementations of one rule.
        assertThat(reconciliation().breaks()).isEmpty();
    }

    @Test
    @DisplayName("a corrupted balance row is detected, with both figures named")
    void aCorruptedBalanceIsDetected() {
        long before = bookedOf("TB00000000000802");
        jdbc.update(
                "UPDATE balance SET booked_minor = booked_minor + 1 WHERE account_ref = :ref",
                Map.of("ref", "TB00000000000802"));
        try {
            List<BalanceReconciliation.Drift> breaks = reconciliation().breaks();

            // A reconciliation that has never caught anything is not known to work. This is the test
            // that makes it a control rather than a claim.
            assertThat(breaks).hasSize(1);
            BalanceReconciliation.Drift drift = breaks.get(0);
            assertThat(drift.account()).isEqualTo(AccountRef.of("TB00000000000802"));
            assertThat(drift.materialised()).isEqualTo(Money.of(before + 1, PLN));
            assertThat(drift.summed()).isEqualTo(Money.of(before, PLN));
            assertThat(drift.difference()).isEqualTo(Money.of(1, PLN));
            assertThat(drift.toString()).contains("out by");
        } finally {
            jdbc.update(
                    "UPDATE balance SET booked_minor = :booked WHERE account_ref = :ref",
                    Map.of("booked", before, "ref", "TB00000000000802"));
        }
    }

    @Test
    @DisplayName("a single minor unit of drift is caught - the smallest error that can exist")
    void oneMinorUnitIsEnough() {
        long before = bookedOf("TB00000000000801");
        jdbc.update(
                "UPDATE balance SET booked_minor = booked_minor - 1 WHERE account_ref = :ref",
                Map.of("ref", "TB00000000000801"));
        try {
            assertThat(reconciliation().breaks())
                    .as("a tolerance would hide exactly the errors worth finding")
                    .hasSize(1);
        } finally {
            jdbc.update(
                    "UPDATE balance SET booked_minor = :booked WHERE account_ref = :ref",
                    Map.of("booked", before, "ref", "TB00000000000801"));
        }
    }

    @Test
    @DisplayName("an account with no postings reconciles at zero rather than reporting drift")
    void anUntouchedAccountIsNotADrift() {
        accounts.save(account("TB00000000000807", AccountType.LIABILITY));

        // LEFT JOIN, not JOIN: an inner join would drop accounts with no postings entirely, and a
        // corrupted balance on an untouched account is precisely the case nobody would notice.
        assertThat(reconciliation().breaks()).isEmpty();

        jdbc.update(
                "UPDATE balance SET booked_minor = 5 WHERE account_ref = :ref",
                Map.of("ref", "TB00000000000807"));
        assertThat(reconciliation().breaks()).hasSize(1);
        jdbc.update(
                "UPDATE balance SET booked_minor = 0 WHERE account_ref = :ref",
                Map.of("ref", "TB00000000000807"));
    }

    // ------------------------------------------------------------------------------------------

    private static BalanceReconciliation reconciliation() {
        if (reconciliation == null) {
            reconciliation = new BalanceReconciliation(jdbc);
        }
        return reconciliation;
    }

    private static long bookedOf(String reference) {
        return entries.balanceOf(AccountRef.of(reference)).booked().amountMinor();
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

    private static JournalEntry transfer(String debited, String credited, long amountMinor) {
        return JournalEntry.of(
                EntryRef.of(String.format("TB20260818%010d", ++entrySequence)),
                VALUE_DATE,
                List.of(
                        Posting.of(AccountRef.of(debited), Direction.DEBIT, Money.of(amountMinor, PLN)),
                        Posting.of(AccountRef.of(credited), Direction.CREDIT, Money.of(amountMinor, PLN))));
    }
}
