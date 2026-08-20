package bank.tessera.customer.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bank.tessera.customer.dao.AccountDao;
import bank.tessera.customer.data.SyntheticAccount;
import bank.tessera.customer.data.SyntheticCustomer;
import bank.tessera.customer.data.SyntheticData;
import bank.tessera.customer.db.OracleSupport;
import bank.tessera.customer.ws.ServiceFaultMessage;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The two read operations, driven through the generated interface against real Oracle.
 *
 * <p>These call the endpoint object directly rather than over HTTP: what is checked here is that
 * it answers the contract's questions correctly, and the separate deployment test checks that a WAR
 * on Tomcat 8.5 carries those answers over the wire. Splitting the two is what keeps this suite
 * runnable in seconds.
 */
public class CustomerMasterEndpointReadTest {

    private static Connection connection;
    private static CustomerMasterEndpoint endpoint;
    private static List<SyntheticCustomer> customers;
    private static List<SyntheticAccount> accounts;

    @BeforeClass
    public static void seedAFreshSchema() throws SQLException {
        connection = OracleSupport.freshSchema();
        customers = SyntheticData.customers(40, 2026L);
        accounts = SyntheticData.accountsFor(customers, 2026L);
        SyntheticData.seed(connection, customers, accounts);
        endpoint = new CustomerMasterEndpoint(new AccountDao(OracleSupport.dataSource()));
    }

    @AfterClass
    public static void closeTheConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void getAccountAnswersWithTheAccountTheContractDescribes() throws Exception {
        SyntheticAccount expected = accounts.get(0);

        bank.tessera.customer.ws.Account answer = endpoint.getAccount(expected.accountRef());

        assertEquals(expected.accountRef(), answer.getAccountRef());
        assertEquals(expected.customerRef(), answer.getCustomerRef());
        assertEquals(expected.currency(), answer.getCurrency());
        assertEquals(expected.accountType(), answer.getAccountType().value());
        assertEquals(expected.status(), answer.getStatus().value());
        assertEquals(expected.bookedBalanceMinor(), answer.getBookedBalance().getAmountMinor());
        assertNotNull(answer.getOpenedDate());
    }

    @Test
    public void anUnknownAccountIsTheFaultTheWsdlNames() {
        try {
            endpoint.getAccount("TB99999999999999");
            fail("an unknown account was answered rather than faulted");
        } catch (ServiceFaultMessage fault) {
            assertNotNull("the fault carries no detail", fault.getFaultInfo());
            assertEquals("ACCT_NOT_FOUND", fault.getFaultInfo().getFaultCode());
            assertNotNull(fault.getFaultInfo().getFaultMessage());
        }
    }

    @Test
    public void getAccountsByCustomerAnswersWithEveryAccountThatCustomerHolds() throws Exception {
        // Not customers.get(0): the generator gives some customers no accounts at all, which is
        // realistic and which this test would otherwise mistake for a passing assertion.
        String customerRef = aCustomerHoldingAccounts();
        List<String> expected = accountRefsOf(customerRef);
        assertTrue("the fixture gives no customer an account, so this test proves nothing",
                expected.size() > 0);

        List<bank.tessera.customer.ws.Account> answer =
                endpoint.getAccountsByCustomer(customerRef);

        assertEquals(expected.size(), answer.size());
        for (int i = 0; i < answer.size(); i++) {
            assertTrue("an account was returned that this customer does not hold",
                    expected.contains(answer.get(i).getAccountRef()));
        }
    }

    /**
     * The WSDL says in as many words that an empty list is an answer and not a fault. It also means
     * a mistyped customerRef reads as "this customer holds no accounts", which is F-52 and a
     * contract change rather than something to fix here.
     */
    @Test
    public void anUnknownCustomerIsAnEmptyListAndNotAFault() throws Exception {
        List<bank.tessera.customer.ws.Account> answer =
                endpoint.getAccountsByCustomer("CU9999999999");

        assertNotNull("an empty answer is a list, never null", answer);
        assertEquals(0, answer.size());
    }

    /**
     * An error path is the second most common place personal data escapes a system, which is why
     * the WSDL says so where the fault is defined. The customer table holds a family name, a given
     * name and a national identifier; none of them may appear in anything a fault carries.
     */
    @Test
    public void aFaultCarriesNoIdentity() {
        try {
            endpoint.getAccount("TB99999999999999");
            fail("an unknown account was answered rather than faulted");
        } catch (ServiceFaultMessage fault) {
            String onTheWire = fault.getMessage()
                    + " " + fault.getFaultInfo().getFaultCode()
                    + " " + fault.getFaultInfo().getFaultMessage()
                    + " " + fault.getFaultInfo().getCorrelationId();
            assertNoIdentityIn(onTheWire);
        }
    }

    /**
     * The same claim about the success path, which is where the volume is. tb:Account carries a
     * customerRef and nothing else about a person - that is the structural reason the rest of the
     * estate is out of scope for an erasure request, and it is worth asserting rather than reading
     * off the schema.
     */
    @Test
    public void aSuccessfulAnswerCarriesNoIdentity() throws Exception {
        SyntheticAccount held = accounts.get(0);

        bank.tessera.customer.ws.Account answer = endpoint.getAccount(held.accountRef());

        assertNoIdentityIn(answer.getAccountRef()
                + " " + answer.getCustomerRef()
                + " " + answer.getCurrency()
                + " " + answer.getAccountType().value()
                + " " + answer.getStatus().value()
                + " " + answer.getOpenedDate()
                + " " + answer.getLastMovementDate());
    }

    private static void assertNoIdentityIn(String text) {
        String haystack = text.toUpperCase();
        for (int i = 0; i < customers.size(); i++) {
            SyntheticCustomer customer = customers.get(i);
            assertAbsent(haystack, customer.familyName(), "a family name");
            assertAbsent(haystack, customer.givenName(), "a given name");
            assertAbsent(haystack, customer.nationalId(), "a national identifier");
        }
    }

    private static void assertAbsent(String haystack, String needle, String what) {
        assertTrue(what + " reached a SOAP message", !haystack.contains(needle.toUpperCase()));
    }

    private static String aCustomerHoldingAccounts() {
        for (int i = 0; i < customers.size(); i++) {
            String customerRef = customers.get(i).customerRef();
            if (!accountRefsOf(customerRef).isEmpty()) {
                return customerRef;
            }
        }
        return customers.get(0).customerRef();
    }

    private static List<String> accountRefsOf(String customerRef) {
        List<String> refs = new ArrayList<String>();
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).customerRef().equals(customerRef)) {
                refs.add(accounts.get(i).accountRef());
            }
        }
        return refs;
    }
}
