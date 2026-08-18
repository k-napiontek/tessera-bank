package bank.tessera.ledger.adapter.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One real PostgreSQL container for the whole module.
 *
 * <p>Real PostgreSQL is not a convenience here, it is the requirement. This module's central claim is
 * that concurrent transfers cannot lose an update or deadlock, and that claim rests on {@code SELECT
 * ... FOR UPDATE} row locking. An in-memory database does not implement it, so the concurrency test
 * would pass against one while proving nothing at all - the worst outcome available.
 *
 * <p>The container is started once and shared. Testcontainers shuts it down with the JVM via its own
 * reuse of the Ryuk sidecar, so there is no {@code @AfterAll} to forget.
 */
final class PostgresSupport {

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tessera_ledger")
                    .withUsername("ledger")
                    .withPassword("ledger");

    private static DataSource dataSource;
    private static final Map<String, DataSource> BY_SCHEMA = new HashMap<>();

    private PostgresSupport() {}

    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = pool(null, 8);
        }
        return dataSource;
    }

    /**
     * A migrated schema of its own, and a pool scoped to it.
     *
     * <p>Each test class gets a schema rather than a container: a container per class would add half a
     * minute each, a schema costs milliseconds and still isolates the data. The pool sets the schema
     * so production SQL stays unqualified - an adapter that had to name its schema could not be
     * deployed anywhere but the test.
     */
    static synchronized DataSource migratedSchema(String schema) {
        return BY_SCHEMA.computeIfAbsent(schema, name -> {
            MigrationSupport.migrateFresh(dataSource(), name);
            return pool(name, 8);
        });
    }

    /** A pool of a stated size, for tests that need to contend for connections. */
    static DataSource pool(String schema, int maximumPoolSize) {
        CONTAINER.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(CONTAINER.getJdbcUrl());
        config.setUsername(CONTAINER.getUsername());
        config.setPassword(CONTAINER.getPassword());
        if (schema != null) {
            config.setSchema(schema);
        }
        // Deliberately small. The ring-transfer test must contend for connections the way a real
        // service does; a pool larger than the thread count would hide lock waits behind spare
        // connections.
        config.setMaximumPoolSize(maximumPoolSize);
        return new HikariDataSource(config);
    }

    static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }
}
