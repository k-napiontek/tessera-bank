package bank.tessera.esb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

/**
 * 2019 becoming 2011: the ledger's JSON event as the estate's canonical XML.
 *
 * <p>Three steps, and the middle one is the only one that decides anything:
 *
 * <ol>
 * <li>The JSON becomes an intermediate XML document, mechanically. XSLT consumes XML and the event
 * is JSON, so something has to bridge them - and it is Jackson rather than a StringBuilder, because
 * a hand-assembled document is one that escapes its own inputs incorrectly exactly once.</li>
 * <li>An <strong>XSLT file</strong> maps that document to {@code tb:canonicalTransfer}. A
 * transformation between two contracts is itself a contract artefact: readable by somebody who does
 * not write Java, reviewable on its own, and diffable when either side moves.</li>
 * <li>The result is <strong>validated against {@code canonical-v1.xsd}</strong> before it goes
 * anywhere. This is the step that earns its place: a stylesheet that drops one element produces a
 * document which looks entirely plausible, and the far end would reject it with a message about
 * whichever element it noticed first.</li>
 * </ol>
 *
 * <p>The stylesheet is compiled once. {@link Templates} is thread-safe and a {@link Transformer} is
 * not, which is the trap in every naive XSLT service: it works under test and produces interleaved
 * garbage under load.
 */
public class CanonicalTransformer {

    private static final String STYLESHEET = "/xslt/transfer-posted-to-canonical.xsl";

    private final ObjectMapper json = new ObjectMapper();
    private final XmlMapper xml = new XmlMapper();
    private final Templates stylesheet;
    private final Schema canonicalSchema;

    public CanonicalTransformer(File canonicalXsd) {
        this.stylesheet = compile();
        this.canonicalSchema = schemaFrom(canonicalXsd);
    }

    /**
     * @throws TransferHandlingException permanently, at stage TRANSFORM, when the event cannot be
     *     read or the result does not satisfy the canonical schema. Both are properties of the
     *     message rather than of the moment, so retrying would fail identically.
     */
    public String toCanonicalXml(String eventJson) {
        String intermediate = intermediateXmlOf(eventJson);
        String canonical = transform(intermediate);
        validate(canonical, intermediate);
        return canonical;
    }

    private String intermediateXmlOf(String eventJson) {
        try {
            JsonNode event = json.readTree(eventJson);
            if (!event.isObject()) {
                throw TransferHandlingException.permanent(FailureStage.DEQUEUE,
                        "the message is not a JSON object");
            }
            return xml.writer().withRootName("event").writeValueAsString(event);
        } catch (TransferHandlingException already) {
            throw already;
        } catch (Exception unreadable) {
            throw TransferHandlingException.permanent(FailureStage.DEQUEUE,
                    "the message is not readable as JSON: " + unreadable.getMessage());
        }
    }

    private String transform(String intermediate) {
        try {
            Transformer transformer = stylesheet.newTransformer();
            StringWriter out = new StringWriter();
            transformer.transform(new StreamSource(new StringReader(intermediate)),
                    new StreamResult(out));
            return out.toString();
        } catch (Exception untransformable) {
            throw TransferHandlingException.permanent(FailureStage.TRANSFORM,
                    "the stylesheet could not transform the event: "
                            + untransformable.getMessage());
        }
    }

    private void validate(String canonical, String intermediate) {
        try {
            Validator validator = canonicalSchema.newValidator();
            validator.validate(new StreamSource(new StringReader(canonical)));
        } catch (Exception invalid) {
            // The intermediate is named in the message and not included in it. Which field was
            // missing is the useful part; the document itself can carry a remittance reference, and
            // this message reaches a dead-letter topic that is retained and widely readable.
            throw TransferHandlingException.permanent(FailureStage.TRANSFORM,
                    "the transformed document does not satisfy canonical-v1.xsd: "
                            + invalid.getMessage()
                            + " (intermediate was " + intermediate.length() + " bytes)");
        }
    }

    private Templates compile() {
        InputStream source = CanonicalTransformer.class.getResourceAsStream(STYLESHEET);
        if (source == null) {
            throw new IllegalStateException("no stylesheet on the classpath at " + STYLESHEET);
        }
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            // A stylesheet is code. This one is ours and on our classpath, but the factory is told
            // not to reach outside the document regardless - a transformer that resolves external
            // entities is an SSRF away from being interesting.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newTemplates(new StreamSource(source));
        } catch (Exception broken) {
            throw new IllegalStateException("the stylesheet at " + STYLESHEET
                    + " does not compile", broken);
        }
    }

    private static Schema schemaFrom(File canonicalXsd) {
        if (canonicalXsd == null || !canonicalXsd.isFile()) {
            throw new IllegalStateException("no canonical schema at "
                    + (canonicalXsd == null ? "null" : canonicalXsd.getAbsolutePath()));
        }
        try {
            return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                    .newSchema(canonicalXsd);
        } catch (Exception unreadable) {
            throw new IllegalStateException("cannot read " + canonicalXsd.getAbsolutePath(),
                    unreadable);
        }
    }

    /** Visible for the test that asserts what the JSON actually looks like as XML. */
    String intermediateFor(String eventJson) {
        return intermediateXmlOf(eventJson);
    }
}
