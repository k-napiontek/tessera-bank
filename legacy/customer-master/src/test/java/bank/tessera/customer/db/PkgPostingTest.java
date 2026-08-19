package bank.tessera.customer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * PKG_POSTING.apply_transfer - what NotifyTransferPosted actually does.
 *
 * <p>Two things are being proved here and they are easy to conflate. The first is arithmetic: a
 * debit and a credit change two balances by the right amounts and in the right directions, which
 * depends on the account type and not on the word "debit". The second is idempotence, and the only
 * test of idempotence worth having calls the procedure twice for real - a test that hands it a flag
 * saying "this is a duplicate" is satisfied by a procedure that ignores the flag.
 */
public class PkgPostingTest {

    private static final String DEBIT_ACCOUNT = "TB00000000000001";
    private static final String CREDIT_ACCOUNT = "TB00000000000002";
    private static final String ASSET_ACCOUNT = "TB00000000000003";
    private static final String CLOSED_ACCOUNT = "TB00000000000004";
    private static final String EUR_ACCOUNT = "TB00000000000005";
    private static final Date VALUE_DATE = Date.valueOf("2026-08-19");

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

    @Before
    public void resetFixture() throws SQLException {
        execute("DELETE FROM applied_transfer");
        execute("DELETE FROM account");
        execute("DELETE FROM customer");
        execute("INSERT INTO customer (customer_ref, family_name, given_name, date_of_birth,"
                + " national_id, onboarded_date) VALUES ('CU0000000001', 'TESSERA-0001',"
                + " 'PRIMA-0001', DATE '1980-01-01', 'SYN-00000001', DATE '2011-01-01')");
        insertAccount(DEBIT_ACCOUNT, "LIABILITY", "PLN", "OPEN", 100000L);
        insertAccount(CREDIT_ACCOUNT, "LIABILITY", "PLN", "OPEN", 50000L);
        insertAccount(ASSET_ACCOUNT, "ASSET", "PLN", "OPEN", 0L);
        insertAccount(CLOSED_ACCOUNT, "LIABILITY", "PLN", "CLOSED", 700L);
        insertAccount(EUR_ACCOUNT, "LIABILITY", "EUR", "OPEN", 0L);
    }

    /**
     * A customer current account is a LIABILITY of the bank, so a debit REDUCES it and a credit
     * increases it. Getting this backwards produces a system that balances perfectly and reports
     * every customer's money with the wrong sign.
     */
    @Test
    public void aDebitReducesALiabilityAndACreditIncreasesOne() throws SQLException {
        boolean alreadyApplied = applyTransfer("TB202608190000000001", DEBIT_ACCOUNT,
                CREDIT_ACCOUNT, 25000L, "PLN");

        assertEquals(false, alreadyApplied);
        assertEquals(75000L, balanceOf(DEBIT_ACCOUNT));
        assertEquals(75000L, balanceOf(CREDIT_ACCOUNT));
    }

    /**
     * The mirror image, and the reason the rule cannot be "debit means minus". An ASSET account -
     * the bank's cash and reserves - increases on a debit. Same procedure, same call, opposite
     * arithmetic, decided by the account type.
     */
    @Test
    public void aDebitIncreasesAnAsset() throws SQLException {
        applyTransfer("TB202608190000000002", ASSET_ACCOUNT, CREDIT_ACCOUNT, 3000L, "PLN");

        assertEquals(3000L, balanceOf(ASSET_ACCOUNT));
        assertEquals(53000L, balanceOf(CREDIT_ACCOUNT));
    }

    @Test
    public void movesAnOverdrawnAccountFurtherRatherThanRefusing() throws SQLException {
        execute("UPDATE account SET booked_balance = -500 WHERE account_ref = '"
                + DEBIT_ACCOUNT + "'");

        applyTransfer("TB202608190000000003", DEBIT_ACCOUNT, CREDIT_ACCOUNT, 100L, "PLN");

        assertEquals("the ledger already posted this - the master mirrors it", -600L,
                balanceOf(DEBIT_ACCOUNT));
    }

    /**
     * The idempotence test, and it re-invokes the procedure rather than describing a re-invocation.
     * At-least-once delivery makes a second call an expected event; applying it twice is a
     * customer's payment taken twice.
     */
    @Test
    public void appliesTheSameTransferOnceHoweverOftenItArrives() throws SQLException {
        boolean firstCall = applyTransfer("TB202608190000000004", DEBIT_ACCOUNT, CREDIT_ACCOUNT,
                10000L, "PLN");
        long afterFirst = balanceOf(DEBIT_ACCOUNT);

        boolean secondCall = applyTransfer("TB202608190000000004", DEBIT_ACCOUNT, CREDIT_ACCOUNT,
                10000L, "PLN");
        boolean thirdCall = applyTransfer("TB202608190000000004", DEBIT_ACCOUNT, CREDIT_ACCOUNT,
                10000L, "PLN");

        assertEquals("the first delivery is not a duplicate", false, firstCall);
        assertEquals("the second delivery must report itself as one", true, secondCall);
        assertEquals("and so must the third", true, thirdCall);
        assertEquals("the balance moved more than once", 90000L, afterFirst);
        assertEquals("the balance moved again on redelivery", 90000L, balanceOf(DEBIT_ACCOUNT));
    }

    @Test
    public void recordsTheTransferAsAppliedExactlyOnce() throws SQLException {
        applyTransfer("TB202608190000000005", DEBIT_ACCOUNT, CREDIT_ACCOUNT, 1L, "PLN");
        applyTransfer("TB202608190000000005", DEBIT_ACCOUNT, CREDIT_ACCOUNT, 1L, "PLN");

        assertEquals(1, countOf("applied_transfer WHERE transfer_ref = 'TB202608190000000005'"));
    }

    @Test
    public void setsTheLastMovementDateOnBothLegs() throws SQLException {
        applyTransfer("TB202608190000000006", DEBIT_ACCOUNT, CREDIT_ACCOUNT, 1L, "PLN");

        assertEquals(VALUE_DATE.toString(), lastMovementDateOf(DEBIT_ACCOUNT));
        assertEquals(VALUE_DATE.toString(), lastMovementDateOf(CREDIT_ACCOUNT));
    }

    /** A backdated correction must not drag the last movement date backwards. */
    @Test
    public void neverMovesTheLastMovementDateBackwards() throws SQLException {
        applyTransfer("TB202608190000000007", DEBIT_ACCOUNT, CREDIT_ACCOUNT, 1L, "PLN");
        applyTransferOn("TB202608180000000008", DEBIT_ACCOUNT, CREDIT_ACCOUNT, 1L, "PLN",
                Date.valueOf("2026-08-01"));

        assertEquals(VALUE_DATE.toString(), lastMovementDateOf(DEBIT_ACCOUNT));
    }

    /**
     * A posting to an account the master has never heard of is an integrity failure, not a business
     * outcome: the two systems disagree about which accounts exist. It faults, WP-11 dead-letters
     * it and a person looks.
     */
    @Test
    public void raisesWhenAnAccountIsUnknown() throws SQLException {
        try {
            applyTransfer("TB202608190000000009", "TB99999999999999", CREDIT_ACCOUNT, 1L, "PLN");
            fail("a posting to an unknown account was absorbed");
        } catch (SQLException expected) {
            assertEquals(20001, expected.getErrorCode());
            assertTrue(expected.getMessage(), expected.getMessage().contains("ACCT_NOT_FOUND"));
        }
    }

    /**
     * A CLOSED account is applied to, and this is the decision most likely to look like a bug.
     * NotifyTransferPosted reports a posting the ledger has ALREADY made. Refusing it does not
     * unmake the movement; it only makes this master permanently wrong about that account, and it
     * hides the disagreement from batch/recon by never recording it. A block belongs before a
     * payment, where it can still prevent one.
     */
    @Test
    public void appliesToAClosedAccountBecauseTheLedgerAlreadyPosted() throws SQLException {
        applyTransfer("TB202608190000000010", CLOSED_ACCOUNT, CREDIT_ACCOUNT, 200L, "PLN");

        assertEquals(500L, balanceOf(CLOSED_ACCOUNT));
    }

    /**
     * A currency mismatch is different in kind. There is no exchange rate anywhere in this estate
     * and no conversion at any tier, so adding EUR minor units to a PLN balance is not a posting
     * with a caveat - it is a number that means nothing.
     */
    @Test
    public void raisesWhenALegIsInAnotherCurrency() throws SQLException {
        try {
            applyTransfer("TB202608190000000011", DEBIT_ACCOUNT, EUR_ACCOUNT, 100L, "PLN");
            fail("EUR minor units were added to a PLN balance");
        } catch (SQLException expected) {
            assertEquals(20002, expected.getErrorCode());
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("ACCT_CURRENCY_MISMATCH"));
        }
    }

    @Test
    public void raisesWhenTheAmountIsNotPositive() throws SQLException {
        try {
            applyTransfer("TB202608190000000012", DEBIT_ACCOUNT, CREDIT_ACCOUNT, -100L, "PLN");
            fail("a negative amount was accepted - direction carries the sign, not the amount");
        } catch (SQLException expected) {
            assertEquals(20003, expected.getErrorCode());
            assertTrue(expected.getMessage(), expected.getMessage().contains("AMOUNT_NOT_POSITIVE"));
        }
    }

    @Test
    public void raisesWhenBothLegsNameTheSameAccount() throws SQLException {
        try {
            applyTransfer("TB202608190000000013", DEBIT_ACCOUNT, DEBIT_ACCOUNT, 100L, "PLN");
            fail("an account was allowed to pay itself");
        } catch (SQLException expected) {
            assertEquals(20004, expected.getErrorCode());
            assertTrue(expected.getMessage(), expected.getMessage().contains("SAME_ACCOUNT"));
        }
    }

    /**
     * Nothing is written when a leg is refused. A procedure that debits, then discovers the credit
     * account is unknown, then raises, has already moved money out of a customer's account with
     * nowhere for it to land.
     */
    @Test
    public void leavesNothingBehindWhenItRefuses() throws SQLException {
        long before = balanceOf(DEBIT_ACCOUNT);
        try {
            applyTransfer("TB202608190000000014", DEBIT_ACCOUNT, "TB99999999999999", 100L, "PLN");
            fail("expected a fault");
        } catch (SQLException expected) {
            assertEquals(20001, expected.getErrorCode());
        }

        assertEquals("the debit leg was applied before the credit leg was refused", before,
                balanceOf(DEBIT_ACCOUNT));
        assertEquals("a refused transfer was recorded as applied", 0,
                countOf("applied_transfer WHERE transfer_ref = 'TB202608190000000014'"));
    }

    /** Value is conserved: whatever leaves one account arrives at the other. */
    @Test
    public void conservesValueAcrossTheTwoLegs() throws SQLException {
        long totalBefore = balanceOf(DEBIT_ACCOUNT) + balanceOf(CREDIT_ACCOUNT);

        applyTransfer("TB202608190000000015", DEBIT_ACCOUNT, CREDIT_ACCOUNT, 12345L, "PLN");

        assertEquals(totalBefore, balanceOf(DEBIT_ACCOUNT) + balanceOf(CREDIT_ACCOUNT));
    }

    private boolean applyTransfer(String transferRef, String debitAccount, String creditAccount,
            long amountMinor, String currency) throws SQLException {
        return applyTransferOn(transferRef, debitAccount, creditAccount, amountMinor, currency,
                VALUE_DATE);
    }

    private boolean applyTransferOn(String transferRef, String debitAccount, String creditAccount,
            long amountMinor, String currency, Date valueDate) throws SQLException {
        CallableStatement call = connection.prepareCall(
                "{call pkg_posting.apply_transfer(?, ?, ?, ?, ?, ?, ?, ?)}");
        try {
            call.setString(1, transferRef);
            call.setString(2, "00000000-0000-4000-8000-000000000001");
            call.setString(3, debitAccount);
            call.setString(4, creditAccount);
            call.setLong(5, amountMinor);
            call.setString(6, currency);
            call.setDate(7, valueDate);
            call.registerOutParameter(8, Types.NUMERIC);
            call.execute();
            return call.getInt(8) == 1;
        } finally {
            call.close();
        }
    }

    private long balanceOf(String accountRef) throws SQLException {
        PreparedStatement query = connection.prepareStatement(
                "SELECT booked_balance FROM account WHERE account_ref = ?");
        try {
            query.setString(1, accountRef);
            ResultSet row = query.executeQuery();
            assertTrue(accountRef + " is missing", row.next());
            long balance = row.getLong(1);
            row.close();
            return balance;
        } finally {
            query.close();
        }
    }

    private String lastMovementDateOf(String accountRef) throws SQLException {
        PreparedStatement query = connection.prepareStatement(
                "SELECT last_movement_date FROM account WHERE account_ref = ?");
        try {
            query.setString(1, accountRef);
            ResultSet row = query.executeQuery();
            assertTrue(row.next());
            Date date = row.getDate(1);
            row.close();
            return date == null ? null : date.toString();
        } finally {
            query.close();
        }
    }

    private int countOf(String fromClause) throws SQLException {
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

    private void insertAccount(String accountRef, String accountType, String currency,
            String status, long balanceMinor) throws SQLException {
        PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO account (account_ref, customer_ref, account_type, currency, status,"
                        + " booked_balance, opened_date)"
                        + " VALUES (?, 'CU0000000001', ?, ?, ?, ?, DATE '2011-06-01')");
        try {
            insert.setString(1, accountRef);
            insert.setString(2, accountType);
            insert.setString(3, currency);
            insert.setString(4, status);
            insert.setLong(5, balanceMinor);
            insert.executeUpdate();
        } finally {
            insert.close();
        }
    }

    private void execute(String sql) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute(sql);
        } finally {
            statement.close();
        }
    }
}
