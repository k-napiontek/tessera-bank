package bank.tessera.customer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The schema, against real Oracle.
 *
 * <p>These assertions are about what the DATABASE refuses, not about what the Java above it
 * refuses. A canonical pattern enforced only in application code is enforced until somebody writes
 * a second application, or runs a correction script at two in the morning.
 */
public class SchemaTest {

    private static Connection connection;

    @BeforeClass
    public static void applySchema() throws SQLException {
        connection = OracleSupport.freshSchema();
    }

    @AfterClass
    public static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void createsTheThreeTablesAndBothSequences() throws SQLException {
        assertTrue(objectExists("TABLE", "CUSTOMER"));
        assertTrue(objectExists("TABLE", "ACCOUNT"));
        assertTrue(objectExists("TABLE", "APPLIED_TRANSFER"));
        assertTrue(objectExists("SEQUENCE", "CUSTOMER_REF_SEQ"));
        assertTrue(objectExists("SEQUENCE", "ACCOUNT_REF_SEQ"));
    }

    /**
     * The money rule, asserted against the data dictionary rather than against the script that was
     * just read. NUMBER(15,0) is a signed count of minor units; NUMBER(15,2) would put the scale in
     * a second place that can disagree with the currency, and Oracle accepts either without
     * comment.
     */
    @Test
    public void bookedBalanceIsFifteenDigitsOfMinorUnits() throws SQLException {
        PreparedStatement query = connection.prepareStatement(
                "SELECT data_type, data_precision, data_scale FROM user_tab_columns"
                        + " WHERE table_name = 'ACCOUNT' AND column_name = 'BOOKED_BALANCE'");
        try {
            ResultSet row = query.executeQuery();
            assertTrue("booked_balance is missing", row.next());
            assertEquals("NUMBER", row.getString("data_type"));
            assertEquals(15, row.getInt("data_precision"));
            assertEquals("money is minor units - a scale here would be a second scale", 0,
                    row.getInt("data_scale"));
            row.close();
        } finally {
            query.close();
        }
    }

    @Test
    public void refusesAnAccountReferenceThatIsNotCanonical() throws SQLException {
        insertCustomer("CU0000000001");
        try {
            insertAccount("NOT-A-REFERENCE", "CU0000000001", "LIABILITY", "PLN", "OPEN");
            fail("the database accepted an account reference the canonical model forbids");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ACCOUNT_REF_CK"));
        }
    }

    @Test
    public void refusesAStatusOutsideTheEnumeration() throws SQLException {
        insertCustomer("CU0000000002");
        try {
            insertAccount("TB00000000000002", "CU0000000002", "LIABILITY", "PLN", "DORMANT");
            fail("the database accepted a status the canonical model does not define");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ACCOUNT_STATUS_CK"));
        }
    }

    @Test
    public void refusesAnAccountTypeOutsideTheEnumeration() throws SQLException {
        insertCustomer("CU0000000003");
        try {
            insertAccount("TB00000000000003", "CU0000000003", "SAVINGS", "PLN", "OPEN");
            fail("the database accepted an account type the canonical model does not define");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ACCOUNT_TYPE_CK"));
        }
    }

    @Test
    public void refusesAnAccountWhoseCustomerDoesNotExist() throws SQLException {
        try {
            insertAccount("TB00000000000004", "CU9999999999", "LIABILITY", "PLN", "OPEN");
            fail("the database accepted an account belonging to nobody");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ACCOUNT_CUSTOMER_FK"));
        }
    }

    @Test
    public void refusesACustomerReferenceThatIsNotCanonical() throws SQLException {
        try {
            insertCustomer("CUSTOMER-1");
            fail("the database accepted a customer reference the canonical model forbids");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("CUSTOMER_REF_CK"));
        }
    }

    /**
     * The idempotency key is a primary key, which is the mechanism rather than a note about one.
     * Two deliveries of the same transfer race to insert and Oracle refuses the second.
     */
    @Test
    public void refusesTheSameTransferTwice() throws SQLException {
        insertAppliedTransfer("TB202608190000000001");
        try {
            insertAppliedTransfer("TB202608190000000001");
            fail("the same transfer was recorded as applied twice");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("APPLIED_TRANSFER_PK"));
        }
    }

    @Test
    public void refusesAMovementDateBeforeTheAccountWasOpened() throws SQLException {
        insertCustomer("CU0000000005");
        insertAccount("TB00000000000005", "CU0000000005", "LIABILITY", "PLN", "OPEN");
        Statement statement = connection.createStatement();
        try {
            statement.executeUpdate(
                    "UPDATE account SET last_movement_date = opened_date - 1"
                            + " WHERE account_ref = 'TB00000000000005'");
            fail("an account moved before it existed");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ACCOUNT_MOVEMENT_CK"));
        } finally {
            statement.close();
        }
    }

    /**
     * Oracle creates a package body that does not compile. It answers "created with compilation
     * errors", the JDBC call does not throw, and the object sits there invalid until something
     * calls it - at which point the failure is ORA-04063 arriving from whatever test happened to be
     * running. The same shape as the hidden compiler warning F-20 records on the mainframe tier:
     * the build says nothing and the mistake is found by the next person.
     */
    @Test
    public void leavesNoInvalidObjectBehind() throws SQLException {
        StringBuilder invalid = new StringBuilder();
        Statement statement = connection.createStatement();
        try {
            ResultSet rows = statement.executeQuery(
                    "SELECT object_type, object_name FROM user_objects WHERE status <> 'VALID'");
            while (rows.next()) {
                invalid.append(rows.getString(1)).append(' ').append(rows.getString(2)).append('\n');
            }
            rows.close();

            // The compiler's own words, line and column included. Reporting only the object name
            // sends the reader back to Oracle to ask the question the build already had the answer
            // to.
            rows = statement.executeQuery(
                    "SELECT name, type, line, position, text FROM user_errors ORDER BY name, sequence");
            while (rows.next()) {
                invalid.append("  ").append(rows.getString("type")).append(' ')
                        .append(rows.getString("name")).append(" line ").append(rows.getInt("line"))
                        .append(':').append(rows.getInt("position")).append(' ')
                        .append(rows.getString("text").trim()).append('\n');
            }
            rows.close();
        } finally {
            statement.close();
        }
        assertEquals("the schema applied but left objects that do not compile:\n" + invalid,
                "", invalid.toString());
    }

    @Test
    public void bothSequencesAllocate() throws SQLException {
        assertNotNull(nextValueOf("customer_ref_seq"));
        assertNotNull(nextValueOf("account_ref_seq"));
    }

    private Long nextValueOf(String sequence) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            ResultSet row = statement.executeQuery("SELECT " + sequence + ".NEXTVAL FROM dual");
            assertTrue(row.next());
            return Long.valueOf(row.getLong(1));
        } finally {
            statement.close();
        }
    }

    private boolean objectExists(String type, String name) throws SQLException {
        PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM user_objects WHERE object_type = ? AND object_name = ?");
        try {
            query.setString(1, type);
            query.setString(2, name);
            ResultSet row = query.executeQuery();
            boolean found = row.next();
            row.close();
            return found;
        } finally {
            query.close();
        }
    }

    private void insertCustomer(String customerRef) throws SQLException {
        PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO customer (customer_ref, family_name, given_name, date_of_birth,"
                        + " national_id, onboarded_date)"
                        + " VALUES (?, 'TESTFAMILY', 'TESTGIVEN', DATE '1980-01-01', '00000000000',"
                        + " DATE '2011-01-01')");
        try {
            insert.setString(1, customerRef);
            insert.executeUpdate();
        } finally {
            insert.close();
        }
    }

    private void insertAccount(String accountRef, String customerRef, String accountType,
            String currency, String status) throws SQLException {
        PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO account (account_ref, customer_ref, account_type, currency, status,"
                        + " booked_balance, opened_date)"
                        + " VALUES (?, ?, ?, ?, ?, 0, DATE '2011-06-01')");
        try {
            insert.setString(1, accountRef);
            insert.setString(2, customerRef);
            insert.setString(3, accountType);
            insert.setString(4, currency);
            insert.setString(5, status);
            insert.executeUpdate();
        } finally {
            insert.close();
        }
    }

    private void insertAppliedTransfer(String transferRef) throws SQLException {
        PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO applied_transfer (transfer_ref) VALUES (?)");
        try {
            insert.setString(1, transferRef);
            insert.executeUpdate();
        } finally {
            insert.close();
        }
    }
}
