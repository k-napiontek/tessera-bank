package bank.tessera.ledger.adapter.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the migrations into a schema of the caller's choosing.
 *
 * <p>Each test class gets its own schema rather than its own container. A container per class would
 * add half a minute per class; a schema per class costs milliseconds and still isolates the data, so
 * one test's postings cannot become another's mysterious balance.
 */
final class MigrationSupport {

    private MigrationSupport() {}

    static MigrateResult migrate(DataSource dataSource, String schema) {
        return flyway(dataSource, schema).migrate();
    }

    /** Drops and recreates the schema, then migrates. The clean-database case, every time. */
    static MigrateResult migrateFresh(DataSource dataSource, String schema) {
        execute(dataSource, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        execute(dataSource, "CREATE SCHEMA " + schema);
        return migrate(dataSource, schema);
    }

    static Flyway flyway(DataSource dataSource, String schema) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
    }

    static void execute(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("failed: " + sql, failure);
        }
    }
}
