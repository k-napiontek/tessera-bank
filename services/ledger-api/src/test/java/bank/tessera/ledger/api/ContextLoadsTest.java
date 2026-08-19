package bank.tessera.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The guard test.
 *
 * <p>A stopped Docker daemon, a broken bean graph or a migration that will not apply should fail on
 * one readable test rather than on thirty stack traces from tests that were about something else.
 * {@code ContainerSmokeTest} plays the same role in {@code ledger-persistence}.
 */
class ContextLoadsTest extends LedgerApiTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the application starts against real PostgreSQL")
    void theContextLoads() {
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("every migration has been applied, including the three WP-08 added")
    void theSchemaIsMigrated() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(5);

        // Named individually rather than counted, so a migration that was renamed or dropped fails
        // here instead of quietly changing what the API is running against.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'account' AND column_name = 'opened_date'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'journal_entry' AND column_name = 'reverses'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_name = 'idempotency_record'",
                        Integer.class))
                .isEqualTo(1);
    }
}
