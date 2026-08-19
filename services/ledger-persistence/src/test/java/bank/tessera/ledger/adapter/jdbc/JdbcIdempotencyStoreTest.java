package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.port.IdempotencyConflictException;
import bank.tessera.ledger.port.IdempotencyStore;
import bank.tessera.ledger.port.StoredResponse;
import bank.tessera.ledger.port.UnitOfWork;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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

/** The idempotency store, against real PostgreSQL. */
class JdbcIdempotencyStoreTest {

    private static final String FINGERPRINT_A =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String FINGERPRINT_B =
            "0000000000000000000000000000000000000000000000000000000000000002";

    private static IdempotencyStore store;
    private static UnitOfWork unitOfWork;
    private static final AtomicInteger NEXT_KEY = new AtomicInteger();

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("idempotency");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        store = new JdbcIdempotencyStore(jdbc);
        unitOfWork = new JdbcUnitOfWork(
                Transactions.of(dataSource), new AccountLocks(new JdbcAccountRepository(jdbc)));
    }

    /** A key of its own per test, so none can observe another's claim. */
    private static String freshKey() {
        return String.format("idempotency-key-%016d", NEXT_KEY.incrementAndGet());
    }

    @Test
    @DisplayName("a key claimed for the first time reports no previous response")
    void aNewKeyIsClaimed() {
        String key = freshKey();

        Optional<StoredResponse> replay =
                unitOfWork.inTransaction(() -> store.claim(key, FINGERPRINT_A));

        assertThat(replay).isEmpty();
    }

    @Test
    @DisplayName("a replay with the same fingerprint returns the stored response byte for byte")
    void aReplayReturnsTheOriginal() {
        String key = freshKey();
        String body = "{\"transferRef\":\"TB202608190000000042\",\"status\":\"POSTED\"}";

        unitOfWork.inTransaction(() -> {
            store.claim(key, FINGERPRINT_A);
            store.store(key, StoredResponse.of(201, body));
            return null;
        });

        StoredResponse replay = unitOfWork
                .inTransaction(() -> store.claim(key, FINGERPRINT_A))
                .orElseThrow();

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("the same key with a different fingerprint is a conflict, not a second attempt")
    void adifferentFingerprintConflicts() {
        String key = freshKey();

        unitOfWork.inTransaction(() -> {
            store.claim(key, FINGERPRINT_A);
            store.store(key, StoredResponse.of(201, "{}"));
            return null;
        });

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> store.claim(key, FINGERPRINT_B)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("the conflict message carries neither the key nor the fingerprint")
    void theConflictLeaksNothing() {
        String key = freshKey();
        unitOfWork.inTransaction(() -> {
            store.claim(key, FINGERPRINT_A);
            store.store(key, StoredResponse.of(201, "{}"));
            return null;
        });

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> store.claim(key, FINGERPRINT_B)))
                .hasMessageNotContaining(key)
                .hasMessageNotContaining(FINGERPRINT_A)
                .hasMessageNotContaining(FINGERPRINT_B);
    }

    @Test
    @DisplayName("storing against a key that was never claimed is refused")
    void storingWithoutAClaimIsRefused() {
        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> {
                    store.store(freshKey(), StoredResponse.of(201, "{}"));
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never claimed");
    }

    @Test
    @DisplayName("a rolled-back claim leaves the key free")
    void aRolledBackClaimReleasesTheKey() {
        String key = freshKey();

        try {
            unitOfWork.inTransaction(() -> {
                store.claim(key, FINGERPRINT_A);
                throw new IllegalStateException("the work failed after the claim");
            });
        } catch (IllegalStateException expected) {
            // A claim is only meaningful if the work it guards committed. Holding the key after a
            // rollback would make a failed transfer unretryable for the client that owns the key.
        }

        assertThat(unitOfWork.inTransaction(() -> store.claim(key, FINGERPRINT_A))).isEmpty();
    }

    @Test
    @DisplayName("eight threads retrying the same key produce one execution and one identical answer")
    void concurrentRetriesExecuteOnce() throws Exception {
        String key = freshKey();
        int threads = 8;
        AtomicInteger executions = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Callable<StoredResponse>> work = java.util.Collections.nCopies(threads, () -> {
                startTogether.await(30, TimeUnit.SECONDS);
                return unitOfWork.inTransaction(() -> {
                    Optional<StoredResponse> replay = store.claim(key, FINGERPRINT_A);
                    if (replay.isPresent()) {
                        return replay.get();
                    }
                    // Stand in for the transfer. The window between claiming and storing is exactly
                    // where a read-then-write check lets a second retry through.
                    int execution = executions.incrementAndGet();
                    StoredResponse response =
                            StoredResponse.of(201, "{\"execution\":" + execution + "}");
                    store.store(key, response);
                    return response;
                });
            });

            List<StoredResponse> answers = new java.util.ArrayList<>();
            for (Future<StoredResponse> future : pool.invokeAll(work)) {
                answers.add(future.get());
            }

            // The money moved once, and every retry was told the same thing about it. Replacing the
            // upsert with SELECT-then-INSERT passes every other test in this class and fails here.
            assertThat(executions.get()).isEqualTo(1);
            assertThat(answers).hasSize(threads);
            assertThat(answers).containsOnly(StoredResponse.of(201, "{\"execution\":1}"));
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
    }
}
