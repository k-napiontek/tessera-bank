package bank.tessera.ledger.adapter.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

    private PostgresSupport() {}

    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            CONTAINER.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(CONTAINER.getJdbcUrl());
            config.setUsername(CONTAINER.getUsername());
            config.setPassword(CONTAINER.getPassword());
            // Deliberately small. The ring-transfer test must contend for connections the way a real
            // service does; a pool larger than the thread count would hide lock waits behind spare
            // connections.
            config.setMaximumPoolSize(8);
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }
}
