package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The database's half of every invariant the domain holds.
 *
 * <p>Neither layer alone is load-bearing. The domain rejects an unbalanced entry and so does the
 * schema, because the domain can be bypassed by any script with a connection string, and a database
 * that trusts its callers has no invariants at all.
 *
 * <p>Every test here watches a constraint <strong>reject</strong> something. A constraint nobody has
 * seen fail is a constraint nobody knows is wired up - the same reason WP-04 demonstrated each of its
 * reject paths rather than only its happy path.
 */
class SchemaConstraintTest {

    private static final String SCHEMA = "constraint_test";
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = PostgresSupport.dataSource();
        MigrationSupport.migrateFresh(dataSource, SCHEMA);
    }

    @Test
    @DisplayName("a posting amount of zero is rejected - direction carries the sign")
    void aZeroAmountIsRejected() {
        givenAnAccount("TB-C-0001", "PLN");
        givenAnEntry("EN-C-0001", "PLN");

        assertThatThrownBy(() -> insertPosting("EN-C-0001", 1, "TB-C-0001", "DEBIT", 0, "PLN"))
                .hasMessageContaining("posting_amount_positive");
    }

    @Test
    @DisplayName("a negative posting amount is rejected")
    void aNegativeAmountIsRejected() {
        givenAnAccount("TB-C-0002", "PLN");
        givenAnEntry("EN-C-0002", "PLN");

        assertThatThrownBy(() -> insertPosting("EN-C-0002", 1, "TB-C-0002", "CREDIT", -1, "PLN"))
                .hasMessageContaining("posting_amount_positive");
    }

    @Test
    @DisplayName("a posting in a currency the entry is not in is rejected")
    void aPostingMustMatchItsEntrysCurrency() {
        givenAnAccount("TB-C-0003", "EUR");
        givenAnEntry("EN-C-0003", "PLN");

        // The account is EUR and the entry is PLN, so both composite keys are violated. Either one
        // catching it is the point: no conversion exists anywhere in this estate.
        assertThatThrownBy(() -> insertPosting("EN-C-0003", 1, "TB-C-0003", "DEBIT", 100, "EUR"))
                .hasMessageContaining("posting_entry_currency_fk");
    }

    @Test
    @DisplayName("a posting in a currency the account is not in is rejected")
    void aPostingMustMatchItsAccountsCurrency() {
        givenAnAccount("TB-C-0004", "EUR");
        givenAnEntry("EN-C-0004", "PLN");

        assertThatThrownBy(() -> insertPosting("EN-C-0004", 1, "TB-C-0004", "DEBIT", 100, "PLN"))
                .hasMessageContaining("posting_account_currency_fk");
    }

    @Test
    @DisplayName("a balanced entry commits")
    void aBalancedEntryCommits() throws SQLException {
        givenAnAccount("TB-C-0005", "PLN");
        givenAnAccount("TB-C-0006", "PLN");
        givenAnEntry("EN-C-0005", "PLN");

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(postingSql("EN-C-0005", 1, "TB-C-0005", "DEBIT", 5_00, "PLN"));
                statement.execute(postingSql("EN-C-0005", 2, "TB-C-0006", "CREDIT", 5_00, "PLN"));
            }
            connection.commit();
        }

        assertThat(count("SELECT count(*) FROM " + SCHEMA + ".posting WHERE entry_ref = 'EN-C-0005'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an unbalanced entry fails at commit, not before")
    void anUnbalancedEntryFailsAtCommit() throws SQLException {
        givenAnAccount("TB-C-0007", "PLN");
        givenAnAccount("TB-C-0008", "PLN");
        givenAnEntry("EN-C-0006", "PLN");

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // Both inserts succeed. A row-level check cannot see the other legs, which is exactly
                // why the constraint has to be deferred to commit time.
                statement.execute(postingSql("EN-C-0006", 1, "TB-C-0007", "DEBIT", 10_00, "PLN"));
                statement.execute(postingSql("EN-C-0006", 2, "TB-C-0008", "CREDIT", 9_99, "PLN"));
            }

            assertThatThrownBy(connection::commit)
                    .hasMessageContaining("does not balance")
                    .hasMessageContaining("EN-C-0006");
        }

        assertThat(count("SELECT count(*) FROM " + SCHEMA + ".posting WHERE entry_ref = 'EN-C-0006'"))
                .as("the failed commit must leave nothing behind")
                .isZero();
    }

    @Test
    @DisplayName("a single-legged entry is unbalanced and fails")
    void aSingleLeggedEntryFails() throws SQLException {
        givenAnAccount("TB-C-0009", "PLN");
        givenAnEntry("EN-C-0007", "PLN");

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(postingSql("EN-C-0007", 1, "TB-C-0009", "DEBIT", 1_00, "PLN"));
            }
            assertThatThrownBy(connection::commit).hasMessageContaining("does not balance");
        }
    }

    @Test
    @DisplayName("a posting cannot be updated")
    void aPostingCannotBeUpdated() {
        givenABalancedEntry("EN-C-0008", "TB-C-0010", "TB-C-0011", 7_00);

        assertThatThrownBy(() -> execute(
                        "UPDATE " + SCHEMA + ".posting SET amount_minor = 1 WHERE entry_ref = 'EN-C-0008'"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a posting cannot be deleted")
    void aPostingCannotBeDeleted() {
        givenABalancedEntry("EN-C-0009", "TB-C-0012", "TB-C-0013", 7_00);

        assertThatThrownBy(() ->
                        execute("DELETE FROM " + SCHEMA + ".posting WHERE entry_ref = 'EN-C-0009'"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a captured hold must name the entry that captured it")
    void aCapturedHoldNamesItsEntry() {
        givenAnAccount("TB-C-0014", "PLN");

        assertThatThrownBy(() -> execute("INSERT INTO " + SCHEMA + ".hold"
                        + " (reference, account_ref, amount_minor, currency, status, placed_at)"
                        + " VALUES ('HO-C-0001', 'TB-C-0014', 100, 'PLN', 'CAPTURED', now())"))
                .hasMessageContaining("hold_captured_by_consistent");
    }

    @Test
    @DisplayName("a placed hold must not name a capturing entry")
    void aPlacedHoldNamesNoEntry() {
        givenAnAccount("TB-C-0015", "PLN");
        givenAnEntry("EN-C-0010", "PLN");

        assertThatThrownBy(() -> execute("INSERT INTO " + SCHEMA + ".hold"
                        + " (reference, account_ref, amount_minor, currency, status, placed_at,"
                        + " captured_by)"
                        + " VALUES ('HO-C-0002', 'TB-C-0015', 100, 'PLN', 'PLACED', now(),"
                        + " 'EN-C-0010')"))
                .hasMessageContaining("hold_captured_by_consistent");
    }

    @Test
    @DisplayName("a hold amount of zero is rejected")
    void aZeroHoldIsRejected() {
        givenAnAccount("TB-C-0016", "PLN");

        assertThatThrownBy(() -> execute("INSERT INTO " + SCHEMA + ".hold"
                        + " (reference, account_ref, amount_minor, currency, status, placed_at)"
                        + " VALUES ('HO-C-0003', 'TB-C-0016', 0, 'PLN', 'PLACED', now())"))
                .hasMessageContaining("hold_amount_positive");
    }

    // ------------------------------------------------------------------------------------------

    private static Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    private static void givenAnAccount(String reference, String currency) {
        execute("INSERT INTO " + SCHEMA + ".account"
                + " (reference, customer_ref, account_type, currency, status)"
                + " VALUES ('" + reference + "', 'CU-0001', 'LIABILITY', '" + currency + "', 'OPEN')");
    }

    private static void givenAnEntry(String reference, String currency) {
        execute("INSERT INTO " + SCHEMA + ".journal_entry (reference, value_date, currency)"
                + " VALUES ('" + reference + "', DATE '2026-08-18', '" + currency + "')");
    }

    private static void givenABalancedEntry(
            String entry, String debited, String credited, long amountMinor) {
        givenAnAccount(debited, "PLN");
        givenAnAccount(credited, "PLN");
        givenAnEntry(entry, "PLN");
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(postingSql(entry, 1, debited, "DEBIT", amountMinor, "PLN"));
                statement.execute(postingSql(entry, 2, credited, "CREDIT", amountMinor, "PLN"));
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String postingSql(
            String entry, int seq, String account, String direction, long amountMinor, String currency) {
        return "INSERT INTO " + SCHEMA + ".posting"
                + " (entry_ref, seq, account_ref, direction, amount_minor, currency)"
                + " VALUES ('" + entry + "', " + seq + ", '" + account + "', '" + direction + "', "
                + amountMinor + ", '" + currency + "')";
    }

    private static void insertPosting(
            String entry, int seq, String account, String direction, long amountMinor, String currency) {
        execute(postingSql(entry, seq, account, direction, amountMinor, currency));
    }

    private static void execute(String sql) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static int count(String sql) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
