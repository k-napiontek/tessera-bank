package bank.tessera.customer.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bank.tessera.customer.contract.ContractSchema;
import bank.tessera.customer.data.SyntheticAccount;
import bank.tessera.customer.data.SyntheticCustomer;
import bank.tessera.customer.data.SyntheticData;
import bank.tessera.customer.db.OracleSupport;
import bank.tessera.customer.ws.CustomerMasterPortType;
import bank.tessera.customer.ws.CustomerMasterService;
import bank.tessera.customer.ws.DirectionType;
import bank.tessera.customer.ws.MoneyType;
import bank.tessera.customer.ws.Movement;
import bank.tessera.customer.ws.ServiceFaultMessage;
import bank.tessera.customer.ws.Transfer;
import bank.tessera.customer.ws.TransferStatusType;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.ws.Holder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The WAR, really deployed to a real Tomcat 8.5, called over real HTTP by the generated client.
 *
 * <p>Everything else in this module tests the endpoint as an object. That leaves a gap the width of
 * a deployment: a listener class that is not on the classpath, a JNDI name nothing binds, a
 * jaxws-rt whose API disagrees with the one JDK 8 keeps in rt.jar. None of those fail a compile and
 * none of them fail a unit test - they fail at deployment, with a stack trace in catalina.out that
 * names none of the four files that had to agree.
 *
 * <p>It also closes the loop the WSDL-first discipline is for. The client here is generated from
 * the authored contract; the server implements an interface generated from the same document. If
 * the two had drifted, this is where a marshalling error would say so.
 */
public class CustomerMasterDeploymentIT {

    private static final String SERVICE_NS =
            "http://services.tesserabank.example/customer-master/v1";

    private static DatatypeFactory datatypes;
    private static TomcatUnderTest tomcat;
    private static CustomerMasterPortType client;
    private static Connection connection;
    private static List<SyntheticCustomer> customers;
    private static List<SyntheticAccount> accounts;

    private static int transferSequence;

    @BeforeClass
    public static void deployTheWarAgainstRealOracle() throws Exception {
        datatypes = DatatypeFactory.newInstance();

        connection = OracleSupport.freshSchema();
        customers = SyntheticData.customers(20, 2026L);
        accounts = SyntheticData.accountsFor(customers, 2026L);
        SyntheticData.seed(connection, customers, accounts);

        tomcat = TomcatUnderTest.deploy(
                new File(required("tessera.war")),
                OracleSupport.jdbcUrl(),
                OracleSupport.username(),
                OracleSupport.password(),
                "jdbc/customerMaster",
                driverJar(),
                new File(required("tessera.tomcat.work")));

        // Built from the WSDL the container publishes, so the port it returns already points at the
        // running endpoint. A client that had to be told the address separately would not be proving
        // that the published address is right.
        CustomerMasterService service = new CustomerMasterService(
                tomcat.publishedWsdl(), new QName(SERVICE_NS, "CustomerMasterService"));
        client = service.getCustomerMasterPort();
    }

    @AfterClass
    public static void stopEverything() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void getAccountAnswersOverHttp() throws Exception {
        SyntheticAccount expected = accounts.get(0);

        bank.tessera.customer.ws.Account answer = client.getAccount(expected.accountRef());

        assertEquals(expected.accountRef(), answer.getAccountRef());
        assertEquals(expected.customerRef(), answer.getCustomerRef());
        assertEquals(expected.bookedBalanceMinor(), answer.getBookedBalance().getAmountMinor());
        assertEquals("availableBalance is mandatory and must survive the round trip",
                expected.bookedBalanceMinor(), answer.getAvailableBalance().getAmountMinor());
        assertNotNull(answer.getOpenedDate());
    }

    @Test
    public void getAccountsByCustomerAnswersOverHttp() throws Exception {
        String customerRef = aCustomerHoldingAccounts();

        List<bank.tessera.customer.ws.Account> answer = client.getAccountsByCustomer(customerRef);

        assertTrue("the customer holds accounts and none came back", answer.size() > 0);
        for (int i = 0; i < answer.size(); i++) {
            assertEquals(customerRef, answer.get(i).getCustomerRef());
        }
    }

    @Test
    public void anUnknownCustomerIsAnEmptyListOverHttpToo() throws Exception {
        List<bank.tessera.customer.ws.Account> answer =
                client.getAccountsByCustomer("CU9999999999");

        assertNotNull(answer);
        assertEquals(0, answer.size());
    }

    @Test
    public void anUnknownAccountArrivesAsTheFaultTheContractDeclares() throws Exception {
        try {
            client.getAccount("TB99999999999999");
            fail("an unknown account was answered rather than faulted");
        } catch (ServiceFaultMessage fault) {
            assertEquals("ACCT_NOT_FOUND", fault.getFaultInfo().getFaultCode());
            assertNotNull(fault.getFaultInfo().getFaultMessage());
        }
    }

    @Test
    public void notifyTransferPostedIsIdempotentOverHttp() throws Exception {
        String[] pair = twoAccountsInOneCurrency();
        String debit = pair[0];
        String credit = pair[1];
        String currency = pair[2];
        String reference = nextTransferRef();
        long amount = 42_00L;

        long debitBefore = client.getAccount(debit).getBookedBalance().getAmountMinor();

        Holder<String> firstRef = new Holder<String>();
        Holder<Boolean> firstApplied = new Holder<Boolean>();
        client.notifyTransferPosted(transfer(reference, debit, credit, amount, currency),
                movements(reference, debit, credit, amount, currency), firstRef, firstApplied);

        Holder<String> secondRef = new Holder<String>();
        Holder<Boolean> secondApplied = new Holder<Boolean>();
        client.notifyTransferPosted(transfer(reference, debit, credit, amount, currency),
                movements(reference, debit, credit, amount, currency), secondRef, secondApplied);

        assertEquals(reference, firstRef.value);
        assertEquals(Boolean.FALSE, firstApplied.value);
        assertEquals(Boolean.TRUE, secondApplied.value);
        assertEquals("the redelivery moved money a second time", debitBefore - amount,
                client.getAccount(debit).getBookedBalance().getAmountMinor());
    }

    /**
     * The assertion this whole test exists for: what a consumer fetches from the running container
     * is the document that was authored, not one derived from the Java.
     *
     * <p>The prose is what makes that checkable. {@code wsdl:documentation} exists only in a
     * document a person wrote - a WSDL the RI generates by reflecting over the implementation class
     * has the same operations, the same namespace and the same port name, and none of the
     * sentences. So the check is that the authored prose survives to the wire, together with the
     * structure a consumer generates a client from.
     *
     * <p>Two things were established by mutation and are worth writing down, because both are
     * counter-intuitive:
     *
     * <ul>
     * <li>Removing the contract from the WAR does not produce a reflected WSDL. It produces no
     * endpoint at all - the deployment fails and this class cannot even reach {@code ?wsdl}. The
     * WAR carrying the authored contract is therefore a precondition of serving anything, which is
     * a stronger guarantee than the one originally claimed here.</li>
     * <li>Removing <em>both</em> the {@code wsdl} attribute in sun-jaxws.xml and
     * {@code wsdlLocation} on the endpoint changes nothing: the RI discovers the single document
     * under {@code WEB-INF/wsdl} by itself. The two declarations are belt and braces over an
     * automatic behaviour, not the mechanism. They earn their place by being explicit - and by
     * DeploymentDescriptorTest holding them to each other - rather than by being load-bearing.</li>
     * </ul>
     */
    @Test
    public void theContainerPublishesTheAuthoredContract() throws Exception {
        String published = fetch(tomcat.publishedWsdl());
        String authored = read(ContractSchema.wsdlFile());

        String[] mustSurvive = {
            "Account metadata by reference. Faults with ACCT_NOT_FOUND if unknown.",
            "Every account held by one customer. An empty list is a valid answer, not a fault.",
            "Idempotent on transferRef",
            "CustomerMasterPortType",
            "CustomerMasterSoapBinding",
            "CustomerMasterService",
            "CustomerMasterPort",
            "http://services.tesserabank.example/customer-master/v1/GetAccount",
            "http://services.tesserabank.example/customer-master/v1/GetAccountsByCustomer",
            "http://services.tesserabank.example/customer-master/v1/NotifyTransferPosted",
        };
        for (int i = 0; i < mustSurvive.length; i++) {
            assertTrue("the published WSDL does not carry \"" + mustSurvive[i]
                    + "\" - the container is publishing a document derived from the Java rather"
                    + " than the contract in contracts/wsdl/",
                    published.contains(mustSurvive[i]));
            assertTrue("the authored WSDL no longer carries \"" + mustSurvive[i]
                    + "\", so this test is comparing against the wrong thing",
                    authored.contains(mustSurvive[i]));
        }

        // The one thing that is SUPPOSED to differ. The RI rewrites the address to wherever it is
        // actually deployed, which is the correct behaviour: an endpoint address is a property of a
        // deployment and the contract says so where the port is declared.
        assertTrue("the published address was not rewritten to the running endpoint",
                published.contains("localhost:"));
        assertTrue("the authored WSDL should carry the development address",
                authored.contains("http://localhost:8080/customer-master"));
    }

    /**
     * The imported schema is published too, and it is the canonical one. A consumer that fetches
     * ?wsdl and follows the import has to arrive at the same types every other tier uses.
     */
    @Test
    public void theContainerPublishesTheCanonicalSchema() throws Exception {
        String published = fetch(tomcat.publishedSchema(1));

        assertTrue("the published schema is not the canonical one",
                published.contains("http://schemas.tesserabank.example/canonical/v1"));
        assertTrue(published.contains("name=\"Account\""));
        assertTrue(published.contains("name=\"MoneyType\""));
        assertTrue("the money documentation from the canonical model did not survive",
                published.contains("Signed count of minor units"));
    }

    private static String[] twoAccountsInOneCurrency() {
        for (int i = 0; i < accounts.size(); i++) {
            for (int j = i + 1; j < accounts.size(); j++) {
                if (accounts.get(i).currency().equals(accounts.get(j).currency())) {
                    return new String[] {accounts.get(i).accountRef(),
                        accounts.get(j).accountRef(), accounts.get(i).currency()};
                }
            }
        }
        throw new IllegalStateException("the fixture holds no two accounts in one currency");
    }

    private static String aCustomerHoldingAccounts() {
        for (int i = 0; i < accounts.size(); i++) {
            return accounts.get(i).customerRef();
        }
        throw new IllegalStateException("the fixture holds no accounts");
    }

    private static synchronized String nextTransferRef() {
        transferSequence++;
        return String.format("TB%018d", Integer.valueOf(900000 + transferSequence));
    }

    private static Transfer transfer(String transferRef, String debit, String credit,
            long amountMinor, String currency) {
        Transfer transfer = new Transfer();
        transfer.setTransferRef(transferRef);
        transfer.setDebitAccountRef(debit);
        transfer.setCreditAccountRef(credit);
        transfer.setAmount(money(amountMinor, currency));
        transfer.setStatus(TransferStatusType.POSTED);
        transfer.setRequestedAt(datatypes.newXMLGregorianCalendar(2026, 8, 20, 9, 30, 0, 0, 0));
        transfer.setPostedAt(datatypes.newXMLGregorianCalendar(2026, 8, 20, 9, 30, 1, 0, 0));
        transfer.setCorrelationId("11111111-2222-3333-4444-555555555555");
        return transfer;
    }

    private static List<Movement> movements(String transferRef, String debit, String credit,
            long amountMinor, String currency) {
        return new ArrayList<Movement>(Arrays.asList(
                movement(transferRef, 1, debit, DirectionType.DEBIT, amountMinor, currency),
                movement(transferRef, 2, credit, DirectionType.CREDIT, amountMinor, currency)));
    }

    private static Movement movement(String transferRef, int legNo, String accountRef,
            DirectionType direction, long amountMinor, String currency) {
        Movement movement = new Movement();
        movement.setMovementRef(transferRef + "-" + String.format("%02d", Integer.valueOf(legNo)));
        movement.setTransferRef(transferRef);
        movement.setLegNo(legNo);
        movement.setAccountRef(accountRef);
        movement.setDirection(direction);
        movement.setAmount(money(amountMinor, currency));
        movement.setValueDate(valueDate());
        movement.setPostedAt(datatypes.newXMLGregorianCalendar(2026, 8, 20, 9, 30, 1, 0, 0));
        return movement;
    }

    private static XMLGregorianCalendar valueDate() {
        return datatypes.newXMLGregorianCalendarDate(
                2026, 8, 20, DatatypeConstants.FIELD_UNDEFINED);
    }

    private static MoneyType money(long amountMinor, String currency) {
        MoneyType money = new MoneyType();
        money.setAmountMinor(amountMinor);
        money.setCurrency(currency);
        return money;
    }

    /**
     * ojdbc8 is a provided dependency: on this test's classpath, and deliberately not in the WAR.
     * Asking the loaded class where it came from beats hard-coding a path into the local Maven
     * repository, which differs per machine.
     */
    private static File driverJar() throws Exception {
        return new File(Class.forName("oracle.jdbc.OracleDriver")
                .getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.length() == 0) {
            throw new IllegalStateException(property
                    + " is not set - failsafe passes it, so this was run some other way");
        }
        return value;
    }

    private static String fetch(URL url) throws Exception {
        InputStream stream = url.openStream();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read = stream.read(buffer);
            while (read > 0) {
                bytes.write(buffer, 0, read);
                read = stream.read(buffer);
            }
            return new String(bytes.toByteArray(), "UTF-8");
        } finally {
            stream.close();
        }
    }

    private static String read(File file) throws Exception {
        return fetch(file.toURI().toURL());
    }
}
