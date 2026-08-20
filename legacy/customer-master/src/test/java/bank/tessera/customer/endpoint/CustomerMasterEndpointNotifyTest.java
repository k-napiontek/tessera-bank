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
import bank.tessera.customer.ws.DirectionType;
import bank.tessera.customer.ws.MoneyType;
import bank.tessera.customer.ws.Movement;
import bank.tessera.customer.ws.ServiceFaultMessage;
import bank.tessera.customer.ws.Transfer;
import bank.tessera.customer.ws.TransferStatusType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.ws.Holder;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * NotifyTransferPosted - the operation that makes this component a system of record rather than a
 * read-only cache.
 *
 * <p>The important test here is the redelivery. Upstream delivery is at-least-once, which the WSDL
 * states, so the same message arriving twice is an expected event and not an error - and the version
 * of this that applies it twice looks entirely correct in a suite that only ever sends it once.
 */
public class CustomerMasterEndpointNotifyTest {

    private static final DatatypeFactory DATATYPES = datatypeFactory();

    private static Connection connection;
    private static CustomerMasterEndpoint endpoint;
    private static List<SyntheticCustomer> customers;
    private static List<SyntheticAccount> accounts;

    private static int transferSequence;

    private String debitRef;
    private String creditRef;
    private String currency;

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

    @Before
    public void chooseTwoAccountsInOneCurrency() {
        // Both legs must hold the same currency: nothing in this estate converts, so a movement in
        // a currency the account does not hold is refused rather than translated.
        for (int i = 0; i < accounts.size(); i++) {
            for (int j = i + 1; j < accounts.size(); j++) {
                SyntheticAccount left = accounts.get(i);
                SyntheticAccount right = accounts.get(j);
                if (left.currency().equals(right.currency())) {
                    debitRef = left.accountRef();
                    creditRef = right.accountRef();
                    currency = left.currency();
                    return;
                }
            }
        }
        fail("the fixture holds no two accounts in one currency");
    }

    @Test
    public void aPostedTransferMovesBothLegsAndIsNotAlreadyApplied() throws Exception {
        long amount = 25_00L;
        long debitBefore = bookedBalanceOf(debitRef);
        long creditBefore = bookedBalanceOf(creditRef);

        Holder<String> transferRef = new Holder<String>();
        Holder<Boolean> alreadyApplied = new Holder<Boolean>();
        String reference = nextTransferRef();
        endpoint.notifyTransferPosted(transfer(reference, debitRef, creditRef, amount, currency),
                movements(reference, debitRef, creditRef, amount, currency),
                transferRef, alreadyApplied);

        assertEquals(reference, transferRef.value);
        assertEquals(Boolean.FALSE, alreadyApplied.value);
        assertEquals("the debit leg did not move by the sign convention for its account type",
                debitBefore - amount, bookedBalanceOf(debitRef));
        assertEquals("the credit leg did not move by the sign convention for its account type",
                creditBefore + amount, bookedBalanceOf(creditRef));
    }

    /**
     * The whole point of the operation. Applying a day's postings twice doubles every balance in
     * the bank, and "the operator would notice" is not a control.
     */
    @Test
    public void aRedeliveryIsAcknowledgedAndTheMoneyMovesOnlyOnce() throws Exception {
        long amount = 13_37L;
        String reference = nextTransferRef();
        long debitBefore = bookedBalanceOf(debitRef);
        long creditBefore = bookedBalanceOf(creditRef);

        Holder<String> firstRef = new Holder<String>();
        Holder<Boolean> firstApplied = new Holder<Boolean>();
        endpoint.notifyTransferPosted(transfer(reference, debitRef, creditRef, amount, currency),
                movements(reference, debitRef, creditRef, amount, currency),
                firstRef, firstApplied);

        long debitAfterFirst = bookedBalanceOf(debitRef);
        long creditAfterFirst = bookedBalanceOf(creditRef);

        Holder<String> secondRef = new Holder<String>();
        Holder<Boolean> secondApplied = new Holder<Boolean>();
        endpoint.notifyTransferPosted(transfer(reference, debitRef, creditRef, amount, currency),
                movements(reference, debitRef, creditRef, amount, currency),
                secondRef, secondApplied);

        assertEquals(Boolean.FALSE, firstApplied.value);
        assertEquals("a redelivery must be acknowledged, not refused",
                Boolean.TRUE, secondApplied.value);
        assertEquals(reference, secondRef.value);
        assertEquals(debitBefore - amount, debitAfterFirst);
        assertEquals("the redelivery moved the debit leg a second time",
                debitAfterFirst, bookedBalanceOf(debitRef));
        assertEquals("the redelivery moved the credit leg a second time",
                creditAfterFirst, bookedBalanceOf(creditRef));
    }

    @Test
    public void anUnknownAccountIsTheFaultTheWsdlNames() {
        String reference = nextTransferRef();
        try {
            endpoint.notifyTransferPosted(
                    transfer(reference, "TB99999999999999", creditRef, 100L, currency),
                    movements(reference, "TB99999999999999", creditRef, 100L, currency),
                    new Holder<String>(), new Holder<Boolean>());
            fail("a movement against an unknown account was applied");
        } catch (ServiceFaultMessage fault) {
            assertEquals("ACCT_NOT_FOUND", fault.getFaultInfo().getFaultCode());
        }
    }

    @Test
    public void aCurrencyTheAccountDoesNotHoldIsRefused() {
        String reference = nextTransferRef();
        String foreign = "PLN".equals(currency) ? "EUR" : "PLN";
        try {
            endpoint.notifyTransferPosted(
                    transfer(reference, debitRef, creditRef, 100L, foreign),
                    movements(reference, debitRef, creditRef, 100L, foreign),
                    new Holder<String>(), new Holder<Boolean>());
            fail("a movement in the wrong currency was added to the balance");
        } catch (ServiceFaultMessage fault) {
            assertEquals("ACCT_CURRENCY_MISMATCH", fault.getFaultInfo().getFaultCode());
        }
    }

    @Test
    public void bothLegsNamingOneAccountIsRefused() {
        String reference = nextTransferRef();
        try {
            endpoint.notifyTransferPosted(
                    transfer(reference, debitRef, debitRef, 100L, currency),
                    movements(reference, debitRef, debitRef, 100L, currency),
                    new Holder<String>(), new Holder<Boolean>());
            fail("a transfer from an account to itself was applied");
        } catch (ServiceFaultMessage fault) {
            assertEquals("SAME_ACCOUNT", fault.getFaultInfo().getFaultCode());
        }
    }

    @Test
    public void anAmountThatIsNotPositiveIsRefused() {
        String reference = nextTransferRef();
        try {
            endpoint.notifyTransferPosted(
                    transfer(reference, debitRef, creditRef, 0L, currency),
                    movements(reference, debitRef, creditRef, 0L, currency),
                    new Holder<String>(), new Holder<Boolean>());
            fail("a zero amount was applied");
        } catch (ServiceFaultMessage fault) {
            assertEquals("AMOUNT_NOT_POSITIVE", fault.getFaultInfo().getFaultCode());
        }
    }

    /**
     * The schema says exactly two movements and enforces it on the wire. A call that arrives with
     * some other number is a programming error rather than a business fault, so it is refused as
     * one - inventing a fault code for it would put a word on the wire that no contract declares.
     */
    @Test
    public void aMessageThatIsNotTwoMovementsIsRefused() {
        String reference = nextTransferRef();
        List<Movement> one = new ArrayList<Movement>(
                movements(reference, debitRef, creditRef, 100L, currency).subList(0, 1));

        try {
            endpoint.notifyTransferPosted(transfer(reference, debitRef, creditRef, 100L, currency),
                    one, new Holder<String>(), new Holder<Boolean>());
            fail("a transfer carrying one movement was applied");
        } catch (ServiceFaultMessage unexpected) {
            fail("a malformed message became a business fault: " + unexpected.getMessage());
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void theValueDateComesFromTheMovementsAndReachesTheAccount() throws Exception {
        long amount = 5_00L;
        String reference = nextTransferRef();

        endpoint.notifyTransferPosted(transfer(reference, debitRef, creditRef, amount, currency),
                movements(reference, debitRef, creditRef, amount, currency),
                new Holder<String>(), new Holder<Boolean>());

        bank.tessera.customer.ws.Account moved = endpoint.getAccount(debitRef);
        assertNotNull("an account that has just moved carries no last movement date",
                moved.getLastMovementDate());
        assertTrue("the last movement date predates the value date of the posting",
                moved.getLastMovementDate().getYear() >= 2026);
    }

    private static long bookedBalanceOf(String accountRef) throws Exception {
        return endpoint.getAccount(accountRef).getBookedBalance().getAmountMinor();
    }

    private static synchronized String nextTransferRef() {
        transferSequence++;
        return String.format("TB%018d", Integer.valueOf(transferSequence));
    }

    private static Transfer transfer(String transferRef, String debit, String credit,
            long amountMinor, String currencyCode) {
        Transfer transfer = new Transfer();
        transfer.setTransferRef(transferRef);
        transfer.setDebitAccountRef(debit);
        transfer.setCreditAccountRef(credit);
        transfer.setAmount(money(amountMinor, currencyCode));
        transfer.setStatus(TransferStatusType.POSTED);
        transfer.setRequestedAt(instant());
        transfer.setPostedAt(instant());
        transfer.setCorrelationId("11111111-2222-3333-4444-555555555555");
        return transfer;
    }

    private static List<Movement> movements(String transferRef, String debit, String credit,
            long amountMinor, String currencyCode) {
        return new ArrayList<Movement>(Arrays.asList(
                movement(transferRef, 1, debit, DirectionType.DEBIT, amountMinor, currencyCode),
                movement(transferRef, 2, credit, DirectionType.CREDIT, amountMinor, currencyCode)));
    }

    private static Movement movement(String transferRef, int legNo, String accountRef,
            DirectionType direction, long amountMinor, String currencyCode) {
        Movement movement = new Movement();
        movement.setMovementRef(transferRef + "-" + String.format("%02d", Integer.valueOf(legNo)));
        movement.setTransferRef(transferRef);
        movement.setLegNo(legNo);
        movement.setAccountRef(accountRef);
        movement.setDirection(direction);
        movement.setAmount(money(amountMinor, currencyCode));
        movement.setValueDate(DATATYPES.newXMLGregorianCalendarDate(
                2026, 8, 20, DatatypeConstants.FIELD_UNDEFINED));
        movement.setPostedAt(instant());
        return movement;
    }

    private static MoneyType money(long amountMinor, String currencyCode) {
        MoneyType money = new MoneyType();
        money.setAmountMinor(amountMinor);
        money.setCurrency(currencyCode);
        return money;
    }

    private static XMLGregorianCalendar instant() {
        return DATATYPES.newXMLGregorianCalendar(
                2026, 8, 20, 9, 30, 0, 0, 0);
    }

    private static DatatypeFactory datatypeFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException unavailable) {
            throw new IllegalStateException("no JAXP DatatypeFactory", unavailable);
        }
    }
}
