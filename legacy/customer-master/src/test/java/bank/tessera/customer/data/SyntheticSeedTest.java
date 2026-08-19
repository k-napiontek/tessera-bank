package bank.tessera.customer.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import bank.tessera.customer.db.OracleSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The generator against the schema, which is the only place the two can be shown to agree.
 *
 * <p>{@link SyntheticDataTest} asserts what the generator produces and {@code SchemaTest} asserts
 * what the database refuses; each passes happily while the other rejects everything it makes. A
 * fixture the schema will not accept is discovered by whichever test first tries to use it, at which
 * point the failure is attributed to that test.
 */
public class SyntheticSeedTest {

    private static final int CUSTOMER_COUNT = 60;
    private static final long SEED = 42L;

    private static Connection connection;
    private static List<SyntheticCustomer> customers;
    private static List<SyntheticAccount> accounts;

    @BeforeClass
    public static void seedAFreshSchema() throws SQLException {
        connection = OracleSupport.freshSchema();
        customers = SyntheticData.customers(CUSTOMER_COUNT, SEED);
        accounts = SyntheticData.accountsFor(customers, SEED);
        SyntheticData.seed(connection, customers, accounts);
    }

    @AfterClass
    public static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void everyGeneratedRowWasAcceptedByTheSchema() throws SQLException {
        assertEquals(CUSTOMER_COUNT, countOf("customer"));
        assertEquals(accounts.size(), countOf("account"));
    }

    @Test
    public void theFixtureHasACustomerWithNoAccounts() throws SQLException {
        assertTrue("no customer holds zero accounts, so the empty-list answer is never exercised",
                countOf("customer c WHERE NOT EXISTS ("
                        + "SELECT 1 FROM account a WHERE a.customer_ref = c.customer_ref)") > 0);
    }

    @Test
    public void theFixtureHasACustomerHoldingMoreThanOneAccount() throws SQLException {
        assertTrue("no customer holds two accounts, so a multi-account answer is never exercised",
                countOf("(SELECT customer_ref FROM account"
                        + " GROUP BY customer_ref HAVING COUNT(*) > 1)") > 0);
    }

    @Test
    public void theFixtureHasAnOverdrawnAccount() throws SQLException {
        assertTrue("no negative balance survived the round trip - the sign is not being tested",
                countOf("account WHERE booked_balance < 0") > 0);
    }

    @Test
    public void theFixtureHasAnAccountThatHasNeverMoved() throws SQLException {
        assertTrue("every account has a last movement date, so the absent case is never exercised",
                countOf("account WHERE last_movement_date IS NULL") > 0);
    }

    @Test
    public void theFixtureCoversEveryStatus() throws SQLException {
        assertTrue(countOf("account WHERE status = 'OPEN'") > 0);
        assertTrue(countOf("account WHERE status = 'BLOCKED'") > 0);
        assertTrue(countOf("account WHERE status = 'CLOSED'") > 0);
    }

    /**
     * Read back rather than trusted. A {@code long} handed to {@code setLong} and returned by
     * {@code getLong} is the whole money path this tier has, and the value that matters most is the
     * negative one - a sign lost in the driver looks like a balance, not like an error.
     */
    @Test
    public void balancesSurviveTheRoundTripExactly() throws SQLException {
        for (int i = 0; i < accounts.size(); i++) {
            SyntheticAccount expected = accounts.get(i);
            Statement statement = connection.createStatement();
            try {
                ResultSet row = statement.executeQuery(
                        "SELECT booked_balance FROM account WHERE account_ref = '"
                                + expected.accountRef() + "'");
                assertTrue(expected.accountRef() + " is missing", row.next());
                assertEquals(expected.accountRef(),
                        expected.bookedBalanceMinor(), row.getLong(1));
                row.close();
            } finally {
                statement.close();
            }
        }
    }

    private static int countOf(String fromClause) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + fromClause);
            assertTrue(row.next());
            int count = row.getInt(1);
            row.close();
            return count;
        } finally {
            statement.close();
        }
    }
}
