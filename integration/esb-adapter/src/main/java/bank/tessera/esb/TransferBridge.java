package bank.tessera.esb;

import bank.tessera.esb.ws.CanonicalTransfer;
import java.io.StringReader;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Unmarshaller;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One transfer, carried from 2019 to 2011.
 *
 * <p>The sequence is the package's whole claim, and each step is somebody else's contract:
 *
 * <ol>
 * <li>The event becomes canonical XML by XSLT, validated against {@code canonical-v1.xsd}.</li>
 * <li>That XML is unmarshalled into the types generated from the <em>WSDL</em> - which works
 * because the WSDL imports the same canonical schema. The document does not have to be translated
 * between the two contracts, because there is only one contract.</li>
 * <li>A currency the mainframe cannot represent is refused here, before the call.</li>
 * <li>The system of record is told.</li>
 * </ol>
 *
 * <p>Step 3 looks out of place in the half of this package that does not touch the mainframe, and it
 * is deliberate. The estate's rule since WP-03 is that the integration tier rejects such a movement
 * <em>before it arrives</em> and the mainframe validates it again - defence in depth, because a 1995
 * core does not trust its feeds. Refusing it after telling the system of record would leave the two
 * halves of the estate disagreeing about a transfer that was never going to make it.
 *
 * <p>WP-11b adds the last hop after this one: the movement file, which is written only if the call
 * below succeeded.
 */
public class TransferBridge implements TransferHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TransferBridge.class);

    private final CanonicalTransformer transformer;
    private final CustomerMasterClient customerMaster;
    private final JAXBContext canonicalTypes;

    public TransferBridge(CanonicalTransformer transformer, CustomerMasterClient customerMaster) {
        this.transformer = transformer;
        this.customerMaster = customerMaster;
        try {
            this.canonicalTypes = JAXBContext.newInstance(CanonicalTransfer.class);
        } catch (Exception impossible) {
            throw new IllegalStateException(
                    "the generated canonical types are not a JAXB context", impossible);
        }
    }

    @Override
    public void handle(String payload) {
        CanonicalTransfer canonical = unmarshal(transformer.toCanonicalXml(payload));

        String currency = canonical.getTransfer().getAmount().getCurrency();
        CurrencyScales.requireRepresentableOnTheMainframe(currency);

        boolean alreadyApplied = customerMaster.notifyTransferPosted(
                canonical.getTransfer(), canonical.getMovement());

        LOG.info("transfer {} carried to the system of record{}",
                canonical.getTransfer().getTransferRef(),
                alreadyApplied ? " (already applied)" : "");
    }

    /**
     * The canonical XML into the WSDL's own generated types.
     *
     * <p>Read through a StAX reader with DTDs and external entities switched off. The document was
     * produced by this component moments ago, which is exactly the reasoning that makes XXE
     * interesting: a parser configured for trusted input is one refactor away from being pointed at
     * untrusted input.
     */
    private CanonicalTransfer unmarshal(String canonicalXml) {
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);

            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(canonicalXml));
            try {
                Unmarshaller unmarshaller = canonicalTypes.createUnmarshaller();
                JAXBElement<CanonicalTransfer> element =
                        unmarshaller.unmarshal(reader, CanonicalTransfer.class);
                return element.getValue();
            } finally {
                reader.close();
            }
        } catch (Exception unreadable) {
            // The document validated against the canonical schema one line earlier, so arriving
            // here means the generated types and the schema disagree - a build problem, not a
            // message problem, and permanent either way.
            throw TransferHandlingException.permanent(FailureStage.TRANSFORM,
                    "the canonical document could not be read into the generated types: "
                            + unreadable.getMessage());
        }
    }
}
