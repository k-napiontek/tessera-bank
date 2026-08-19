package bank.tessera.customer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The idempotency claim under actual contention.
 *
 * <p>{@code PkgPostingTest} calls the procedure twice in sequence, and a procedure that reads
 * {@code applied_transfer} and then inserts into it passes that test perfectly. It is wrong anyway:
 * two deliveries arriving together both find nothing, both conclude the transfer is theirs, and both
 * apply it. The same defect the ledger's idempotency store had, where {@code ON CONFLICT DO NOTHING}
 * let eight retries execute eight times.
 *
 * <p>So the mechanism has to be the unique constraint, and a test that cannot tell the two
 * implementations apart is not testing the mechanism. This one runs eight deliveries at once.
 */
public class PkgPostingConcurrencyTest {

    private static final int DELIVERIES = 8;
    private static final String TRANSFER_REF = "TB202608190000009999";
    private static final String DEBIT_ACCOUNT = "TB00000000000001";
    private static final String CREDIT_ACCOUNT = "TB00000000000002";
    private static final long AMOUNT_MINOR = 25000L;
    private static final long OPENING_BALANCE = 100000L;

    private static Connection connection;

    @BeforeClass
    public static void seedAFreshSchema() throws SQLException {
        connection = OracleSupport.freshSchema();
        execute("INSERT INTO customer (customer_ref, family_name, given_name, date_of_birth,"
                + " national_id, onboarded_date) VALUES ('CU0000000001', 'TESSERA-0001',"
                + " 'PRIMA-0001', DATE '1980-01-01', 'SYN-00000001', DATE '2011-01-01')");
        execute("INSERT INTO account (account_ref, customer_ref, account_type, currency, status,"
                + " booked_balance, opened_date) VALUES ('" + DEBIT_ACCOUNT + "', 'CU0000000001',"
                + " 'LIABILITY', 'PLN', 'OPEN', " + OPENING_BALANCE + ", DATE '2011-06-01')");
        execute("INSERT INTO account (account_ref, customer_ref, account_type, currency, status,"
                + " booked_balance, opened_date) VALUES ('" + CREDIT_ACCOUNT + "', 'CU0000000001',"
                + " 'LIABILITY', 'PLN', 'OPEN', 0, DATE '2011-06-01')");
    }

    @AfterClass
    public static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void eightSimultaneousDeliveriesApplyTheTransferOnce() throws Exception {
        final CountDownLatch startLine = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(DELIVERIES);
        final AtomicInteger applied = new AtomicInteger();
        final AtomicInteger duplicates = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final java.util.List<String> reasons =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        for (int i = 0; i < DELIVERIES; i++) {
            Thread delivery = new Thread(new Runnable() {
                public void run() {
                    Connection own = null;
                    try {
                        own = OracleSupport.connection();
                        // The whole procedure is one unit of work: the claim on the transfer and
                        // the two legs commit together or not at all.
                        own.setAutoCommit(false);
                        startLine.await();
                        if (applyTransfer(own)) {
                            duplicates.incrementAndGet();
                        } else {
                            applied.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        reasons.add(e.getClass().getName() + ": " + e.getMessage());
                    } finally {
                        closeQuietly(own);
                        finished.countDown();
                    }
                }
            });
            delivery.start();
        }

        startLine.countDown();
        assertTrue("the deliveries did not finish", finished.await(60, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals("a delivery failed outright: " + reasons, 0, failures.get());
        assertEquals("more than one delivery believed it was the first", 1, applied.get());
        assertEquals(DELIVERIES - 1, duplicates.get());
        assertEquals("the transfer was recorded more than once", 1,
                countOf("applied_transfer WHERE transfer_ref = '" + TRANSFER_REF + "'"));
        assertEquals("the balance moved more than once", OPENING_BALANCE - AMOUNT_MINOR,
                balanceOf(DEBIT_ACCOUNT));
        assertEquals(AMOUNT_MINOR, balanceOf(CREDIT_ACCOUNT));
    }

    private static boolean applyTransfer(Connection own) throws SQLException {
        CallableStatement call = own.prepareCall(
                "{call pkg_posting.apply_transfer(?, ?, ?, ?, ?, ?, ?, ?)}");
        try {
            call.setString(1, TRANSFER_REF);
            call.setString(2, "00000000-0000-4000-8000-000000000001");
            call.setString(3, DEBIT_ACCOUNT);
            call.setString(4, CREDIT_ACCOUNT);
            call.setLong(5, AMOUNT_MINOR);
            call.setString(6, "PLN");
            call.setDate(7, Date.valueOf("2026-08-19"));
            call.registerOutParameter(8, Types.NUMERIC);
            call.execute();
            boolean duplicate = call.getInt(8) == 1;
            own.commit();
            return duplicate;
        } finally {
            call.close();
        }
    }

    private static long balanceOf(String accountRef) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            ResultSet row = statement.executeQuery(
                    "SELECT booked_balance FROM account WHERE account_ref = '" + accountRef + "'");
            assertTrue(row.next());
            long balance = row.getLong(1);
            row.close();
            return balance;
        } finally {
            statement.close();
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

    private static void execute(String sql) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute(sql);
        } finally {
            statement.close();
        }
    }

    private static void closeQuietly(Connection candidate) {
        if (candidate != null) {
            try {
                candidate.close();
            } catch (SQLException ignored) {
                // closing a connection the test is finished with cannot change the outcome
            }
        }
    }
}
