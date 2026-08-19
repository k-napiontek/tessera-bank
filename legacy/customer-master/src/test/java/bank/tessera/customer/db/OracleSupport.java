package bank.tessera.customer.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.oracle.OracleContainer;

/**
 * One Oracle for the whole module.
 *
 * <p>Real Oracle, not a compatibility mode. TD-005 accepts that Oracle itself is not distributable
 * and calls for a substitute; the substitute is Oracle Database 23ai Free in a container, because
 * the two things this stratum is here to reproduce - the dialect lock-in and the stored-procedure
 * layer - are exactly the two things a compatibility mode does not have. H2 in Oracle mode runs no
 * PL/SQL at all, so the procedures would have been Java pretending to be PL/SQL and the tests would
 * have proved the pretence.
 *
 * <p>The container starts in a static initialiser rather than through the JUnit rule, and Ryuk
 * removes it when the JVM exits. Starting Oracle costs tens of seconds, so one per module is the
 * difference between a suite that gets run and one that does not.
 */
public final class OracleSupport {

    private static final OracleContainer CONTAINER =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                    .withUsername("tessera")
                    .withPassword("tessera")
                    .withReuse(false);

    static {
        CONTAINER.start();
    }

    private OracleSupport() {
    }

    public static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    /**
     * A connection onto an empty schema with the versioned scripts applied.
     *
     * <p>Every object is dropped first. Oracle commits DDL implicitly, so a test class cannot lean
     * on a rollback to undo what the class before it did, and a suite whose outcome depends on its
     * running order is a suite that passes until somebody adds a test.
     */
    public static Connection freshSchema() throws SQLException {
        Connection connection = connection();
        dropEverything(connection);
        SchemaApplier.applyTo(connection);
        return connection;
    }

    private static void dropEverything(Connection connection) throws SQLException {
        drop(connection, "PACKAGE", objectsOfType(connection, "PACKAGE"));
        drop(connection, "TABLE", objectsOfType(connection, "TABLE"));
        drop(connection, "SEQUENCE", objectsOfType(connection, "SEQUENCE"));
    }

    private static List<String> objectsOfType(Connection connection, String type)
            throws SQLException {
        List<String> names = new ArrayList<String>();
        Statement statement = connection.createStatement();
        try {
            ResultSet rows = statement.executeQuery(
                    "SELECT object_name FROM user_objects WHERE object_type = '" + type + "'");
            try {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            } finally {
                rows.close();
            }
        } finally {
            statement.close();
        }
        return names;
    }

    private static void drop(Connection connection, String type, List<String> names)
            throws SQLException {
        for (int i = 0; i < names.size(); i++) {
            Statement statement = connection.createStatement();
            try {
                String cascade = "TABLE".equals(type) ? " CASCADE CONSTRAINTS PURGE" : "";
                statement.execute("DROP " + type + " \"" + names.get(i) + "\"" + cascade);
            } finally {
                statement.close();
            }
        }
    }
}
