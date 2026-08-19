package bank.tessera.customer.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bank.tessera.customer.data.SyntheticAccount;
import bank.tessera.customer.data.SyntheticCustomer;
import bank.tessera.customer.data.SyntheticData;
import bank.tessera.customer.db.OracleSupport;
import bank.tessera.customer.domain.Account;
import bank.tessera.customer.domain.Money;
import bank.tessera.customer.domain.ServiceFaultException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The DAO against real Oracle, through the stored procedures.
 *
 * <p>What is being proved here is the mapping, which is where this layer can be wrong on its own:
 * a balance losing its sign between {@code getLong} and {@code Money}, an absent last-movement date
 * arriving as the epoch rather than as null, an Oracle error number becoming the wrong fault code.
 * The procedures' own behaviour is proved next door in {@code PkgAccountTest} and
 * {@code PkgPostingTest}.
 */
public class AccountDaoTest {

    private static Connection connection;
    private static AccountDao dao;
    private static List<SyntheticCustomer> customers;
    private static List<SyntheticAccount> accounts;

    @BeforeClass
    public static void seedAFreshSchema() throws SQLException {
        connection = OracleSupport.freshSchema();
        customers = SyntheticData.customers(40, 2026L);
        accounts = SyntheticData.accountsFor(customers, 2026L);
        SyntheticData.seed(connection, customers, accounts);
        dao = new AccountDao(OracleSupport.dataSource());
    }

    @AfterClass
    public static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void mapsEveryFieldOfAnAccount() {
        SyntheticAccount expected = accounts.get(0);

        Account account = dao.findAccount(expected.accountRef());

        assertEquals(expected.accountRef(), account.getAccountRef());
        assertEquals(expected.customerRef(), account.getCustomerRef());
        assertEquals(expected.accountType(), account.getAccountType());
        assertEquals(expected.status(), account.getStatus());
        assertEquals(expected.currency(), account.getCurrency());
        assertEquals(new Money(expected.bookedBalanceMinor(), expected.currency()),
                account.getBookedBalance());
        assertEquals(expected.openedDate().toString(), account.getOpenedDate().toString());
    }

    /**
     * The sign is the field most easily lost between the database and the wire, and an overdrawn
     * balance reported positive is a customer told they have money they do not have.
     */
    @Test
    public void keepsTheSignOfAnOverdrawnBalance() {
        SyntheticAccount overdrawn = null;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).bookedBalanceMinor() < 0L) {
                overdrawn = accounts.get(i);
                break;
            }
        }
        assertNotNull("the fixture holds no overdrawn account", overdrawn);

        Account account = dao.findAccount(overdrawn.accountRef());

        assertTrue("the sign was lost", account.getBookedBalance().getAmountMinor() < 0L);
        assertEquals(overdrawn.bookedBalanceMinor(), account.getBookedBalance().getAmountMinor());
    }

    /**
     * Absent, not zero and not the epoch. The canonical model says lastMovementDate is absent until
     * the first movement posts, and the XSD makes it the one optional element on Account.
     */
    @Test
    public void reportsAnAbsentLastMovementDateAsNull() {
        SyntheticAccount neverMoved = null;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).lastMovementDate() == null) {
                neverMoved = accounts.get(i);
                break;
            }
        }
        assertNotNull("the fixture holds no account that has never moved", neverMoved);

        assertNull(dao.findAccount(neverMoved.accountRef()).getLastMovementDate());
    }

    @Test
    public void availableBalanceEqualsBookedBalanceAtThisTier() {
        Account account = dao.findAccount(accounts.get(0).accountRef());

        assertEquals("a hold lives in the ledger and nothing tells this tier about one",
                account.getBookedBalance(), account.getAvailableBalance());
    }

    @Test
    public void raisesTheContractsFaultCodeForAnUnknownAccount() {
        try {
            dao.findAccount("TB99999999999999");
            fail("an unknown account returned something");
        } catch (ServiceFaultException expected) {
            assertEquals("ACCT_NOT_FOUND", expected.getFaultCode());
        }
    }

    /** The message must not name the account. A fault reaches logs and, sometimes, a screen. */
    @Test
    public void theFaultMessageNamesNoAccount() {
        try {
            dao.findAccount("TB99999999999999");
            fail("expected a fault");
        } catch (ServiceFaultException expected) {
            assertTrue("the reference leaked into the fault message: " + expected.getMessage(),
                    expected.getMessage().indexOf("TB99999999999999") < 0);
        }
    }

    @Test
    public void returnsEveryAccountACustomerHolds() {
        String customerRef = accounts.get(0).customerRef();
        int expected = 0;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).customerRef().equals(customerRef)) {
                expected++;
            }
        }

        assertEquals(expected, dao.findAccountsByCustomer(customerRef).size());
    }

    @Test
    public void returnsAnEmptyListForACustomerWithNoAccounts() {
        assertTrue(dao.findAccountsByCustomer("CU9999999999").isEmpty());
    }

    @Test
    public void appliesATransferAndReportsARedeliveryAsAlreadyApplied() {
        String debit = accounts.get(0).accountRef();
        String credit = null;
        for (int i = 1; i < accounts.size(); i++) {
            if (accounts.get(i).currency().equals(accounts.get(0).currency())) {
                credit = accounts.get(i).accountRef();
                break;
            }
        }
        assertNotNull("no second account in the same currency", credit);

        long debitBefore = dao.findAccount(debit).getBookedBalance().getAmountMinor();
        Money amount = new Money(1500L, accounts.get(0).currency());
        Date valueDate = Date.valueOf("2026-08-19");

        boolean first = dao.applyTransfer("TB202608190000000100",
                "00000000-0000-4000-8000-000000000001", debit, credit, amount, valueDate);
        boolean second = dao.applyTransfer("TB202608190000000100",
                "00000000-0000-4000-8000-000000000001", debit, credit, amount, valueDate);

        assertEquals(false, first);
        assertEquals(true, second);
        assertEquals("the balance moved twice", debitBefore - 1500L,
                dao.findAccount(debit).getBookedBalance().getAmountMinor());
    }

    @Test
    public void mapsACurrencyMismatchToItsOwnFaultCode() {
        String pln = null;
        String other = null;
        for (int i = 0; i < accounts.size(); i++) {
            if ("PLN".equals(accounts.get(i).currency()) && pln == null) {
                pln = accounts.get(i).accountRef();
            }
            if (!"PLN".equals(accounts.get(i).currency()) && other == null) {
                other = accounts.get(i).accountRef();
            }
        }
        assertNotNull("the fixture has no PLN account", pln);
        assertNotNull("the fixture has only one currency", other);

        try {
            dao.applyTransfer("TB202608190000000101", "00000000-0000-4000-8000-000000000001",
                    pln, other, new Money(100L, "PLN"), Date.valueOf("2026-08-19"));
            fail("minor units of one currency were added to a balance held in another");
        } catch (ServiceFaultException expected) {
            assertEquals("ACCT_CURRENCY_MISMATCH", expected.getFaultCode());
        }
    }

    @Test
    public void mapsANonPositiveAmountToItsOwnFaultCode() {
        try {
            dao.applyTransfer("TB202608190000000102", "00000000-0000-4000-8000-000000000001",
                    accounts.get(0).accountRef(), accounts.get(1).accountRef(),
                    new Money(0L, accounts.get(0).currency()), Date.valueOf("2026-08-19"));
            fail("a zero amount was accepted");
        } catch (ServiceFaultException expected) {
            assertEquals("AMOUNT_NOT_POSITIVE", expected.getFaultCode());
        }
    }

    /**
     * A refused transfer leaves nothing behind, including its claim. Were the claim to survive the
     * rollback, a corrected redelivery of the same transfer would be answered "already applied"
     * forever, and the money would never arrive.
     */
    @Test
    public void aRefusedTransferReleasesItsClaim() {
        String debit = accounts.get(0).accountRef();
        try {
            dao.applyTransfer("TB202608190000000103", "00000000-0000-4000-8000-000000000001",
                    debit, "TB99999999999999", new Money(100L, accounts.get(0).currency()),
                    Date.valueOf("2026-08-19"));
            fail("expected a fault");
        } catch (ServiceFaultException expected) {
            assertEquals("ACCT_NOT_FOUND", expected.getFaultCode());
        }

        String credit = null;
        for (int i = 1; i < accounts.size(); i++) {
            if (accounts.get(i).currency().equals(accounts.get(0).currency())) {
                credit = accounts.get(i).accountRef();
                break;
            }
        }
        boolean alreadyApplied = dao.applyTransfer("TB202608190000000103",
                "00000000-0000-4000-8000-000000000001", debit, credit,
                new Money(100L, accounts.get(0).currency()), Date.valueOf("2026-08-19"));

        assertEquals("the failed attempt kept its claim on the transfer reference", false,
                alreadyApplied);
    }
}
