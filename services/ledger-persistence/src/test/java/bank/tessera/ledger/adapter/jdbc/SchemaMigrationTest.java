package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The schema, and the two things the Definition of Done asks of the migrations. */
class SchemaMigrationTest {

    private static final String SCHEMA = "migration_test";
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        dataSource = PostgresSupport.dataSource();
    }

    @Test
    @DisplayName("migrations apply cleanly to an empty database")
    void migrationsApplyToAnEmptyDatabase() {
        MigrateResult result = MigrationSupport.migrateFresh(dataSource, SCHEMA);

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isPositive();
        assertThat(tablesIn(SCHEMA))
                .contains("account", "journal_entry", "posting", "balance", "hold");
    }

    @Test
    @DisplayName("re-running the migrations is a no-op")
    void reRunningIsANoOp() {
        MigrationSupport.migrateFresh(dataSource, SCHEMA + "_rerun");
        MigrateResult second = MigrationSupport.migrate(dataSource, SCHEMA + "_rerun");

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted)
                .as("a second migrate must apply nothing - forward-only means idempotent on re-run")
                .isZero();
    }

    @Test
    @DisplayName("money is stored as minor units in bigint, never as numeric or a float")
    void moneyIsBigintMinorUnits() {
        MigrationSupport.migrateFresh(dataSource, SCHEMA + "_money");

        // Asserted on the JDBC type code, not the type name: PostgreSQL reports bigint as "int8" and
        // the two are the same type. A name comparison would fail on a correct schema and invite
        // someone to change the schema to satisfy the test.
        //
        // A numeric column would invite a BigDecimal into the row mapper, and the domain forbids one
        // outright. The canonical model says money is minor units plus an ISO 4217 code.
        assertThat(columnType(SCHEMA + "_money", "posting", "amount_minor")).isEqualTo(Types.BIGINT);
        assertThat(columnType(SCHEMA + "_money", "balance", "booked_minor")).isEqualTo(Types.BIGINT);
        assertThat(columnType(SCHEMA + "_money", "hold", "amount_minor")).isEqualTo(Types.BIGINT);
        assertThat(columnType(SCHEMA + "_money", "account", "overdraft_limit_minor"))
                .isEqualTo(Types.BIGINT);
    }

    @Test
    @DisplayName("a forbidden overdraft is a null limit, so the column must allow one")
    void theOverdraftLimitIsNullable() {
        MigrationSupport.migrateFresh(dataSource, SCHEMA + "_overdraft");

        // OverdraftPolicy.forbidden() against OverdraftPolicy.upTo(limit). A zero limit is a
        // different thing - an account permitted to reach exactly zero - and conflating them would
        // silently grant an overdraft of nothing to accounts that must never have one.
        assertThat(isNullable(SCHEMA + "_overdraft", "account", "overdraft_limit_minor")).isTrue();
    }

    private static List<String> tablesIn(String schema) {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, schema, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    names.add(tables.getString("TABLE_NAME"));
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return names;
    }

    private static int columnType(String schema, String table, String column) {
        return Integer.parseInt(column(schema, table, column, "DATA_TYPE"));
    }

    private static boolean isNullable(String schema, String table, String column) {
        return "YES".equals(column(schema, table, column, "IS_NULLABLE"));
    }

    private static String column(String schema, String table, String column, String field) {
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet columns =
                    connection.getMetaData().getColumns(null, schema, table, column)) {
                if (!columns.next()) {
                    throw new IllegalStateException(schema + "." + table + "." + column + " is absent");
                }
                return columns.getString(field);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
