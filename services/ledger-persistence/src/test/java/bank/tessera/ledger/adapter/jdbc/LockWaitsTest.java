package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.AuditEntry;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.OverdraftPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two lock waits are two figures, and contention on one never shows up as the other.
 *
 * <p>This is the measurement F-27 has been asking for since WP-09, and it is only worth taking if
 * the two are apart. The audit chain's {@code pg_advisory_xact_lock} is service-wide - one writer at
 * a time across every account and every instance - while the {@code SELECT ... FOR UPDATE} row locks
 * contend only between transactions touching the same accounts. Averaged into one "lock wait" the
 * composite moves for two unrelated reasons, and an operator could not tell a busy corporate account
 * from a ceiling the whole service is queued behind.
 *
 * <p>So both directions are asserted here, with real contention rather than by construction: a
 * transaction is made to wait on one lock and the other timer must not move at all.
 */
class LockWaitsTest {

    private static final Duration HELD = Duration.ofMillis(300);

    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static JdbcAccountRepository accounts;
    private static AccountRef contended;

    /** Totals per kind, which is all a meter does. */
    private static final class Recorder implements LockWaits {
        private final Map<Kind, AtomicLong> nanos = new EnumMap<>(Kind.class);

        Recorder() {
            for (Kind kind : Kind.values()) {
                nanos.put(kind, new AtomicLong());
            }
        }

        @Override
        public void record(Kind kind, long elapsed) {
            nanos.get(kind).addAndGet(elapsed);
        }

        Duration waited(Kind kind) {
            return Duration.ofNanos(nanos.get(kind).get());
        }
    }

    private Recorder recorder;

    @BeforeAll
    static void seed() {
        DataSource dataSource = PostgresSupport.migratedSchema("lockwaits");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = Transactions.of(dataSource);
        accounts = new JdbcAccountRepository(jdbc);

        contended = AccountRef.of("TB00000000000801");
        accounts.save(Account.builder()
                .reference(contended)
                .customer(CustomerRef.of("CU0000000801"))
                .type(AccountType.LIABILITY)
                .currency(CurrencyCode.of("PLN"))
                .status(AccountStatus.OPEN)
                .overdraft(OverdraftPolicy.forbidden())
                .build());
    }

    @BeforeEach
    void freshRecorder() {
        recorder = new Recorder();
    }

    /**
     * Runs {@code contend} while another transaction holds a lock, and returns once both are done.
     *
     * <p>The holder takes its lock, signals, and sleeps to commit. Whatever {@code contend} does then
     * either queues behind it or does not, which is the whole question.
     */
    private void whileHolding(Runnable takeAndHold, Runnable contend) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> transactions.execute(status -> {
                takeAndHold.run();
                held.countDown();
                try {
                    Thread.sleep(HELD.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            if (!held.await(30, TimeUnit.SECONDS)) {
                // Surface why rather than reporting a latch that did not count down: a harness that
                // hides the cause turns a one-line failure into an afternoon.
                holder.get(5, TimeUnit.SECONDS);
                throw new AssertionError("the holder never took its lock and never failed either");
            }
            Future<?> waiter = threads.submit(() -> transactions.execute(status -> {
                contend.run();
                return null;
            }));

            holder.get(60, TimeUnit.SECONDS);
            waiter.get(60, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("waiting for an account's row lock moves the account timer and not the chain timer")
    void accountContentionIsNotReportedAsChainContention() throws Exception {
        AccountLocks measured = new AccountLocks(accounts, recorder);
        AccountLocks holder = new AccountLocks(accounts, LockWaits.UNMEASURED);

        whileHolding(
                () -> holder.lockInOrder(List.of(contended)),
                () -> measured.lockInOrder(List.of(contended)));

        assertThat(recorder.waited(LockWaits.Kind.ACCOUNT))
                .as("the second transaction queued behind the first for most of the hold")
                .isGreaterThan(HELD.dividedBy(2));
        assertThat(recorder.waited(LockWaits.Kind.CHAIN))
                .as("no audit row was written, so the chain lock was never taken")
                .isZero();
    }

    @Test
    @DisplayName("waiting for the audit chain moves the chain timer and not the account timer")
    void chainContentionIsNotReportedAsAccountContention() throws Exception {
        JdbcAuditLog measured = new JdbcAuditLog(jdbc, new ObjectMapper(), recorder);
        JdbcAuditLog holder = new JdbcAuditLog(jdbc, new ObjectMapper(), LockWaits.UNMEASURED);

        whileHolding(
                () -> holder.append(auditEntry("first")),
                () -> measured.append(auditEntry("second")));

        assertThat(recorder.waited(LockWaits.Kind.CHAIN))
                .as("the advisory lock is held to commit, so the second append waited for the first")
                .isGreaterThan(HELD.dividedBy(2));
        assertThat(recorder.waited(LockWaits.Kind.ACCOUNT))
                .as("appending to the chain takes no account lock")
                .isZero();
    }

    private static AuditEntry auditEntry(String label) {
        return AuditEntry.of(
                Instant.parse("2026-08-21T09:00:00Z"),
                "test",
                AuditAction.ACCOUNT_OPENED,
                contended.value(),
                // The column is a uuid, so a readable label here fails on insert rather than on
                // anything this test is about.
                UUID.randomUUID().toString(),
                Map.of(),
                Map.of("status", label));
    }
}
