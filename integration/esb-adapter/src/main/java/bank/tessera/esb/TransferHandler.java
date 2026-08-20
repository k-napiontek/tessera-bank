package bank.tessera.esb;

/**
 * What this component does with one transfer-posted message.
 *
 * <p>A port, so that the listener can be tested for what it does with success and with each kind of
 * failure without a SOAP endpoint, an XSLT processor or a mainframe file being involved. The real
 * implementation arrives in task 7 and grows a second hop in WP-11b.
 */
public interface TransferHandler {

    /**
     * @param payload the message body exactly as it arrived, unparsed
     * @throws TransferHandlingException when the transfer could not be carried across
     */
    void handle(String payload);
}
