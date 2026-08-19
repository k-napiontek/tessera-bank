package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.application.AuditTrail;
import bank.tessera.ledger.application.TransferEvents;
import bank.tessera.ledger.application.OpenAccount;
import bank.tessera.ledger.application.Transfer;
import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.port.AuditContext;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The transfer use case under concurrency, against real PostgreSQL.
 *
 * <p>WP-07 proved {@code AccountLocks.lockInOrder} conserves value when the adapter drives it
 * directly. This proves the same holds when the composition WP-08 built drives it: read the account,
 * consult the overdraft policy, append - all inside one transaction with both accounts already
 * locked. A use case that took the lock after reading the balance would pass every single-threaded
 * test in {@code TransferTest} and lose money here.
 *
 * <p>Testcontainers is not negotiable for this. An in-memory database does not implement
 * {@code SELECT ... FOR UPDATE} row locking, so the test would pass against one while proving
 * nothing at all.
 */
class TransferConcurrencyTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final int RING_SIZE = 5;
    private static final int THREADS = 6;
    private static final int TRANSFERS_EACH = 25;
    private static final long OPENING_BALANCE_MINOR = 1_000_00;
    private static final long TRANSFER_MINOR = 1_00;

    private static final List<AccountRef> RING = new ArrayList<>();
    private static final AccountRef FUNDING = AccountRef.of("TB0000000000FUND");

    private static Transfer transfer;
    private static JdbcJournalEntryRepository entries;
    private static final AtomicInteger FUNDING_REFERENCE = new AtomicInteger();

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("transfer_concurrency");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);

        JdbcAccountRepository accounts = new JdbcAccountRepository(jdbc);
        JdbcLedgerReadModel readModel = new JdbcLedgerReadModel(jdbc);
        entries = new JdbcJournalEntryRepository(
                jdbc, new JdbcHoldRepository(jdbc), Transactions.of(dataSource));
        JdbcUnitOfWork unitOfWork =
                new JdbcUnitOfWork(Transactions.of(dataSource), new AccountLocks(accounts));

        AuditTrail audit = auditTrail(jdbc);
        OpenAccount openAccount =
                new OpenAccount(accounts, readModel, unitOfWork, audit, Clock.systemUTC());
        transfer = new Transfer(
                accounts,
                entries,
                readModel,
                new JdbcReferenceGenerator(jdbc, Clock.systemUTC()),
                unitOfWork,
                audit,
                transferEvents(jdbc),
                Clock.systemUTC());

        openAccount.open(new OpenAccount.Command(
                FUNDING,
                CustomerRef.of("CU0000000000"),
                AccountType.ASSET,
                PLN,
                null,
                OverdraftPolicy.upTo(Money.of(Long.MAX_VALUE / 4, PLN))));

        for (int i = 1; i <= RING_SIZE; i++) {
            AccountRef reference = AccountRef.of(String.format("TB%014d", i));
            RING.add(reference);
            openAccount.open(new OpenAccount.Command(
                    reference,
                    CustomerRef.of("CU0000000001"),
                    AccountType.LIABILITY,
                    PLN,
                    null,
                    OverdraftPolicy.forbidden()));
            fund(reference);
        }
    }

    /** Seeds an account outside the use case, so the ring starts with something to move. */
    private static void fund(AccountRef account) {
        entries.append(JournalEntry.of(
                EntryRef.of(String.format("TB20260818%010d", FUNDING_REFERENCE.incrementAndGet())),
                LocalDate.of(2026, 8, 18),
                List.of(
                        Posting.of(FUNDING, Direction.DEBIT, Money.of(OPENING_BALANCE_MINOR, PLN)),
                        Posting.of(account, Direction.CREDIT, Money.of(OPENING_BALANCE_MINOR, PLN)))));
    }

    private static Money totalAcrossTheRing() {
        Money total = Money.zero(PLN);
        for (AccountRef account : RING) {
            total = total.plus(entries.balanceOf(account).booked());
        }
        return total;
    }

    @Test
    @DisplayName("money moved concurrently around a ring is conserved, with no deadlock")
    void theRingConservesValue() throws Exception {
        Money before = totalAcrossTheRing();
        assertThat(before).isEqualTo(Money.of(OPENING_BALANCE_MINOR * RING_SIZE, PLN));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                // Half the threads walk the ring forwards and half backwards, so opposite-direction
                // transfers over the same pair of accounts happen deliberately rather than by luck.
                // That pair is exactly what deadlocks without a deterministic lock order.
                boolean forwards = thread % 2 == 0;
                work.add(() -> {
                    int posted = 0;
                    for (int i = 0; i < TRANSFERS_EACH; i++) {
                        int from = i % RING_SIZE;
                        int to = forwards ? (from + 1) % RING_SIZE : (from + RING_SIZE - 1) % RING_SIZE;
                        transfer.execute(new Transfer.Command(
                                RING.get(from), RING.get(to), Money.of(TRANSFER_MINOR, PLN), null, null));
                        posted++;
                    }
                    return posted;
                });
            }

            int posted = 0;
            for (Future<Integer> future : pool.invokeAll(work)) {
                posted += future.get();
            }
            assertThat(posted).isEqualTo(THREADS * TRANSFERS_EACH);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        // Every transfer took from one ring account and gave to another, so the total is untouched.
        // A lost update shows up here as a figure that is simply wrong, with no exception anywhere.
        assertThat(totalAcrossTheRing()).isEqualTo(before);
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
