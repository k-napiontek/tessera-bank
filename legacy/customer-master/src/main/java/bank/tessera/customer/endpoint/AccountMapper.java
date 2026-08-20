package bank.tessera.customer.endpoint;

import bank.tessera.customer.domain.Money;
import bank.tessera.customer.ws.AccountStatusType;
import bank.tessera.customer.ws.AccountTypeType;
import bank.tessera.customer.ws.MoneyType;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * Turns what this component holds into what the contract declares.
 *
 * <p>One direction only. Account metadata is read from here and never written through the SOAP
 * interface - onboarding is not an operation in this WSDL - so a reverse mapper would be code with
 * no caller pretending to be symmetry.
 *
 * <p>The target types are generated from {@code contracts/xsd/canonical-v1.xsd} and are never
 * hand-written, which is what makes this class the only place the two vocabularies meet.
 */
public final class AccountMapper {

    /**
     * Thread-safe in practice for the one method used here: {@code newXMLGregorianCalendarDate}
     * constructs a value and keeps nothing. Every request in a servlet container is a thread, so
     * this is worth stating rather than assuming.
     */
    private static final DatatypeFactory DATATYPES = datatypeFactory();

    private AccountMapper() {
    }

    public static bank.tessera.customer.ws.Account toContract(
            bank.tessera.customer.domain.Account account) {
        if (account == null) {
            throw new IllegalArgumentException("an account is required");
        }

        bank.tessera.customer.ws.Account contract = new bank.tessera.customer.ws.Account();
        contract.setAccountRef(account.getAccountRef());
        contract.setCustomerRef(account.getCustomerRef());

        // fromValue throws IllegalArgumentException on anything the canonical model does not
        // define. That is the behaviour wanted: a status this schema has no word for must stop
        // here, not travel to a consumer that will reject the whole document for it.
        contract.setAccountType(AccountTypeType.fromValue(account.getAccountType()));
        contract.setStatus(AccountStatusType.fromValue(account.getStatus()));

        contract.setCurrency(account.getCurrency());
        contract.setBookedBalance(money(account.getBookedBalance()));
        contract.setAvailableBalance(money(account.getAvailableBalance()));
        contract.setOpenedDate(calendarDate(account.getOpenedDate()));

        // Null stays null, so JAXB omits the element entirely. minOccurs="0" permits an absent
        // lastMovementDate; it does not permit an empty one, and the two are different documents.
        contract.setLastMovementDate(calendarDate(account.getLastMovementDate()));

        return contract;
    }

    /**
     * A separate instance for each of booked and available. They hold the same figure today because
     * this tier cannot see a hold - ADR 0010 - and sharing one object would make the day that stops
     * being true a defect nobody would think to look for.
     */
    private static MoneyType money(Money amount) {
        MoneyType contract = new MoneyType();
        contract.setAmountMinor(amount.getAmountMinor());
        contract.setCurrency(amount.getCurrency());
        return contract;
    }

    /**
     * A java.util.Date to an xs:date: the calendar date alone, with no time of day and no timezone.
     *
     * <p>A value date is a date in the bank's own calendar, not an instant, so attaching a timezone
     * would attach a meaning the column does not have - and an xs:date carrying an offset compares
     * differently from one without it in every consumer that does the comparison properly.
     */
    private static XMLGregorianCalendar calendarDate(Date date) {
        if (date == null) {
            return null;
        }
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTime(date);
        return DATATYPES.newXMLGregorianCalendarDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                DatatypeConstants.FIELD_UNDEFINED);
    }

    private static DatatypeFactory datatypeFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException unavailable) {
            throw new IllegalStateException(
                    "no JAXP DatatypeFactory on the classpath", unavailable);
        }
    }
}
