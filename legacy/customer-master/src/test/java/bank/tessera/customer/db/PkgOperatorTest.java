package bank.tessera.customer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * PKG_OPERATOR - the two things a back office operator does, and the trail each one leaves.
 *
 * <p>Three claims, and they fail independently. That the action happens. That an audit row is
 * written <em>in the same transaction</em>, so a rollback cannot separate the record from the fact.
 * And that the trail cannot afterwards be altered, which is what makes it a control rather than a
 * log.
 *
 * <p>The refusals are tested here rather than only in the screen on purpose. A rule enforced in a
 * JSP is a rule the next caller does not have, and this package is reachable by anything holding a
 * connection.
 */
public class PkgOperatorTest {

    private static final Date BUSINESS_DATE = Date.valueOf("2026-08-18");
    private static final String ACCOUNT = "TB00000000000001";
    private static final String OPERATOR = "OPS01";

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
        execute("DELETE FROM break_acknowledgement");
        execute("DELETE FROM reject_annotation");
        execute("BEGIN EXECUTE IMMEDIATE 'ALTER TRIGGER operator_audit_no_change DISABLE'; END;");
        execute("DELETE FROM operator_audit");
        execute("BEGIN EXECUTE IMMEDIATE 'ALTER TRIGGER operator_audit_no_change ENABLE'; END;");
    }

    @Test
    public void acknowledgingABreakRecordsItAndAuditsIt() throws SQLException {
        acknowledge(ACCOUNT, "VALUE_DRIFT", "checked against the movement file");

        assertEquals(1, count("SELECT COUNT(*) FROM break_acknowledgement"));
        assertEquals(1, count("SELECT COUNT(*) FROM operator_audit"
                + " WHERE action = 'BREAK_ACKNOWLEDGED'"));
        assertEquals(OPERATOR, single("SELECT actor FROM operator_audit"));
        assertEquals(ACCOUNT, single("SELECT subject_ref FROM operator_audit"));
    }

    /**
     * A double-click is one act. A trail that records two misleads whoever reads it later, which is
     * the only reason anybody reads a trail at all.
     */
    @Test
    public void acknowledgingTwiceIsOneActAndOneAuditRow() throws SQLException {
        acknowledge(ACCOUNT, "VALUE_DRIFT", "first");
        acknowledge(ACCOUNT, "VALUE_DRIFT", "second");

        assertEquals(1, count("SELECT COUNT(*) FROM break_acknowledgement"));
        assertEquals(1, count("SELECT COUNT(*) FROM operator_audit"));
        assertEquals("the first acknowledgement stands; the second is not a new act",
                "first", single("SELECT note FROM break_acknowledgement"));
    }

    /**
     * A TIMING break is expected and is not the operator's to work - ADR 0015. Enforced here and
     * not only by hiding a button, because the screen is not the only caller this package has.
     */
    @Test
    public void aTimingBreakCannotBeAcknowledged() throws SQLException {
        try {
            acknowledge(ACCOUNT, "TIMING", "should not be possible");
            fail("a TIMING break was acknowledged");
        } catch (SQLException expected) {
            assertEquals(20011, expected.getErrorCode());
            assertTrue(expected.getMessage().contains("ADR 0015"));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM operator_audit"));
    }

    @Test
    public void annotatingARejectRecordsItAndAuditsIt() throws SQLException {
        annotate("TB202608180000000001", 1, "raised with the ledger team");

        assertEquals(1, count("SELECT COUNT(*) FROM reject_annotation"));
        assertEquals("TB202608180000000001/1", single("SELECT subject_ref FROM operator_audit"));
    }

    /**
     * Re-annotating replaces the note, and the previous text survives only in the trail. That is
     * the whole reason the trail is append-only.
     */
    @Test
    public void reAnnotatingIsItselfAnAuditedAct() throws SQLException {
        annotate("TB202608180000000001", 1, "first look");
        annotate("TB202608180000000001", 1, "second look, escalated");

        assertEquals(1, count("SELECT COUNT(*) FROM reject_annotation"));
        assertEquals(2, count("SELECT COUNT(*) FROM operator_audit"));
        assertEquals("second look, escalated", single("SELECT note FROM reject_annotation"));
        assertEquals("the earlier note survives in the trail and nowhere else",
                1, count("SELECT COUNT(*) FROM operator_audit WHERE detail = 'first look'"));
    }

    @Test
    public void anAnnotationWithNoNoteIsRefused() throws SQLException {
        try {
            annotate("TB202608180000000001", 1, "   ");
            fail("an empty annotation was accepted");
        } catch (SQLException expected) {
            assertEquals(20012, expected.getErrorCode());
        }
    }

    @Test
    public void theAuditTrailCannotBeUpdated() throws SQLException {
        acknowledge(ACCOUNT, "VALUE_DRIFT", "note");
        try {
            execute("UPDATE operator_audit SET actor = 'SOMEONE-ELSE'");
            fail("an audit row was rewritten");
        } catch (SQLException expected) {
            assertEquals(20010, expected.getErrorCode());
        }
    }

    @Test
    public void theAuditTrailCannotBeDeleted() throws SQLException {
        acknowledge(ACCOUNT, "VALUE_DRIFT", "note");
        try {
            execute("DELETE FROM operator_audit");
            fail("an audit row was deleted");
        } catch (SQLException expected) {
            assertEquals(20010, expected.getErrorCode());
        }
    }

    /**
     * The change and its record are one transaction. A trail written afterwards is one a rollback
     * silently separates from the fact it describes.
     */
    @Test
    public void arollbackTakesTheAuditRowWithIt() throws SQLException {
        connection.setAutoCommit(false);
        try {
            acknowledge(ACCOUNT, "VALUE_DRIFT", "will be rolled back");
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
        assertEquals(0, count("SELECT COUNT(*) FROM break_acknowledgement"));
        assertEquals(0, count("SELECT COUNT(*) FROM operator_audit"));
    }

    private void acknowledge(String accountRef, String classification, String note)
            throws SQLException {
        CallableStatement call = connection.prepareCall(
                "{call pkg_operator.acknowledge_break(?, ?, ?, ?, ?)}");
        try {
            call.setDate(1, BUSINESS_DATE);
            call.setString(2, accountRef);
            call.setString(3, classification);
            call.setString(4, OPERATOR);
            if (note == null) {
                call.setNull(5, Types.VARCHAR);
            } else {
                call.setString(5, note);
            }
            call.execute();
        } finally {
            call.close();
        }
    }

    private void annotate(String transferRef, int legNo, String note) throws SQLException {
        CallableStatement call = connection.prepareCall(
                "{call pkg_operator.annotate_reject(?, ?, ?, ?, ?)}");
        try {
            call.setDate(1, BUSINESS_DATE);
            call.setString(2, transferRef);
            call.setInt(3, legNo);
            call.setString(4, OPERATOR);
            call.setString(5, note);
            call.execute();
        } finally {
            call.close();
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

    private static int count(String sql) throws SQLException {
        return Integer.parseInt(single(sql));
    }

    private static String single(String sql) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            ResultSet rows = statement.executeQuery(sql);
            try {
                assertTrue("no row for " + sql, rows.next());
                return rows.getString(1);
            } finally {
                rows.close();
            }
        } finally {
            statement.close();
        }
    }
}
