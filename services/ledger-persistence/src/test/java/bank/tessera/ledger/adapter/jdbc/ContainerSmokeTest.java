package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The container harness itself, checked before anything is built on top of it.
 *
 * <p>Every other test in this module assumes a real database answered. When Docker is not running the
 * failure should land here, on a test that says what is wrong in one line, rather than twenty tests
 * down in a Flyway stack trace.
 */
class ContainerSmokeTest {

    @Test
    @DisplayName("a real PostgreSQL answers")
    void aRealPostgresAnswers() throws Exception {
        try (Connection connection = PostgresSupport.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("it is PostgreSQL, and a version that supports what this module relies on")
    void itIsAPostgresThatSupportsDeferredConstraintTriggers() throws Exception {
        try (Connection connection = PostgresSupport.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW server_version_num")) {
            assertThat(result.next()).isTrue();
            // Deferrable constraint triggers and SELECT ... FOR UPDATE are ancient, but the schema
            // also uses generated identity columns. 12 is the floor; the image pins 16.
            assertThat(result.getInt(1)).isGreaterThanOrEqualTo(120_000);
            assertThat(PostgresSupport.jdbcUrl()).startsWith("jdbc:postgresql://");
        }
    }
}
