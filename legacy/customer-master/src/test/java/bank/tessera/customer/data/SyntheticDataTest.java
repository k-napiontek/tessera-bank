package bank.tessera.customer.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The only source of a name, a date of birth or a national identifier in this repository.
 *
 * <p>customer-master is the one component that holds personal data, deliberately, so that every
 * other component is out of scope for an erasure request. That makes this generator a control
 * rather than a convenience, and the assertions below are what the control consists of: every value
 * is constructed, and constructed in a shape no issuing authority uses. A generator that produced
 * plausible identifiers would be a generator that could produce a real one.
 */
public class SyntheticDataTest {

    @Test
    public void isDeterministicForASeed() {
        List<SyntheticCustomer> first = SyntheticData.customers(20, 42L);
        List<SyntheticCustomer> second = SyntheticData.customers(20, 42L);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).customerRef(), second.get(i).customerRef());
            assertEquals(first.get(i).familyName(), second.get(i).familyName());
            assertEquals(first.get(i).nationalId(), second.get(i).nationalId());
            assertEquals(first.get(i).dateOfBirth(), second.get(i).dateOfBirth());
        }
    }

    @Test
    public void adifferentSeedGivesDifferentData() {
        List<SyntheticCustomer> first = SyntheticData.customers(20, 42L);
        List<SyntheticCustomer> second = SyntheticData.customers(20, 43L);

        boolean anyDifference = false;
        for (int i = 0; i < first.size(); i++) {
            if (!first.get(i).nationalId().equals(second.get(i).nationalId())) {
                anyDifference = true;
            }
        }
        assertTrue("two seeds produced identical data - the seed is not being used", anyDifference);
    }

    /**
     * A national identifier that could belong to a person is the thing this repository must never
     * hold. Every value is prefixed so that it is not merely unlikely to be issued but cannot be:
     * no authority issues an identifier beginning with these letters.
     */
    @Test
    public void noNationalIdentifierCouldEverHaveBeenIssued() {
        List<SyntheticCustomer> customers = SyntheticData.customers(200, 7L);
        for (int i = 0; i < customers.size(); i++) {
            String nationalId = customers.get(i).nationalId();
            assertTrue("not evidently synthetic: " + nationalId, nationalId.startsWith("SYN-"));
            assertTrue("not the declared shape: " + nationalId,
                    nationalId.matches("^SYN-[0-9]{8}$"));
        }
    }

    /**
     * Names carry their ordinal, so a bare surname cannot appear in this database however large the
     * pool of base names grows. The suffix is what makes each value constructed rather than merely
     * chosen.
     */
    @Test
    public void everyNameCarriesItsOrdinalAndIsThereforeConstructed() {
        List<SyntheticCustomer> customers = SyntheticData.customers(200, 7L);
        for (int i = 0; i < customers.size(); i++) {
            assertTrue("a bare family name: " + customers.get(i).familyName(),
                    customers.get(i).familyName().matches("^[A-Z]+-[0-9]{4}$"));
            assertTrue("a bare given name: " + customers.get(i).givenName(),
                    customers.get(i).givenName().matches("^[A-Z]+-[0-9]{4}$"));
        }
    }

    @Test
    public void everyReferenceMatchesTheCanonicalPattern() {
        List<SyntheticCustomer> customers = SyntheticData.customers(50, 3L);
        for (int i = 0; i < customers.size(); i++) {
            assertTrue(customers.get(i).customerRef(),
                    customers.get(i).customerRef().matches("^CU[0-9]{10}$"));
        }

        List<SyntheticAccount> accounts = SyntheticData.accountsFor(customers, 3L);
        for (int i = 0; i < accounts.size(); i++) {
            assertTrue(accounts.get(i).accountRef(),
                    accounts.get(i).accountRef().matches("^TB[0-9A-Z]{14}$"));
        }
    }

    @Test
    public void referencesAreUnique() {
        List<SyntheticCustomer> customers = SyntheticData.customers(200, 11L);
        Set<String> customerRefs = new HashSet<String>();
        for (int i = 0; i < customers.size(); i++) {
            assertTrue("duplicate customer reference", customerRefs.add(customers.get(i).customerRef()));
        }

        List<SyntheticAccount> accounts = SyntheticData.accountsFor(customers, 11L);
        Set<String> accountRefs = new HashSet<String>();
        for (int i = 0; i < accounts.size(); i++) {
            assertTrue("duplicate account reference", accountRefs.add(accounts.get(i).accountRef()));
        }
    }

    /**
     * Every account currency has an ISO 4217 scale of 2. Stratum 0 stores money as PIC S9(13)V99
     * COMP-3 and cannot represent JPY or BHD, so an account in one of those would be a balance the
     * mainframe misstates a hundredfold. WP-11 rejects such a movement; no account should exist to
     * receive one in the first place.
     */
    @Test
    public void everyAccountCurrencyHasScaleTwo() {
        List<SyntheticAccount> accounts =
                SyntheticData.accountsFor(SyntheticData.customers(100, 5L), 5L);
        Set<String> scaleTwo = new HashSet<String>();
        scaleTwo.add("PLN");
        scaleTwo.add("EUR");
        scaleTwo.add("USD");
        scaleTwo.add("GBP");
        scaleTwo.add("CHF");

        for (int i = 0; i < accounts.size(); i++) {
            assertTrue("a currency stratum 0 cannot represent: " + accounts.get(i).currency(),
                    scaleTwo.contains(accounts.get(i).currency()));
        }
    }

    /** Balances are a count of minor units. A generator that emitted a decimal would seed the bug. */
    @Test
    public void balancesAreWholeMinorUnits() {
        List<SyntheticAccount> accounts =
                SyntheticData.accountsFor(SyntheticData.customers(100, 5L), 5L);
        boolean anyNegative = false;
        for (int i = 0; i < accounts.size(); i++) {
            long balance = accounts.get(i).bookedBalanceMinor();
            if (balance < 0L) {
                anyNegative = true;
            }
            assertTrue("outside AmountMinorType's fifteen digits",
                    Math.abs(balance) < 1000000000000000L);
        }
        assertTrue("no overdrawn account in the fixture - the negative path is never exercised",
                anyNegative);
    }

    @Test
    public void coversEveryAccountStatus() {
        List<SyntheticAccount> accounts =
                SyntheticData.accountsFor(SyntheticData.customers(100, 5L), 5L);
        Set<String> statuses = new HashSet<String>();
        for (int i = 0; i < accounts.size(); i++) {
            statuses.add(accounts.get(i).status());
        }
        assertTrue("no OPEN account", statuses.contains("OPEN"));
        assertTrue("no BLOCKED account", statuses.contains("BLOCKED"));
        assertTrue("no CLOSED account", statuses.contains("CLOSED"));
    }

    @Test
    public void noAccountMovedBeforeItWasOpened() {
        List<SyntheticAccount> accounts =
                SyntheticData.accountsFor(SyntheticData.customers(100, 5L), 5L);
        for (int i = 0; i < accounts.size(); i++) {
            SyntheticAccount account = accounts.get(i);
            if (account.lastMovementDate() != null) {
                assertFalse("moved before it existed: " + account.accountRef(),
                        account.lastMovementDate().before(account.openedDate()));
            }
        }
    }
}
