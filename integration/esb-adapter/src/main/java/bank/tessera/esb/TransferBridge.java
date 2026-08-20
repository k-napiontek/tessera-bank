package bank.tessera.esb;

import bank.tessera.esb.ws.CanonicalTransfer;
import bank.tessera.esb.ws.Movement;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Unmarshaller;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
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
 * <li><strong>Only then</strong>, the movement records are appended to the file tonight's COBOL
 * cycle will read.</li>
 * </ol>
 *
 * <p>Step 3 looks out of place in the half of this package that does not touch the mainframe, and it
 * is deliberate. The estate's rule since WP-03 is that the integration tier rejects such a movement
 * <em>before it arrives</em> and the mainframe validates it again - defence in depth, because a 1995
 * core does not trust its feeds. Refusing it after telling the system of record would leave the two
 * halves of the estate disagreeing about a transfer that was never going to make it.
 *
 * <p>Step 5's position in that list is the constraint the work package was split on: <strong>nothing
 * may be written to the movement file unless the SOAP call succeeded.</strong> A transfer the system
 * of record refused has not happened as far as 2011 is concerned, and a movement record for it would
 * tell 1995 otherwise - leaving the two halves of the estate permanently disagreeing about a payment
 * that never was, which is exactly the break {@code batch/recon} exists to find.
 *
 * <p>The reverse order is not symmetrical with it. If the file write fails after the SOAP call
 * succeeded, the transfer is redelivered, the far end answers {@code alreadyApplied}, and the writer
 * - which asks the file rather than the answer - completes what was missing.
 */
public class TransferBridge implements TransferHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TransferBridge.class);

    private final CanonicalTransformer transformer;
    private final CustomerMasterClient customerMaster;
    private final MovementFileWriter movementFile;
    private final JAXBContext canonicalTypes;

    public TransferBridge(CanonicalTransformer transformer, CustomerMasterClient customerMaster,
            MovementFileWriter movementFile) {
        this.transformer = transformer;
        this.customerMaster = customerMaster;
        this.movementFile = movementFile;
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

        String transferRef = canonical.getTransfer().getTransferRef();
        LOG.info("transfer {} carried to the system of record{}", transferRef,
                alreadyApplied ? " (already applied)" : "");

        boolean written = movementFile.append(transferRef,
                movementRecords(canonical, transferRef));

        LOG.info("transfer {} crossed to stratum 0{}", transferRef,
                written ? "" : " (already in the movement file)");
    }

    /**
     * The canonical movements as fixed-width records, in the order the document carries them.
     *
     * <p>No sorting. The copybook says the file is ascending by {@code MOV-ACCT-REF} and
     * {@code STEP010} of the overnight cycle is what puts it in that order - which is the entire
     * reason that step exists.
     */
    private List<byte[]> movementRecords(CanonicalTransfer canonical, String transferRef) {
        String reference = canonical.getTransfer().getReference();

        List<byte[]> records = new ArrayList<>(canonical.getMovement().size());
        for (Movement movement : canonical.getMovement()) {
            records.add(MovementRecord.of(
                    transferRef,
                    movement.getLegNo(),
                    movement.getAccountRef(),
                    movement.getDirection().value(),
                    movement.getAmount().getCurrency(),
                    movement.getAmount().getAmountMinor(),
                    date(movement.getValueDate()),
                    timestamp(movement.getPostedAt()),
                    movement.getReference() != null ? movement.getReference() : reference));
        }
        return records;
    }

    /**
     * {@code PIC 9(08)} as {@code YYYYMMDD}, and {@code PIC 9(14)} as {@code YYYYMMDDHHMMSS}.
     *
     * <p>Both are <strong>normalised to UTC</strong>. The canonical document carries an offset and a
     * 1995 domestic core has no concept of one, so something has to decide - and normalising is the
     * only choice that does not invent a local zone this component has no business knowing. The
     * estate's events are published in UTC, so nothing is actually shifted today; the normalisation
     * is there for the day something is.
     */
    private static String date(XMLGregorianCalendar value) {
        XMLGregorianCalendar on = utc(value);
        return String.format("%04d%02d%02d", on.getYear(), on.getMonth(), on.getDay());
    }

    private static String timestamp(XMLGregorianCalendar value) {
        XMLGregorianCalendar at = utc(value);
        return String.format("%04d%02d%02d%02d%02d%02d", at.getYear(), at.getMonth(), at.getDay(),
                at.getHour(), at.getMinute(), at.getSecond());
    }

    private static XMLGregorianCalendar utc(XMLGregorianCalendar value) {
        if (value.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
            // A date with no offset - which is what xs:date usually is. Nothing to normalise.
            return value;
        }
        return value.normalize();
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
