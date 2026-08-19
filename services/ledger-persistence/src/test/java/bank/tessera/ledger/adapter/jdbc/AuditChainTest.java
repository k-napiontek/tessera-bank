package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit trail: append-only against the application, tamper-evident against everyone else.
 *
 * <p>The test that matters is {@link #tamperedRowIsNamed}. It has to defeat the append-only trigger
 * to do its job, and it does so by disabling the trigger - which is not cheating, it is the scenario.
 * The trigger stops the service; the chain is what catches somebody with rights the service does not
 * have. A test that could not get past the trigger would only be testing the trigger twice.
 */
class AuditChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2026-08-19T10:00:00Z");

    /**
     * A schema of its own per test, because half of these tests break the chain on purpose.
     *
     * <p>Sharing one schema would make every assertion depend on which test ran first: once
     * {@link #tamperedRowIsNamed} has done its work, {@code verify()} reports that break for every
     * test that follows. Schemas cost milliseconds; a suite whose result depends on JUnit's ordering
     * costs an afternoon.
     */
    private record Trail(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            JdbcAuditLog audit,
            AuditChain chain) {

        static Trail freshlyMigrated(String schema) {
            DataSource dataSource = PostgresSupport.migratedSchema(schema);
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
            return new Trail(
                    jdbc,
                    Transactions.of(dataSource),
                    new JdbcAuditLog(jdbc, JSON),
                    new AuditChain(jdbc, JSON));
        }

        /** The port joins the caller's transaction, so the advisory lock needs one to be held to. */
        void append(AuditEntry entry) {
            transactions.executeWithoutResult(status -> audit.append(entry));
        }

        long lastSeq() {
            Long seq = jdbc.queryForObject(
                    "SELECT seq FROM audit_record ORDER BY seq DESC LIMIT 1", Map.of(), Long.class);
            return seq == null ? 0L : seq;
        }

        /** Tampering, as somebody with rights the service does not have would do it. */
        void withoutTheTrigger(Runnable tamper) {
            jdbc.getJdbcTemplate()
                    .execute("ALTER TABLE audit_record DISABLE TRIGGER audit_record_no_change");
            try {
                tamper.run();
            } finally {
                jdbc.getJdbcTemplate()
                        .execute("ALTER TABLE audit_record ENABLE TRIGGER audit_record_no_change");
            }
        }
    }

    private static AuditEntry entry(String subject, String status) {
        return AuditEntry.of(
                AT,
                "ledger-api",
                AuditAction.ACCOUNT_OPENED,
                subject,
                UUID.randomUUID().toString(),
                Map.of(),
                Map.of("status", status));
    }

    @Test
    @DisplayName("the first row chains onto the genesis hash and the chain verifies")
    void theChainStartsAtGenesis() {
        Trail trail = Trail.freshlyMigrated("audit_genesis");
        trail.append(entry("TB00000000000201", "OPEN"));

        assertThat(trail.chain().verify()).isEmpty();
        assertThat(trail.jdbc()
                        .queryForObject(
                                "SELECT previous_hash FROM audit_record ORDER BY seq LIMIT 1",
                                Map.of(),
                                String.class))
                .isEqualTo(AuditEntry.GENESIS_HASH);
    }

    @Test
    @DisplayName("an untampered chain of many rows verifies")
    void aLongChainVerifies() {
        Trail trail = Trail.freshlyMigrated("audit_long");
        List.of("TB00000000000202", "TB00000000000203", "TB00000000000204")
                .forEach(subject -> trail.append(entry(subject, "OPEN")));

        assertThat(trail.chain().verify()).isEmpty();
        assertThat(trail.chain().length()).isEqualTo(3);
    }

    @Test
    @DisplayName("a tampered row is detected and named")
    void tamperedRowIsNamed() {
        Trail trail = Trail.freshlyMigrated("audit_tampered");
        trail.append(entry("TB00000000000205", "OPEN"));
        long seq = trail.lastSeq();
        trail.append(entry("TB00000000000206", "OPEN"));

        trail.withoutTheTrigger(() -> trail.jdbc()
                .update(
                        "UPDATE audit_record SET after_state = CAST('{\"status\": \"CLOSED\"}' AS jsonb)"
                                + " WHERE seq = :seq",
                        Map.of("seq", seq)));

        assertThat(trail.chain().verify())
                .isPresent()
                .get()
                .satisfies(broken -> {
                    assertThat(broken.seq()).isEqualTo(seq);
                    assertThat(broken.reason()).contains("altered after it was written");
                });
    }

    @Test
    @DisplayName("a deleted row is detected at its successor")
    void aDeletedRowBreaksTheChain() {
        Trail trail = Trail.freshlyMigrated("audit_deleted");
        trail.append(entry("TB00000000000207", "OPEN"));
        long removed = trail.lastSeq();
        trail.append(entry("TB00000000000208", "OPEN"));
        long successor = trail.lastSeq();

        trail.withoutTheTrigger(() -> trail.jdbc()
                .update("DELETE FROM audit_record WHERE seq = :seq", Map.of("seq", removed)));

        assertThat(trail.chain().verify())
                .isPresent()
                .get()
                .satisfies(broken -> {
                    assertThat(broken.seq()).isEqualTo(successor);
                    assertThat(broken.reason()).contains("a row was removed or inserted");
                });
    }

    @Test
    @DisplayName("the application cannot update or delete an audit row")
    void theTrailIsAppendOnlyToTheApplication() {
        Trail trail = Trail.freshlyMigrated("audit_append_only");
        trail.append(entry("TB00000000000209", "OPEN"));
        long seq = trail.lastSeq();

        assertThatThrownBy(() -> trail.jdbc()
                        .update(
                                "UPDATE audit_record SET actor = 'someone-else' WHERE seq = :seq",
                                Map.of("seq", seq)))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> trail.jdbc()
                        .update("DELETE FROM audit_record WHERE seq = :seq", Map.of("seq", seq)))
                .hasMessageContaining("append-only");
        // The hole a row-level trigger leaves. TRUNCATE fires no row trigger, so without a
        // statement-level one the whole trail goes in a single statement and the control that was
        // supposed to prevent it never runs.
        assertThatThrownBy(() -> trail.jdbc().getJdbcTemplate().execute("TRUNCATE TABLE audit_record"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("nanoseconds and an uppercase correlation id still verify")
    void whatIsHashedIsWhatIsStored() {
        // Both conversions the table performs. timestamptz keeps microseconds and a uuid column
        // returns lowercase, so an entry hashed before normalisation would verify against a row that
        // was never written - the chain would report tampering on a row nobody touched.
        Trail trail = Trail.freshlyMigrated("audit_normalised");
        AuditEntry awkward = AuditEntry.of(
                Instant.parse("2026-08-19T10:00:00.123456789Z"),
                "ledger-api",
                AuditAction.TRANSFER_POSTED,
                "TB00000000000210",
                UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT),
                Map.of("bookedBalance", "10000"),
                Map.of("bookedBalance", "9000"));

        trail.append(awkward);

        assertThat(trail.chain().verify()).isEmpty();
    }

    @Test
    @DisplayName("an append that starts while another is uncommitted waits for it")
    void theChainIsSerialisedByTheAdvisoryLock() throws Exception {
        // Deterministic on purpose. Eight threads racing is a test that passes with the lock removed
        // whenever the timing happens to be kind, and a concurrency test that passes on a broken
        // implementation is worse than none - it certifies the bug.
        //
        // So the interleaving is forced. A appends and holds its transaction open; B appends while
        // A is still uncommitted. With the lock, B waits and chains onto A. Without it, B reads the
        // predecessor A has already claimed, and audit_record_previous_unique refuses B's insert -
        // which in production is a customer's transfer failing because of an audit row.
        Trail trail = Trail.freshlyMigrated("audit_serialised");
        CountDownLatch aHasInserted = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> trail.transactions().executeWithoutResult(status -> {
            trail.audit().append(entry("TB00000000000301", "OPEN"));
            aHasInserted.countDown();
            sleep(500);
        }));
        pool.submit(() -> {
            try {
                assertThat(aHasInserted.await(30, TimeUnit.SECONDS)).isTrue();
                trail.append(entry("TB00000000000302", "OPEN"));
            } catch (RuntimeException | InterruptedException refused) {
                failures.incrementAndGet();
            }
        });
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).as("neither append may be refused").hasValue(0);
        assertThat(trail.chain().length()).isEqualTo(2);
        assertThat(trail.chain().verify()).isEmpty();
    }

    @Test
    @DisplayName("eight concurrent appends produce eight rows and one intact chain")
    void concurrentAppendsStillVerify() throws Exception {
        int threads = 8;
        Trail trail = Trail.freshlyMigrated("audit_concurrent");

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            String subject = String.format("TB%014d", 400 + i);
            pool.submit(() -> {
                try {
                    start.await();
                    trail.append(entry(subject, "OPEN"));
                } catch (RuntimeException | InterruptedException failure) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).hasValue(0);
        assertThat(trail.chain().length()).isEqualTo(threads);
        assertThat(trail.chain().verify()).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

}
