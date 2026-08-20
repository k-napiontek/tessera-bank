package bank.tessera.customer.endpoint;

import bank.tessera.customer.dao.AccountDao;
import bank.tessera.customer.domain.ServiceFaultException;
import bank.tessera.customer.ws.CustomerMasterPortType;
import bank.tessera.customer.ws.Movement;
import bank.tessera.customer.ws.ServiceFault;
import bank.tessera.customer.ws.ServiceFaultMessage;
import bank.tessera.customer.ws.Transfer;
import java.util.ArrayList;
import java.util.List;
import javax.jws.WebService;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
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
        endpointInterface = "bank.tessera.customer.ws.CustomerMasterPortType")
public class CustomerMasterEndpoint implements CustomerMasterPortType {

    /**
     * The container supplies the DataSource, which is how a WAR got one before dependency injection
     * was something a bank had heard of. Declared as a resource-ref in web.xml and bound by the
     * container to whatever the environment's database happens to be.
     */
    private static final String DATA_SOURCE_NAME = "java:comp/env/jdbc/customerMaster";

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

    public void notifyTransferPosted(Transfer transfer, List<Movement> movement,
            Holder<String> transferRef, Holder<Boolean> alreadyApplied)
            throws ServiceFaultMessage {
        // WP-10b task 4 applies the posting. Declared here because the generated interface declares
        // it, and an interface this class does not fully implement does not compile.
        throw new UnsupportedOperationException("NotifyTransferPosted is not implemented yet");
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
