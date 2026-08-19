package bank.tessera.customer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bank.tessera.customer.data.SyntheticAccount;
import bank.tessera.customer.data.SyntheticCustomer;
import bank.tessera.customer.data.SyntheticData;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import oracle.jdbc.OracleTypes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * PKG_ACCOUNT, the read side, in PL/SQL against real Oracle.
 *
 * <p>The two operations look symmetrical and are not, which is the point of testing both. An unknown
 * ACCOUNT is a fault - the WSDL names ACCT_NOT_FOUND for it. An unknown CUSTOMER is an empty list,
 * because the same WSDL says in as many words that an empty list is an answer and not a fault, and
 * it defines no fault code for a customer. An implementer who reasons from symmetry gets one of the
 * two wrong and gets it wrong plausibly.
 */
public class PkgAccountTest {

    private static Connection connection;
    private static List<SyntheticCustomer> customers;
    private static List<SyntheticAccount> accounts;

    @BeforeClass
    public static void seedAFreshSchema() throws SQLException {
        connection = OracleSupport.freshSchema();
        customers = SyntheticData.customers(40, 99L);
        accounts = SyntheticData.accountsFor(customers, 99L);
        SyntheticData.seed(connection, customers, accounts);
    }

    @AfterClass
    public static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void returnsAnAccountByItsReference() throws SQLException {
        SyntheticAccount expected = accounts.get(0);

        CallableStatement call = connection.prepareCall("{call pkg_account.get_account(?, ?)}");
        try {
            call.setString(1, expected.accountRef());
            call.registerOutParameter(2, OracleTypes.CURSOR);
            call.execute();

            ResultSet row = (ResultSet) call.getObject(2);
            assertTrue("no row for a known account", row.next());
            assertEquals(expected.accountRef(), row.getString("account_ref"));
            assertEquals(expected.customerRef(), row.getString("customer_ref"));
            assertEquals(expected.accountType(), row.getString("account_type"));
            assertEquals(expected.currency(), row.getString("currency"));
            assertEquals(expected.status(), row.getString("status"));
            assertEquals(expected.bookedBalanceMinor(), row.getLong("booked_balance"));
            assertNotNull(row.getDate("opened_date"));
            assertFalse("get_account returned more than one row", row.next());
            row.close();
        } finally {
            call.close();
        }
    }

    /**
     * The fault path. ORA-20001 carries ACCT_NOT_FOUND, which is the code the WSDL names, so the
     * mapping from a PL/SQL exception to a SOAP fault is a lookup rather than a decision made in
     * Java from a message string.
     */
    @Test
    public void raisesAcctNotFoundForAnUnknownAccount() throws SQLException {
        CallableStatement call = connection.prepareCall("{call pkg_account.get_account(?, ?)}");
        try {
            call.setString(1, "TB99999999999999");
            call.registerOutParameter(2, OracleTypes.CURSOR);
            call.execute();
            fail("an unknown account reference returned a result instead of raising");
        } catch (SQLException expected) {
            assertEquals("the fault must be ORA-20001", 20001, expected.getErrorCode());
            assertTrue(expected.getMessage(), expected.getMessage().contains("ACCT_NOT_FOUND"));
        } finally {
            call.close();
        }
    }

    /** A closed account is still an account. Its metadata is exactly what an operator asks for. */
    @Test
    public void returnsAClosedAccountRatherThanHidingIt() throws SQLException {
        String closedRef = null;
        for (int i = 0; i < accounts.size(); i++) {
            if ("CLOSED".equals(accounts.get(i).status())) {
                closedRef = accounts.get(i).accountRef();
                break;
            }
        }
        assertNotNull("the fixture holds no closed account", closedRef);

        List<String> found = accountRefsOf(closedRef);
        assertEquals(1, found.size());
    }

    @Test
    public void returnsEveryAccountACustomerHolds() throws SQLException {
        String customerRef = customerHolding(2);
        assertNotNull("the fixture holds no customer with two accounts", customerRef);

        List<String> returned = accountsOfCustomer(customerRef);
        List<String> expected = new ArrayList<String>();
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).customerRef().equals(customerRef)) {
                expected.add(accounts.get(i).accountRef());
            }
        }
        java.util.Collections.sort(returned);
        java.util.Collections.sort(expected);
        assertEquals(expected, returned);
    }

    @Test
    public void answersACustomerWithNoAccountsWithAnEmptyList() throws SQLException {
        String customerRef = customerHolding(0);
        assertNotNull("the fixture holds no customer without accounts", customerRef);

        assertTrue("a customer with no accounts must be an empty list, not a fault",
                accountsOfCustomer(customerRef).isEmpty());
    }

    /**
     * An unknown customer is an empty list too. It is not obviously the right answer - a typo
     * returns "no accounts" rather than "no such customer" - but the WSDL defines exactly one fault
     * code and it is about accounts. Inventing CUST_NOT_FOUND here would put a code on the wire that
     * no contract declares, which is the drift a contract-first estate exists to prevent. Logged as
     * a follow-up rather than decided unilaterally.
     */
    @Test
    public void answersAnUnknownCustomerWithAnEmptyListRatherThanAFault() throws SQLException {
        assertTrue(accountsOfCustomer("CU9999999999").isEmpty());
    }

    private static String customerHolding(int howMany) {
        for (int i = 0; i < customers.size(); i++) {
            String customerRef = customers.get(i).customerRef();
            int count = 0;
            for (int j = 0; j < accounts.size(); j++) {
                if (accounts.get(j).customerRef().equals(customerRef)) {
                    count++;
                }
            }
            if (count == howMany) {
                return customerRef;
            }
        }
        return null;
    }

    private static List<String> accountsOfCustomer(String customerRef) throws SQLException {
        CallableStatement call =
                connection.prepareCall("{call pkg_account.get_accounts_by_customer(?, ?)}");
        try {
            call.setString(1, customerRef);
            call.registerOutParameter(2, OracleTypes.CURSOR);
            call.execute();
            return refsOf((ResultSet) call.getObject(2));
        } finally {
            call.close();
        }
    }

    private static List<String> accountRefsOf(String accountRef) throws SQLException {
        CallableStatement call = connection.prepareCall("{call pkg_account.get_account(?, ?)}");
        try {
            call.setString(1, accountRef);
            call.registerOutParameter(2, OracleTypes.CURSOR);
            call.execute();
            return refsOf((ResultSet) call.getObject(2));
        } finally {
            call.close();
        }
    }

    private static List<String> refsOf(ResultSet rows) throws SQLException {
        List<String> refs = new ArrayList<String>();
        try {
            while (rows.next()) {
                refs.add(rows.getString("account_ref"));
            }
        } finally {
            rows.close();
        }
        return refs;
    }
}
