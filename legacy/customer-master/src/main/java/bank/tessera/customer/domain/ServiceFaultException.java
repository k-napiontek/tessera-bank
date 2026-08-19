package bank.tessera.customer.domain;

/**
 * A business fault, carrying the code the WSDL puts on the wire.
 *
 * <p>The code is decided in PL/SQL and travels as an Oracle error number, so the mapping here is a
 * lookup rather than a judgement made by reading an error message. A layer that inferred
 * ACCT_NOT_FOUND from the text of an exception would start reporting it for a dropped connection
 * the day Oracle reworded something.
 *
 * <p>The message must never carry personal data. An error path is the second most common place it
 * escapes a system, which is why the WSDL says so where the fault is defined.
 */
public class ServiceFaultException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String faultCode;

    public ServiceFaultException(String faultCode, String message) {
        super(message);
        this.faultCode = faultCode;
    }

    public String getFaultCode() {
        return faultCode;
    }
}
