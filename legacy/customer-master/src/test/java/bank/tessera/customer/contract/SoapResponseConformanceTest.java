package bank.tessera.customer.contract;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bank.tessera.customer.endpoint.AccountMapper;
import bank.tessera.customer.ws.Account;
import bank.tessera.customer.ws.AccountStatusType;
import bank.tessera.customer.ws.AccountTypeType;
import bank.tessera.customer.ws.GetAccountResponse;
import bank.tessera.customer.ws.GetAccountsByCustomerResponse;
import bank.tessera.customer.ws.MoneyType;
import bank.tessera.customer.ws.NotifyTransferPostedResponse;
import bank.tessera.customer.ws.ServiceFault;
import java.io.StringReader;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.io.StringWriter;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXException;

/**
 * Every SOAP response this component sends, validated against the contract that declares it.
 *
 * <p>This is the conformance check WP-02 wired into WP-10: a component may not be trusted to say
 * that it satisfies a schema, because the way this fails is that a mandatory element is quietly
 * absent and every test written against the Java objects still passes. The schema is the arbiter,
 * and it is read from {@code contracts/} rather than from a copy.
 *
 * <p>Traceability to {@code docs/architecture/canonical-data-model.md}:
 *
 * <table>
 * <caption>What each response carries and what the schema enforces about it</caption>
 * <tr><th>Contract element</th><th>Model</th><th>What the schema enforces</th></tr>
 * <tr><td>tb:Account/accountRef</td><td>&sect;1</td><td>TB + 14 upper alphanumerics, 16 long</td></tr>
 * <tr><td>tb:Account/customerRef</td><td>&sect;1</td><td>CU + 10 digits, 12 long</td></tr>
 * <tr><td>tb:MoneyType</td><td>&sect;2</td><td>a signed long of minor units and an ISO 4217 code, never a decimal</td></tr>
 * <tr><td>tb:Account/accountType</td><td>&sect;3</td><td>the five-value enumeration</td></tr>
 * <tr><td>tb:Account/status</td><td>&sect;3</td><td>OPEN, BLOCKED or CLOSED</td></tr>
 * <tr><td>tb:Account/availableBalance</td><td>&sect;3</td><td>mandatory - see ADR 0010 for what this tier can defend</td></tr>
 * <tr><td>tb:Account/lastMovementDate</td><td>&sect;3</td><td>the one optional element; absent, never empty</td></tr>
 * <tr><td>tns:GetAccountsByCustomerResponse</td><td>&sect;3</td><td>zero or more accounts; an empty answer is no elements</td></tr>
 * <tr><td>tns:ServiceFault</td><td>&sect;8</td><td>a code and a message; correlationId optional</td></tr>
 * </table>
 */
public class SoapResponseConformanceTest {

    private static final String SERVICE_NS =
            "http://services.tesserabank.example/customer-master/v1";

    private static Schema contract;
    private static JAXBContext jaxb;
    private static DatatypeFactory datatypes;

    @BeforeClass
    public static void readTheAuthoredContract() throws Exception {
        contract = ContractSchema.ofTheAuthoredContract();
        jaxb = JAXBContext.newInstance("bank.tessera.customer.ws");
        datatypes = DatatypeFactory.newInstance();
    }

    @Test
    public void theContractIsReadFromTheRepositoryAndNotFromACopy() {
        assertTrue("no WSDL at " + ContractSchema.wsdlFile().getAbsolutePath(),
                ContractSchema.wsdlFile().isFile());
        assertTrue("no canonical XSD at " + ContractSchema.canonicalXsdFile().getAbsolutePath(),
                ContractSchema.canonicalXsdFile().isFile());
        assertNotNull("the WSDL's inline schema did not resolve its import", contract);
    }

    @Test
    public void aGetAccountResponseValidates() throws Exception {
        GetAccountResponse response = new GetAccountResponse();
        response.setAccount(anAccount(true));

        assertValidates(marshal(response));
    }

    @Test
    public void anAccountThatHasNeverMovedValidatesWithTheElementAbsent() throws Exception {
        GetAccountResponse response = new GetAccountResponse();
        response.setAccount(anAccount(false));

        String xml = marshal(response);
        assertValidates(xml);
        assertTrue("an absent lastMovementDate was marshalled as an empty element,"
                + " which is not the same document", !xml.contains("lastMovementDate"));
    }

    /**
     * The case PR #39 kept back for this package. minOccurs="0" permits <em>no</em> account
     * elements; it does not permit one empty element, and a naive implementation that returns a
     * single null produces exactly that - which some parsers accept and others reject, so the estate
     * would work until the day it met the second kind.
     */
    @Test
    public void anEmptyAccountListValidatesAndCarriesNoAccountElements() throws Exception {
        GetAccountsByCustomerResponse response = new GetAccountsByCustomerResponse();

        String xml = marshal(response);
        assertValidates(xml);
        assertTrue("an empty result carries an account element: " + xml,
                !xml.contains("account"));
    }

    @Test
    public void aListOfAccountsValidates() throws Exception {
        GetAccountsByCustomerResponse response = new GetAccountsByCustomerResponse();
        response.getAccount().add(anAccount(true));
        response.getAccount().add(anAccount(false));

        assertValidates(marshal(response));
    }

    @Test
    public void aNotifyTransferPostedResponseValidates() throws Exception {
        NotifyTransferPostedResponse response = new NotifyTransferPostedResponse();
        response.setTransferRef("TB000000000000000042");
        response.setAlreadyApplied(true);

        assertValidates(marshal(response));
    }

    @Test
    public void aServiceFaultValidates() throws Exception {
        ServiceFault fault = new ServiceFault();
        fault.setFaultCode("ACCT_NOT_FOUND");
        fault.setFaultMessage("no such account");

        assertValidates(marshal(new JAXBElement<ServiceFault>(
                new QName(SERVICE_NS, "ServiceFault"), ServiceFault.class, fault)));
    }

    /**
     * The demonstration that the check has teeth, kept as a test rather than performed once by hand.
     * A currency-less account is exactly what a mapper that forgot one line produces, and every
     * assertion written against the Java object would still pass.
     */
    @Test
    public void anAccountMissingAMandatoryElementIsRefused() throws Exception {
        Account incomplete = anAccount(true);
        incomplete.setCurrency(null);

        GetAccountResponse response = new GetAccountResponse();
        response.setAccount(incomplete);

        try {
            assertValidates(marshal(response));
            fail("an account with no currency validated against the canonical schema");
        } catch (AssertionError expected) {
            assertTrue("the schema refused it for the wrong reason: " + expected.getMessage(),
                    expected.getMessage().contains("currency"));
        }
    }

    @Test
    public void anAccountRefThatBreaksTheCanonicalPatternIsRefused() throws Exception {
        Account wrong = anAccount(true);
        wrong.setAccountRef("NOT-AN-ACCOUNT-REF");

        GetAccountResponse response = new GetAccountResponse();
        response.setAccount(wrong);

        try {
            assertValidates(marshal(response));
            fail("an account reference outside the canonical pattern validated");
        } catch (AssertionError expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * The same check against what the production mapper actually builds, rather than against an
     * object this test assembled. Without this the suite would prove that a correctly-populated
     * Account validates - which nobody doubted - and prove nothing about the code that populates one.
     */
    @Test
    public void whatTheMapperProducesValidates() throws Exception {
        GregorianCalendar opened = new GregorianCalendar(2011, Calendar.MARCH, 14);

        bank.tessera.customer.domain.Account held = new bank.tessera.customer.domain.Account(
                "TB00000000000001", "CU0000000042", "LIABILITY", "OPEN",
                new bank.tessera.customer.domain.Money(123456789L, "PLN"),
                opened.getTime(), null);

        GetAccountResponse response = new GetAccountResponse();
        response.setAccount(AccountMapper.toContract(held));

        String xml = marshal(response);
        assertValidates(xml);
        assertTrue("the mapper emitted an empty lastMovementDate for an account that never moved",
                !xml.contains("lastMovementDate"));
    }

    private static void assertValidates(String xml) throws Exception {
        Validator validator = contract.newValidator();
        try {
            validator.validate(new StreamSource(new StringReader(xml)));
        } catch (SAXException invalid) {
            throw new AssertionError(
                    "the response does not validate against the authored contract: "
                            + invalid.getMessage() + "\n" + xml);
        }
    }

    private static String marshal(Object response) throws Exception {
        Marshaller marshaller = jaxb.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter written = new StringWriter();
        marshaller.marshal(response, written);
        return written.toString();
    }

    private static Account anAccount(boolean hasMoved) {
        Account account = new Account();
        account.setAccountRef("TB00000000000001");
        account.setCustomerRef("CU0000000042");
        account.setAccountType(AccountTypeType.LIABILITY);
        account.setCurrency("PLN");
        account.setStatus(AccountStatusType.OPEN);
        account.setBookedBalance(money(123456789L));
        account.setAvailableBalance(money(123456789L));
        account.setOpenedDate(datatypes.newXMLGregorianCalendarDate(
                2011, 3, 14, DatatypeConstants.FIELD_UNDEFINED));
        if (hasMoved) {
            account.setLastMovementDate(datatypes.newXMLGregorianCalendarDate(
                    2026, 8, 20, DatatypeConstants.FIELD_UNDEFINED));
        }
        return account;
    }

    private static MoneyType money(long amountMinor) {
        MoneyType money = new MoneyType();
        money.setAmountMinor(amountMinor);
        money.setCurrency("PLN");
        return money;
    }
}
