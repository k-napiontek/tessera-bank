package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.application.AuditTrail;
import bank.tessera.ledger.application.TransferEvents;
import bank.tessera.ledger.application.OpenAccount;
import bank.tessera.ledger.application.ReverseTransfer;
import bank.tessera.ledger.application.Transfer;
import bank.tessera.ledger.application.TransferView;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.port.AuditContext;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.LedgerReadModel;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Reversals, against real PostgreSQL.
 *
 * <p>{@code journal_entry.reverses} did not exist before WP-08, so a reversal round-tripped through
 * the database losing the link to the entry it corrected. These tests are what says it does not any
 * more, and that the database refuses a second reversal even if the application check is bypassed.
 */
class JdbcReversalTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef VAULT = AccountRef.of("TB00000000000009");
    /** Starts clear of the vault's own reference, so a per-test account can never collide with it. */
    private static final AtomicInteger NEXT_ACCOUNT = new AtomicInteger(100);

    private static NamedParameterJdbcTemplate jdbc;
    private static LedgerReadModel readModel;
    private static JdbcJournalEntryRepository entries;
    private static Transfer transfer;
    private static ReverseTransfer reverseTransfer;
    private static OpenAccount openAccount;

    private AccountRef alice;
    private AccountRef bob;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("reversal");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAccountRepository accounts = new JdbcAccountRepository(jdbc);
        readModel = new JdbcLedgerReadModel(jdbc);
        entries = new JdbcJournalEntryRepository(
                jdbc, new JdbcHoldRepository(jdbc), Transactions.of(dataSource));
        JdbcUnitOfWork unitOfWork =
                new JdbcUnitOfWork(Transactions.of(dataSource), new AccountLocks(accounts));
        JdbcReferenceGenerator references = new JdbcReferenceGenerator(jdbc, Clock.systemUTC());

        AuditTrail audit = auditTrail(jdbc);
        openAccount = new OpenAccount(accounts, readModel, unitOfWork, audit, Clock.systemUTC());
        TransferEvents events = transferEvents(jdbc);
        transfer = new Transfer(
                accounts, entries, readModel, references, unitOfWork, audit, events, Clock.systemUTC());
        reverseTransfer = new ReverseTransfer(
                accounts, entries, readModel, references, unitOfWork, audit, events, Clock.systemUTC());

        openAccount.open(new OpenAccount.Command(
                VAULT, CustomerRef.of("CU0000000000"), AccountType.ASSET, PLN, null,
                OverdraftPolicy.forbidden()));
    }

    @BeforeEach
    void openTwoAccounts() {
        alice = open();
        bob = open();
        // A deposit: the vault is an ASSET and rises on a debit, the customer account is a LIABILITY
        // and rises on a credit. Both go up, which is what paying money in actually does.
        transfer.execute(new Transfer.Command(VAULT, alice, Money.of(500_00, PLN), null, null));
    }

    private AccountRef open() {
        AccountRef reference = AccountRef.of(String.format("TB%014d", NEXT_ACCOUNT.incrementAndGet()));
        openAccount.open(new OpenAccount.Command(
                reference,
                CustomerRef.of("CU0000000001"),
                AccountType.LIABILITY,
                PLN,
                null,
                OverdraftPolicy.forbidden()));
        return reference;
    }

    @Test
    @DisplayName("a reversal reads back knowing which entry it reverses")
    void aReversalRoundTrips() {
        TransferView original =
                transfer.execute(new Transfer.Command(alice, bob, Money.of(40_00, PLN), null, null));

        TransferView reversal = reverseTransfer.execute(
                new ReverseTransfer.Command(original.transferReference(), "keyed in error", null));

        JournalEntry loaded =
                entries.findByReference(reversal.transferReference()).orElseThrow();

        assertThat(loaded.reverses()).contains(original.transferReference());
        assertThat(loaded.isReversal()).isTrue();
        // The stored postings are the original's opposite. The adapter rebuilds a reversal by
        // reversing its original and then checks the result against what is stored, so this
        // assertion also proves those two agree.
        assertThat(loaded.postings()).isEqualTo(original.entry().reverse(
                        reversal.transferReference(), loaded.valueDate())
                .postings());
    }

    @Test
    @DisplayName("the reversal restores the balance the original moved")
    void theBalanceComesBack() {
        Money before = entries.balanceOf(alice).booked();
        TransferView original =
                transfer.execute(new Transfer.Command(alice, bob, Money.of(40_00, PLN), null, null));
        assertThat(entries.balanceOf(alice).booked()).isEqualTo(before.minus(Money.of(40_00, PLN)));

        reverseTransfer.execute(
                new ReverseTransfer.Command(original.transferReference(), "keyed in error", null));

        assertThat(entries.balanceOf(alice).booked()).isEqualTo(before);
    }

    @Test
    @DisplayName("the original reports REVERSED and names the entry that reversed it")
    void theOriginalPointsForward() {
        TransferView original =
                transfer.execute(new Transfer.Command(alice, bob, Money.of(10_00, PLN), null, null));

        TransferView reversal = reverseTransfer.execute(
                new ReverseTransfer.Command(original.transferReference(), "duplicate", null));

        assertThat(readModel.reversedBy(original.transferReference()))
                .contains(reversal.transferReference());
    }

    @Test
    @DisplayName("the database refuses a second reversal even when the application check is bypassed")
    void theUniqueIndexIsTheRealControl() {
        TransferView original =
                transfer.execute(new Transfer.Command(alice, bob, Money.of(10_00, PLN), null, null));
        reverseTransfer.execute(
                new ReverseTransfer.Command(original.transferReference(), "duplicate", null));

        // Written straight to the table, the way two concurrent reversal requests would arrive after
        // both had passed the application's "has this been reversed?" read. Without
        // journal_entry_reverses_uq this INSERT succeeds and the account is credited twice for one
        // erroneous debit - and the second reversal balances perfectly, so nothing else reports it.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO journal_entry (reference, value_date, currency, reverses)
                        VALUES ('TB209901010000000001', DATE '2099-01-01', 'PLN', :original)
                        """,
                        Map.of("original", original.transferReference().value())))
                .hasMessageContaining("journal_entry_reverses_uq");
    }

    @Test
    @DisplayName("an entry cannot reverse itself")
    void nothingReversesItself() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO journal_entry (reference, value_date, currency, reverses)
                        VALUES ('TB209901010000000002', DATE '2099-01-01', 'PLN', 'TB209901010000000002')
                        """,
                        Map.of()))
                .hasMessageContaining("journal_entry_reverses_not_self");
    }

    /**
     * A real audit trail, against the same database.
     *
     * <p>A no-op double here would leave the advisory lock the chain serialises on untested on the
     * one path that contends for it. The audit append happens inside every transfer's transaction,
     * so it is part of what this test is measuring whether it is asserted on or not.
     */
    private static AuditTrail auditTrail(NamedParameterJdbcTemplate jdbc) {
        return new AuditTrail(
                new JdbcAuditLog(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()),
                testContext(),
                Clock.systemUTC());
    }

    /** No inbound request here, so no correlation id - which the audit row records as absent. */
    private static AuditContext testContext() {
        return new AuditContext() {
            @Override
            public String actor() {
                return "test";
            }

            @Override
            public java.util.Optional<String> correlationId() {
                return java.util.Optional.empty();
            }
        };
    }

    /** A real outbox, against the same database, so events are written where a relay would find them. */
    private static TransferEvents transferEvents(NamedParameterJdbcTemplate jdbc) {
        return new TransferEvents(
                new JdbcEventOutbox(jdbc, LedgerEventJson.mapper()), testContext());
    }
}
