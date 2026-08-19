package bank.tessera.customer.dao;

import bank.tessera.customer.domain.Account;
import bank.tessera.customer.domain.Money;
import bank.tessera.customer.domain.ServiceFaultException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;

/**
 * Data access for accounts. Plain JDBC over stored procedures, as 2011 wrote it.
 *
 * <p>There is no SQL in this class and that is deliberate. Every statement lives in a PL/SQL package
 * where a 2011 team would have put it, and this layer opens a connection, calls a procedure and maps
 * what comes back. It is also the reason a migration off Oracle is genuinely difficult rather than
 * merely tedious - the business logic does not move with the Java. That difficulty is the point;
 * see TD-005.
 *
 * <p>No ORM, no framework, no connection pool of its own: the container supplies a
 * {@link DataSource} through JNDI, which is how a WAR got one before dependency injection was
 * something a bank had heard of.
 */
public final class AccountDao {

    /** The Oracle error numbers PKG_POSTING and PKG_ACCOUNT raise, and the codes they carry. */
    private static final int ACCT_NOT_FOUND = 20001;
    private static final int ACCT_CURRENCY_MISMATCH = 20002;
    private static final int AMOUNT_NOT_POSITIVE = 20003;
    private static final int SAME_ACCOUNT = 20004;

    private final DataSource dataSource;

    public AccountDao(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("a DataSource is required");
        }
        this.dataSource = dataSource;
    }

    /**
     * @throws ServiceFaultException with code ACCT_NOT_FOUND when the account is unknown
     */
    public Account findAccount(String accountRef) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            CallableStatement call =
                    connection.prepareCall("{call pkg_account.get_account(?, ?)}");
            try {
                call.setString(1, accountRef);
                call.registerOutParameter(2, ORACLE_CURSOR);
                call.execute();

                ResultSet rows = (ResultSet) call.getObject(2);
                try {
                    if (!rows.next()) {
                        // Unreachable while the procedure raises for an unknown reference, and
                        // stated anyway: an empty cursor here would otherwise become a
                        // NoSuchElementException three frames away from its cause.
                        throw new ServiceFaultException("ACCT_NOT_FOUND", "no such account");
                    }
                    return accountOf(rows);
                } finally {
                    rows.close();
                }
            } finally {
                call.close();
            }
        } catch (SQLException e) {
            throw faultFor(e);
        } finally {
            closeQuietly(connection);
        }
    }

    /** An empty list is an answer, not a fault. The WSDL says so where the operation is defined. */
    public List<Account> findAccountsByCustomer(String customerRef) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            CallableStatement call =
                    connection.prepareCall("{call pkg_account.get_accounts_by_customer(?, ?)}");
            try {
                call.setString(1, customerRef);
                call.registerOutParameter(2, ORACLE_CURSOR);
                call.execute();

                List<Account> accounts = new ArrayList<Account>();
                ResultSet rows = (ResultSet) call.getObject(2);
                try {
                    while (rows.next()) {
                        accounts.add(accountOf(rows));
                    }
                } finally {
                    rows.close();
                }
                return Collections.unmodifiableList(accounts);
            } finally {
                call.close();
            }
        } catch (SQLException e) {
            throw faultFor(e);
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * Applies a transfer the ledger has already posted.
     *
     * @return true when this transfer had been applied before, which the caller treats as success
     */
    public boolean applyTransfer(String transferRef, String correlationId, String debitAccountRef,
            String creditAccountRef, Money amount, java.util.Date valueDate) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            // One unit of work: the claim on the transfer and both legs commit together or not at
            // all. A half-applied transfer is a debit with nowhere for the money to land.
            connection.setAutoCommit(false);
            CallableStatement call = connection.prepareCall(
                    "{call pkg_posting.apply_transfer(?, ?, ?, ?, ?, ?, ?, ?)}");
            try {
                call.setString(1, transferRef);
                call.setString(2, correlationId);
                call.setString(3, debitAccountRef);
                call.setString(4, creditAccountRef);
                call.setLong(5, amount.getAmountMinor());
                call.setString(6, amount.getCurrency());
                call.setDate(7, new Date(valueDate.getTime()));
                call.registerOutParameter(8, Types.NUMERIC);
                call.execute();

                boolean alreadyApplied = call.getInt(8) == 1;
                connection.commit();
                return alreadyApplied;
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw faultFor(e);
            } finally {
                call.close();
            }
        } catch (SQLException e) {
            throw faultFor(e);
        } finally {
            closeQuietly(connection);
        }
    }

    private static Account accountOf(ResultSet row) throws SQLException {
        return new Account(
                row.getString("account_ref"),
                row.getString("customer_ref"),
                row.getString("account_type"),
                row.getString("status"),
                new Money(row.getLong("booked_balance"), row.getString("currency")),
                row.getDate("opened_date"),
                row.getDate("last_movement_date"));
    }

    /**
     * Maps an Oracle error number to the fault code the contract declares. By number, never by
     * message text: a message is prose that changes between releases, and a layer that pattern-
     * matched on it would report ACCT_NOT_FOUND for a dropped connection the day Oracle reworded
     * something.
     */
    private static RuntimeException faultFor(SQLException e) {
        switch (e.getErrorCode()) {
            case ACCT_NOT_FOUND:
                return new ServiceFaultException("ACCT_NOT_FOUND", "no such account");
            case ACCT_CURRENCY_MISMATCH:
                return new ServiceFaultException("ACCT_CURRENCY_MISMATCH",
                        "the movement currency does not match the account currency");
            case AMOUNT_NOT_POSITIVE:
                return new ServiceFaultException("AMOUNT_NOT_POSITIVE",
                        "an amount must be positive; direction carries the sign");
            case SAME_ACCOUNT:
                return new ServiceFaultException("SAME_ACCOUNT",
                        "both legs name the same account");
            default:
                // Nothing from the driver is repeated to the caller. An Oracle message can carry
                // the bind values that caused it, and a bind value here is a customer's data.
                return new DataAccessException("the customer master is unavailable", e);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the caller is already being told about the original failure, which is the useful one
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // returning a connection to the pool cannot change what the caller is told
            }
        }
    }

    /**
     * {@code oracle.jdbc.OracleTypes.CURSOR}, written out rather than imported. The driver is a
     * provided dependency supplied by the container, and naming the constant keeps this class
     * compiling against the JDK alone.
     */
    private static final int ORACLE_CURSOR = -10;
}
