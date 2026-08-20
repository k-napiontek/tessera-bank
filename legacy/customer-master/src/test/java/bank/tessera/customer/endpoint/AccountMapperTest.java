package bank.tessera.customer.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import bank.tessera.customer.domain.Money;
import bank.tessera.customer.ws.AccountStatusType;
import bank.tessera.customer.ws.AccountTypeType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import org.junit.Test;

/**
 * The boundary between what this component holds and what the contract says.
 *
 * <p>Nothing here needs a database, which is the point of having a mapper at all: the shape of a
 * tb:Account is decided by canonical-v1.xsd and can be checked in milliseconds, separately from
 * whether Oracle returned the right row.
 */
public class AccountMapperTest {

    private static final Date OPENED = date(2011, Calendar.MARCH, 14);
    private static final Date MOVED = date(2026, Calendar.AUGUST, 3);

    @Test
    public void carriesEveryElementTheSchemaMakesMandatory() {
        bank.tessera.customer.ws.Account mapped = AccountMapper.toContract(account(MOVED));

        assertEquals("TB00000000000001", mapped.getAccountRef());
        assertEquals("CU0000000042", mapped.getCustomerRef());
        assertEquals(AccountTypeType.LIABILITY, mapped.getAccountType());
        assertEquals("PLN", mapped.getCurrency());
        assertEquals(AccountStatusType.OPEN, mapped.getStatus());
        assertNotNull(mapped.getBookedBalance());
        assertNotNull(mapped.getAvailableBalance());
        assertNotNull(mapped.getOpenedDate());
    }

    @Test
    public void moneyKeepsItsCurrencyAndItsMinorUnits() {
        bank.tessera.customer.ws.Account mapped = AccountMapper.toContract(account(MOVED));

        assertEquals(123456789L, mapped.getBookedBalance().getAmountMinor());
        assertEquals("PLN", mapped.getBookedBalance().getCurrency());
    }

    /**
     * Not a shortcut. A hold lives in the ledger and no operation in this WSDL carries one, so the
     * only available balance this tier can defend is the booked one - see ADR 0010 and F-50.
     */
    @Test
    public void availableBalanceEqualsBookedBalanceBecauseThisTierCannotSeeAHold() {
        bank.tessera.customer.ws.Account mapped = AccountMapper.toContract(account(MOVED));

        assertEquals(mapped.getBookedBalance().getAmountMinor(),
                mapped.getAvailableBalance().getAmountMinor());
        assertEquals(mapped.getBookedBalance().getCurrency(),
                mapped.getAvailableBalance().getCurrency());
    }

    /**
     * Two MoneyType instances, not one shared object. JAXB marshals whatever the getter returns and
     * a shared instance would marshal identically today - but the two figures are separate concepts
     * and the day one of them starts differing, aliasing them is a defect nobody would look for.
     */
    @Test
    public void bookedAndAvailableAreSeparateInstances() {
        bank.tessera.customer.ws.Account mapped = AccountMapper.toContract(account(MOVED));

        if (mapped.getBookedBalance() == mapped.getAvailableBalance()) {
            fail("booked and available balance are the same object");
        }
    }

    @Test
    public void aDateBecomesACalendarDateWithNoTimeAndNoTimezone() {
        bank.tessera.customer.ws.Account mapped = AccountMapper.toContract(account(MOVED));

        XMLGregorianCalendar opened = mapped.getOpenedDate();
        assertEquals(2011, opened.getYear());
        assertEquals(3, opened.getMonth());
        assertEquals(14, opened.getDay());
        assertEquals("an xs:date carries no time of day",
                DatatypeConstants.FIELD_UNDEFINED, opened.getHour());
        assertEquals("a value date is not an instant, so it carries no timezone",
                DatatypeConstants.FIELD_UNDEFINED, opened.getTimezone());
    }

    /**
     * lastMovementDate is the one optional element in tb:Account. Null must stay null so JAXB omits
     * the element: an empty element is not the same document, and minOccurs="0" permits absence
     * rather than emptiness.
     */
    @Test
    public void anAccountThatHasNeverMovedCarriesNoLastMovementDate() {
        bank.tessera.customer.ws.Account mapped = AccountMapper.toContract(account(null));

        assertNull(mapped.getLastMovementDate());
    }

    @Test
    public void anAccountThatHasMovedCarriesTheDateItMovedOn() {
        bank.tessera.customer.ws.Account mapped = AccountMapper.toContract(account(MOVED));

        XMLGregorianCalendar moved = mapped.getLastMovementDate();
        assertNotNull(moved);
        assertEquals(2026, moved.getYear());
        assertEquals(8, moved.getMonth());
        assertEquals(3, moved.getDay());
    }

    /**
     * The enumerations are the schema's, not this component's. A status the canonical model does not
     * define must fail here rather than travel: the alternative is a response that validates in the
     * sender and is rejected by every consumer, which is the harder failure to diagnose of the two.
     */
    @Test
    public void aStatusOutsideTheCanonicalEnumerationIsRefused() {
        bank.tessera.customer.domain.Account dormant = new bank.tessera.customer.domain.Account(
                "TB00000000000001", "CU0000000042", "LIABILITY", "DORMANT",
                new Money(1L, "PLN"), OPENED, null);

        try {
            AccountMapper.toContract(dormant);
            fail("DORMANT is not in AccountStatusType and was mapped anyway");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void nullIsNotAnAccount() {
        try {
            AccountMapper.toContract(null);
            fail("null was mapped to something");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }

    /** Stateless, like the other helpers in this module. A mapper holding a field is a mapper two
     *  threads can disagree about, and every request in a servlet container is a thread. */
    @Test
    public void theMapperHoldsNoState() {
        Constructor<?>[] constructors = AccountMapper.class.getDeclaredConstructors();
        for (int i = 0; i < constructors.length; i++) {
            if (!Modifier.isPrivate(constructors[i].getModifiers())) {
                fail("AccountMapper has a non-private constructor");
            }
        }
        assertEquals("AccountMapper declares an instance field", 0,
                AccountMapper.class.getDeclaredFields().length
                        - staticFieldCount(AccountMapper.class));
    }

    private static int staticFieldCount(Class<?> type) {
        int count = 0;
        Field[] fields = type.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            if (Modifier.isStatic(fields[i].getModifiers())) {
                count++;
            }
        }
        return count;
    }

    private static bank.tessera.customer.domain.Account account(Date lastMovement) {
        return new bank.tessera.customer.domain.Account(
                "TB00000000000001", "CU0000000042", "LIABILITY", "OPEN",
                new Money(123456789L, "PLN"), OPENED, lastMovement);
    }

    private static Date date(int year, int month, int day) {
        GregorianCalendar calendar = new GregorianCalendar(year, month, day, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}
