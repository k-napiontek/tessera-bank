package bank.tessera.ledger.api.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * What the ledger can say about the database that does its work.
 *
 * <p>Until WP-23 this tier reported how many transfers posted and nothing at all about the
 * PostgreSQL that posted them, while every transfer takes two row locks and one global advisory
 * lock. Two open follow-ups - F-27 on the audit chain's throughput ceiling and F-28 on tables
 * nothing prunes - are questions only a database signal can answer, and neither could be answered
 * from anything this service emitted.
 *
 * <p><strong>These are the ledger's to emit, not a platform's to collect.</strong> Pool utilisation,
 * table growth and vacuum activity are things this process can observe about itself, which is the
 * same argument that put {@code ledger.outbox.lag} here rather than in a platform repository -
 * ADR 0012. What is deliberately absent is {@code pg_stat_statements}: it is exactly the signal a
 * query-cost objective would want, and switching it on needs {@code shared_preload_libraries},
 * which is server configuration this repository does not hold.
 *
 * <p><strong>Pool utilisation and acquire wait are not here either, and that is not an omission.</strong>
 * Boot's Hikari binder already publishes {@code hikaricp.connections.*}, including the acquire
 * timer. Re-deriving them would be a second copy of somebody else's instrument, and a wrong copy
 * would agree with itself. The catalogue records them as framework-emitted and
 * {@code DatabaseSignalsTest} asserts they are genuinely in a scrape.
 *
 * <h2>Two things this class gets right on purpose</h2>
 *
 * <p><strong>The table list is fixed.</strong> Reading {@code pg_stat_user_tables} as it comes would
 * grow a time series every time a migration adds a table, and unbounded label cardinality is the
 * standard way to take a monitoring system down - discovered, always, when the monitoring is needed
 * most. {@link #TABLES} is the list, and a test fails when a migration adds a table without adding
 * its signals.
 *
 * <p><strong>A table that has never been autovacuumed reports {@code NaN}, not zero.</strong>
 * {@code last_autovacuum} is null until the first one runs, and an age of zero reads as "vacuumed a
 * moment ago" - the exact opposite of the truth, in the right units, on a graph nobody would
 * question. That is the shape of defect this repository keeps a trap list about.
 */
public final class DatabaseSignals {

    /**
     * Every table the ledger owns, in the order a migration created them.
     *
     * <p>Fixed rather than discovered, so that the number of time series is a property of this file
     * and not of whatever happens to be in the schema.
     */
    public static final List<String> TABLES = List.of(
            "account",
            "journal_entry",
            "posting",
            "balance",
            "hold",
            "idempotency_record",
            "audit_record",
            "outbox_record");

    /** How many gauges each table carries. Asserted by the cardinality test. */
    public static final int MEASURES = 6;

    private static final String QUERY =
            """
            SELECT relname,
                   pg_table_size(relid)                                AS table_bytes,
                   pg_indexes_size(relid)                              AS index_bytes,
                   n_dead_tup                                          AS dead_tuples,
                   n_live_tup                                          AS live_tuples,
                   autovacuum_count                                    AS autovacuums,
                   EXTRACT(EPOCH FROM (now() - last_autovacuum))       AS autovacuum_age
              FROM pg_stat_user_tables
             WHERE relname IN (:tables)
            """;

    /** One table's figures. A null age means never, and is carried as null rather than as zero. */
    private record Stats(
            long tableBytes,
            long indexBytes,
            long deadTuples,
            long liveTuples,
            long autovacuums,
            Double autovacuumAgeSeconds) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final long ttlNanos;

    private volatile Map<String, Stats> snapshot = Map.of();
    private final AtomicLong refreshedAt = new AtomicLong(Long.MIN_VALUE);

    private DatabaseSignals(NamedParameterJdbcTemplate jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.ttlNanos = ttl.toNanos();
    }

    /**
     * Registers one gauge per table per measure.
     *
     * <p>A scrape reads all {@value #MEASURES} times {@link #TABLES}{@code .size()} of them, and the
     * cache below turns that into one query rather than forty-eight: a signal that costs a
     * round-trip per series is a signal that makes the thing it measures worse.
     */
    public static DatabaseSignals register(
            NamedParameterJdbcTemplate jdbc, MeterRegistry registry, Duration ttl) {

        DatabaseSignals signals = new DatabaseSignals(jdbc, ttl);
        for (String table : TABLES) {
            signals.gauge(registry, table, "ledger.db.table.size", "bytes",
                    "Heap size of the table, excluding its indexes", stats -> stats.tableBytes);
            signals.gauge(registry, table, "ledger.db.index.size", "bytes",
                    "Size of every index on the table", stats -> stats.indexBytes);
            signals.gauge(registry, table, "ledger.db.dead.tuples", null,
                    "Rows superseded or deleted and not yet reclaimed", stats -> stats.deadTuples);
            signals.gauge(registry, table, "ledger.db.live.tuples", null,
                    "Rows PostgreSQL estimates are live", stats -> stats.liveTuples);
            signals.gauge(registry, table, "ledger.db.autovacuums", null,
                    "Autovacuums run against the table since the statistics were last reset",
                    stats -> stats.autovacuums);
            signals.gauge(registry, table, "ledger.db.autovacuum.age", "seconds",
                    "Age of the last autovacuum. NaN means the table has never been autovacuumed",
                    stats -> stats.autovacuumAgeSeconds == null
                            ? Double.NaN
                            : stats.autovacuumAgeSeconds);
        }
        return signals;
    }

    private void gauge(
            MeterRegistry registry,
            String table,
            String name,
            String baseUnit,
            String description,
            ToDoubleFunction<Stats> measure) {

        Gauge.Builder<DatabaseSignals> builder = Gauge.builder(
                        name, this, self -> self.read(table, measure))
                .description(description)
                .tag("table", table);
        if (baseUnit != null) {
            builder = builder.baseUnit(baseUnit);
        }
        builder.register(registry);
    }

    private double read(String table, ToDoubleFunction<Stats> measure) {
        Stats stats = current().get(table);
        // A table PostgreSQL has no statistics for yet is unknown rather than empty. Reporting zero
        // would be a figure, and a figure is what a reader would believe.
        return stats == null ? Double.NaN : measure.applyAsDouble(stats);
    }

    private Map<String, Stats> current() {
        long now = System.nanoTime();
        long last = refreshedAt.get();
        if (now - last < ttlNanos && !snapshot.isEmpty()) {
            return snapshot;
        }
        // Whoever wins the compare-and-set does the query; everybody else reads what is already
        // there. A scrape must never queue behind another scrape's round-trip.
        if (!refreshedAt.compareAndSet(last, now)) {
            return snapshot;
        }
        snapshot = query();
        return snapshot;
    }

    private Map<String, Stats> query() {
        return jdbc.query(QUERY, Map.of("tables", TABLES), resultSet -> {
            Map<String, Stats> byTable = new java.util.HashMap<>();
            while (resultSet.next()) {
                // wasNull() reports on the column read *immediately* before it, so the age and its
                // null test have to be adjacent. Reading the other five columns in between made
                // wasNull() answer for autovacuum_count instead, which is never null - so every
                // never-vacuumed table reported an age of zero: "vacuumed a moment ago", in the
                // right units, on a graph nobody would question. Caught by the test below.
                double age = resultSet.getDouble("autovacuum_age");
                Double autovacuumAge = resultSet.wasNull() ? null : age;
                byTable.put(
                        resultSet.getString("relname"),
                        new Stats(
                                resultSet.getLong("table_bytes"),
                                resultSet.getLong("index_bytes"),
                                resultSet.getLong("dead_tuples"),
                                resultSet.getLong("live_tuples"),
                                resultSet.getLong("autovacuums"),
                                autovacuumAge));
            }
            return Map.copyOf(byTable);
        });
    }
}
