package bank.tessera.esb;

import bank.tessera.esb.ws.CustomerMasterPortType;
import bank.tessera.esb.ws.CustomerMasterService;
import bank.tessera.esb.ws.Movement;
import bank.tessera.esb.ws.ServiceFaultMessage;
import bank.tessera.esb.ws.Transfer;
import java.net.URL;
import java.util.List;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The call into 2011.
 *
 * <p>The port is generated from {@code customer-master-v1.wsdl} - the same document that component
 * generated its server interface from. Neither side decides what the interface is, which is the
 * whole argument for a contract, and the deployment test is where the two are made to meet.
 *
 * <p><strong>Everything here turns on separating a refusal from an outage.</strong> A
 * {@code ServiceFaultMessage} is the system of record saying no: the message is wrong, it will be
 * wrong on redelivery, and it belongs on the dead-letter channel. A {@code WebServiceException} is
 * usually the network, and re-presenting the identical bytes in a minute may well work. Getting
 * that backwards either discards a good payment or blocks the queue on a bad one.
 */
public class CustomerMasterClient {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerMasterClient.class);

    private static final String SERVICE_NS =
            "http://services.tesserabank.example/customer-master/v1";

    private final CustomerMasterPortType port;

    public CustomerMasterClient(URL wsdlLocation, String endpointAddress) {
        CustomerMasterService service =
                new CustomerMasterService(wsdlLocation, new QName(SERVICE_NS, "CustomerMasterService"));
        this.port = service.getCustomerMasterPort();

        // The address in a WSDL is a development default; the real one is a property of the
        // deployment, which is what the contract says where the port is declared.
        ((BindingProvider) port).getRequestContext()
                .put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpointAddress);
    }

    CustomerMasterClient(CustomerMasterPortType port) {
        this.port = port;
    }

    /**
     * Tells the system of record that the ledger posted this transfer.
     *
     * @return true when it had already been applied, which is success rather than an error:
     *     delivery is at-least-once, so a duplicate is an expected event. This is where the
     *     estate's idempotency actually lives - the far end claims the transfer with a unique
     *     constraint, and this component deliberately keeps no second record of its own.
     */
    public boolean notifyTransferPosted(Transfer transfer, List<Movement> movements) {
        Holder<String> transferRef = new Holder<>();
        Holder<Boolean> alreadyApplied = new Holder<>();

        try {
            port.notifyTransferPosted(transfer, movements, transferRef, alreadyApplied);
        } catch (ServiceFaultMessage refused) {
            // A business fault. The message named a code; it goes on the dead letter so an operator
            // sees which one without reading a stack trace.
            String faultCode = refused.getFaultInfo() == null ? null
                    : refused.getFaultInfo().getFaultCode();
            // The fault's own message is not repeated. customer-master scrubs it, but this
            // component cannot verify that, and whatever it says lands on a retained topic.
            throw TransferHandlingException.permanent(FailureStage.SOAP, faultCode,
                    "the system of record refused the transfer");
        } catch (WebServiceException unavailable) {
            throw TransferHandlingException.transient_(FailureStage.SOAP,
                    "the system of record could not be reached", unavailable);
        }

        boolean applied = Boolean.TRUE.equals(alreadyApplied.value);
        if (applied) {
            LOG.info("transfer {} had already been applied by the system of record",
                    transferRef.value);
        }
        return applied;
    }
}
