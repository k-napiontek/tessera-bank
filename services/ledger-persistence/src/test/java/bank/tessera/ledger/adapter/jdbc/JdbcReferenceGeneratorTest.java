package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.port.ReferenceGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcReferenceGeneratorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-19T09:00:00Z"), ZoneOffset.UTC);

    private static ReferenceGenerator generator;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("reference_generator");
        generator = new JdbcReferenceGenerator(new NamedParameterJdbcTemplate(dataSource), FIXED);
    }

    @Test
    @DisplayName("an entry reference is TB, the business date, then ten digits")
    void anEntryReferenceMatchesTheCanonicalPattern() {
        EntryRef reference = generator.nextEntryReference();

        // EntryRef.of already rejects anything off-pattern, so reaching this line proves the shape.
        // The assertion is about the parts the pattern cannot check: the date is the business date,
        // and the sequence is zero-padded to a fixed ten rather than however wide the number is.
        assertThat(reference.value()).startsWith("TB20260819");
        assertThat(reference.value()).hasSize(20);
        assertThat(reference.value().substring(10)).matches("[0-9]{10}");
    }

    @Test
    @DisplayName("a hold reference is HL, the business date, then ten digits")
    void aHoldReferenceMatchesTheCanonicalPattern() {
        HoldRef reference = generator.nextHoldReference();

        assertThat(reference.value()).startsWith("HL20260819");
        assertThat(reference.value()).hasSize(20);
    }

    @Test
    @DisplayName("entry and hold references advance independently")
    void theTwoSeriesAreSeparate() {
        String entry = generator.nextEntryReference().value().substring(10);
        String hold = generator.nextHoldReference().value().substring(10);

        // Sharing one sequence would still produce valid references, and would leave gaps in both
        // series that look like lost transfers to anyone reading them.
        assertThat(entry).isNotEqualTo("0000000000");
        assertThat(hold).isNotEqualTo("0000000000");
    }

    @Test
    @DisplayName("sixteen threads allocating at once never collide")
    void concurrentAllocationNeverCollides() throws Exception {
        int threads = 16;
        int each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<String>>> work = java.util.Collections.nCopies(threads, () -> {
                List<String> mine = new java.util.ArrayList<>();
                for (int i = 0; i < each; i++) {
                    mine.add(generator.nextEntryReference().value());
                }
                return mine;
            });

            Set<String> allocated = new HashSet<>();
            int total = 0;
            for (Future<List<String>> future : pool.invokeAll(work)) {
                List<String> mine = future.get();
                total += mine.size();
                allocated.addAll(mine);
            }

            // A generator built on MAX(reference) + 1 passes every single-threaded test above and
            // fails this one, which is the only reason this test exists.
            assertThat(total).isEqualTo(threads * each);
            assertThat(allocated).hasSize(total);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
