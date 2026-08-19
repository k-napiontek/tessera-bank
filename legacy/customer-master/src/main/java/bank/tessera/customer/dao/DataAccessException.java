package bank.tessera.customer.dao;

/**
 * Something went wrong below the business logic.
 *
 * <p>Its message is fixed. An Oracle error can quote the bind values that caused it, and a bind
 * value in this component is a customer's data - so the driver's message is attached as the cause,
 * where it reaches a log the operations team reads, and never becomes the text of a SOAP fault.
 */
public class DataAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
