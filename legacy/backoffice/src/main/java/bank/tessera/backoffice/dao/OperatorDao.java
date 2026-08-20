package bank.tessera.backoffice.dao;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The two things an operator does, and what has already been done.
 *
 * <p>There is no SQL here beyond a read, and that is deliberate: both mutations are
 * {@code PKG_OPERATOR} calls, so the change and its audit row are one transaction inside the
 * database. A DAO that inserted the audit row itself would be a DAO an application bug could skip
 * past, and the trail would be missing exactly the act somebody later needed to find.
 *
 * <p>The {@link DataSource} comes from JNDI, which is how a WAR got one before dependency injection
 * - the same {@code jdbc/customerMaster} resource {@code customer-master} declares, because this is
 * one bank with one customer master and two applications reading it.
 */
public final class OperatorDao {

    /** Oracle's -20011: a TIMING break is expected and is not the operator's to work. */
    public static final int TIMING_NOT_ACTIONABLE = 20011;

    /** Oracle's -20012: an annotation needs a note. */
    public static final int NOTE_REQUIRED = 20012;

    private final DataSource dataSource;

    public OperatorDao(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("a DataSource is required");
        }
        this.dataSource = dataSource;
    }

    /** Acknowledge a break. Idempotent, and refused outright for a TIMING break. */
    public void acknowledgeBreak(String businessDate, String accountRef, String classification,
            String actor, String note) throws OperatorException {
        Connection connection = null;
        CallableStatement call = null;
        try {
            connection = dataSource.getConnection();
            call = connection.prepareCall("{call pkg_operator.acknowledge_break(?, ?, ?, ?, ?)}");
            call.setDate(1, toDate(businessDate));
            call.setString(2, accountRef);
            call.setString(3, classification);
            call.setString(4, actor);
            if (note == null || note.trim().length() == 0) {
                call.setNull(5, Types.VARCHAR);
            } else {
                call.setString(5, note.trim());
            }
            call.execute();
        } catch (SQLException problem) {
            throw translate(problem);
        } finally {
            close(call, connection);
        }
    }

    /** Annotate a reject leg. Re-annotating replaces the note and is itself audited. */
    public void annotateReject(String businessDate, String transferRef, int legNo, String actor,
            String note) throws OperatorException {
        Connection connection = null;
        CallableStatement call = null;
        try {
            connection = dataSource.getConnection();
            call = connection.prepareCall("{call pkg_operator.annotate_reject(?, ?, ?, ?, ?)}");
            call.setDate(1, toDate(businessDate));
            call.setString(2, transferRef);
            call.setInt(3, legNo);
            call.setString(4, actor);
            call.setString(5, note == null ? null : note.trim());
            call.execute();
        } catch (SQLException problem) {
            throw translate(problem);
        } finally {
            close(call, connection);
        }
    }

    /**
     * Which accounts already carry an acknowledgement for the date, and who made it.
     *
     * <p>Read in one query rather than one per row. A screen that asked the database once per break
     * would be two hundred round trips for a morning nobody is waiting on, and it is the shape that
     * quietly stops working when the estate has a bad night.
     */
    public Map<String, String> acknowledgementsFor(String businessDate) throws OperatorException {
        Map<String, String> byAccount = new HashMap<String, String>();
        Connection connection = null;
        java.sql.PreparedStatement query = null;
        ResultSet rows = null;
        try {
            connection = dataSource.getConnection();
            query = connection.prepareStatement(
                    "SELECT account_ref, acknowledged_by FROM break_acknowledgement"
                            + " WHERE business_date = ?");
            query.setDate(1, toDate(businessDate));
            rows = query.executeQuery();
            while (rows.next()) {
                byAccount.put(rows.getString(1), rows.getString(2));
            }
            return byAccount;
        } catch (SQLException problem) {
            throw translate(problem);
        } finally {
            closeQuietly(rows);
            close(query, connection);
        }
    }

    /** Notes already filed against a reject leg, keyed as {@code transferRef/legNo}. */
    public Map<String, String> annotationsFor(String businessDate) throws OperatorException {
        Map<String, String> byLeg = new HashMap<String, String>();
        Connection connection = null;
        java.sql.PreparedStatement query = null;
        ResultSet rows = null;
        try {
            connection = dataSource.getConnection();
            query = connection.prepareStatement(
                    "SELECT transfer_ref, leg_no, note FROM reject_annotation"
                            + " WHERE business_date = ?");
            query.setDate(1, toDate(businessDate));
            rows = query.executeQuery();
            while (rows.next()) {
                byLeg.put(rows.getString(1) + "/" + rows.getInt(2), rows.getString(3));
            }
            return byLeg;
        } catch (SQLException problem) {
            throw translate(problem);
        } finally {
            closeQuietly(rows);
            close(query, connection);
        }
    }

    private static OperatorException translate(SQLException problem) {
        int code = problem.getErrorCode();
        if (code == TIMING_NOT_ACTIONABLE) {
            return new OperatorException(code,
                    "a timing difference is expected and is not acknowledged", problem);
        }
        if (code == NOTE_REQUIRED) {
            return new OperatorException(code, "an annotation needs a note", problem);
        }
        return new OperatorException(code, "the customer master refused the action", problem);
    }

    /** CCYYMMDD, the form the whole estate writes a business date in. */
    private static Date toDate(String businessDate) throws SQLException {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
            format.setLenient(false);
            return new Date(format.parse(businessDate).getTime());
        } catch (ParseException notADate) {
            throw new SQLException("not a CCYYMMDD business date: " + businessDate, notADate);
        }
    }

    private static void close(Statement statement, Connection connection) {
        closeQuietly(statement);
        closeQuietly(connection);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Closing is best effort. Reporting a failure to close would replace whatever
                // exception is already on its way out with a less interesting one.
            }
        }
    }
}
