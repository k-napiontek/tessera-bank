package bank.tessera.customer.endpoint;

import bank.tessera.customer.dao.AccountDao;
import bank.tessera.customer.domain.Money;
import bank.tessera.customer.domain.ServiceFaultException;
import bank.tessera.customer.ws.CustomerMasterPortType;
import bank.tessera.customer.ws.Movement;
import bank.tessera.customer.ws.ServiceFault;
import bank.tessera.customer.ws.ServiceFaultMessage;
import bank.tessera.customer.ws.Transfer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.jws.WebService;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.ws.Holder;

/**
 * The SOAP face of the customer master.
 *
 * <p>Implements {@link CustomerMasterPortType}, which is <em>generated</em> from
 * {@code contracts/wsdl/customer-master-v1.wsdl}. That direction is the whole discipline of this
 * tier: the contract is authored, the interface follows from it, and this class cannot compile
 * unless it answers exactly the operations the contract declares. A WSDL produced by reflecting over
 * Java classes is not a contract - it is a description of whatever the code happened to be that day.
 *
 * <p>This layer holds no business logic. It translates, and the translation runs in one direction
 * each way: a request becomes DAO arguments, a result becomes canonical types, and a
 * {@link ServiceFaultException} becomes the fault the WSDL declares. Everything that decides
 * anything is in PL/SQL, where a 2011 team put it.
 */
@WebService(
        serviceName = "CustomerMasterService",
        portName = "CustomerMasterPort",
        targetNamespace = "http://services.tesserabank.example/customer-master/v1",
        endpointInterface = "bank.tessera.customer.ws.CustomerMasterPortType",
        // The authored contract, carried in the WAR and published verbatim. Drop this and the RI
        // publishes a WSDL it derived from these annotations instead - a description of the code
        // rather than the contract the code was generated from.
        wsdlLocation = "WEB-INF/wsdl/wsdl/customer-master-v1.wsdl")
public class CustomerMasterEndpoint implements CustomerMasterPortType {

    /**
     * The container supplies the DataSource, which is how a WAR got one before dependency injection
     * was something a bank had heard of. Declared as a resource-ref in web.xml and bound by the
     * container to whatever the environment's database happens to be.
     *
     * <p>Not private: DeploymentDescriptorTest holds this string and the resource-ref in web.xml to
     * the same value, because the two drifting apart is a deployment that fails at the first
     * request with a message naming neither of them.
     */
    static final String DATA_SOURCE_NAME = "java:comp/env/jdbc/customerMaster";

    private final AccountDao accounts;

    /**
     * The constructor JAX-WS calls. The lookup happens here rather than on the first request, so a
     * WAR deployed against a missing or misnamed resource fails at deployment - loudly, in front of
     * whoever deployed it - instead of at 09:00 in front of a customer.
     */
    public CustomerMasterEndpoint() {
        this(new AccountDao(dataSourceFromContainer()));
    }

    /** For tests, which supply a DataSource onto a database they control. */
    CustomerMasterEndpoint(AccountDao accounts) {
        if (accounts == null) {
            throw new IllegalArgumentException("an AccountDao is required");
        }
        this.accounts = accounts;
    }

    public bank.tessera.customer.ws.Account getAccount(String accountRef)
            throws ServiceFaultMessage {
        try {
            return AccountMapper.toContract(accounts.findAccount(accountRef));
        } catch (ServiceFaultException business) {
            throw faultFor(business);
        }
    }

    public List<bank.tessera.customer.ws.Account> getAccountsByCustomer(String customerRef)
            throws ServiceFaultMessage {
        try {
            List<bank.tessera.customer.domain.Account> held =
                    accounts.findAccountsByCustomer(customerRef);

            // A new list, never a null and never a list holding a null. minOccurs="0" on the
            // response element permits no account elements at all; it does not permit one empty
            // element, and a naive mapping produces exactly that.
            List<bank.tessera.customer.ws.Account> answer =
                    new ArrayList<bank.tessera.customer.ws.Account>(held.size());
            for (int i = 0; i < held.size(); i++) {
                answer.add(AccountMapper.toContract(held.get(i)));
            }
            return answer;
        } catch (ServiceFaultException business) {
            throw faultFor(business);
        }
    }

    /**
     * Records a posting the ledger has already made.
     *
     * <p>Idempotent on transferRef, because upstream delivery is at-least-once and the WSDL says so.
     * The idempotency is the database's - PKG_POSTING claims the transfer with an INSERT and catches
     * the duplicate-key violation - not a read followed by a write here, which two simultaneous
     * deliveries defeat.
     *
     * <p>The account's status is deliberately not consulted. This operation reports a movement that
     * has already happened; refusing it does not unmake the movement, it only leaves this master
     * permanently wrong about that account and hides the disagreement from batch/recon by never
     * recording it. A block belongs before a payment, where it can still prevent one.
     */
    public void notifyTransferPosted(Transfer transfer, List<Movement> movement,
            Holder<String> transferRef, Holder<Boolean> alreadyApplied)
            throws ServiceFaultMessage {
        if (transfer == null) {
            throw new IllegalArgumentException("a transfer is required");
        }
        // Exactly two, because a posting has two legs. The schema enforces it on the wire; this
        // says the same thing to a caller that arrived some other way. It is a malformed message
        // rather than a business fault, so it is not dressed up as one - inventing a fault code
        // would put a word on the wire that no contract declares.
        if (movement == null || movement.size() != 2) {
            throw new IllegalArgumentException(
                    "a posted transfer carries exactly two movements, not "
                            + (movement == null ? "none" : String.valueOf(movement.size())));
        }

        try {
            boolean applied = accounts.applyTransfer(
                    transfer.getTransferRef(),
                    transfer.getCorrelationId(),
                    transfer.getDebitAccountRef(),
                    transfer.getCreditAccountRef(),
                    new Money(transfer.getAmount().getAmountMinor(),
                            transfer.getAmount().getCurrency()),
                    // The value date is on the movements, not on the transfer: a transfer is
                    // requested and posted at instants, while the date the money counts from is a
                    // property of each leg. Both legs of one posting carry the same one.
                    valueDateOf(movement));

            transferRef.value = transfer.getTransferRef();
            alreadyApplied.value = Boolean.valueOf(applied);
        } catch (ServiceFaultException business) {
            throw faultFor(business);
        }
    }

    /**
     * An xs:date to a java.util.Date, at midnight in the JVM's own zone - which is where
     * {@code java.sql.Date} puts it too, so the calendar date that reaches the column is the one
     * that arrived on the wire.
     */
    private static Date valueDateOf(List<Movement> movement) {
        XMLGregorianCalendar valueDate = movement.get(0).getValueDate();
        if (valueDate == null) {
            throw new IllegalArgumentException("a movement carries no value date");
        }
        return valueDate.toGregorianCalendar().getTime();
    }

    /**
     * A business fault, carrying the code the contract puts on the wire.
     *
     * <p>Only a {@link ServiceFaultException} becomes one of these. A technical failure -
     * {@code DataAccessException}, a broken connection - is deliberately left to propagate and
     * become a SOAP server fault, because the WSDL says this fault element is for business faults
     * and a caller that cannot tell "your request was wrong" from "we are broken" retries the first
     * and gives up on the second.
     *
     * <p>correlationId is left absent. It is optional in the schema and no operation in this WSDL
     * carries one inbound, so filling it would mean inventing an id that correlates nothing.
     */
    private static ServiceFaultMessage faultFor(ServiceFaultException business) {
        ServiceFault detail = new ServiceFault();
        detail.setFaultCode(business.getFaultCode());
        detail.setFaultMessage(business.getMessage());
        return new ServiceFaultMessage(business.getMessage(), detail);
    }

    private static DataSource dataSourceFromContainer() {
        try {
            return (DataSource) new InitialContext().lookup(DATA_SOURCE_NAME);
        } catch (NamingException unbound) {
            throw new IllegalStateException(
                    "no DataSource bound at " + DATA_SOURCE_NAME
                            + " - the container has not been given one, or web.xml declares a"
                            + " different resource-ref name", unbound);
        }
    }
}
