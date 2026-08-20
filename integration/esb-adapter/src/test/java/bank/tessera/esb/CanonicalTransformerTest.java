package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * The 2019-to-2011 hop, checked against the schema that decides whether it worked.
 *
 * <p>The important assertion is not that the fields arrive - it is that the result satisfies
 * {@code canonical-v1.xsd}. A stylesheet that drops one element, or emits a sequence in the wrong
 * order, produces a document that looks entirely plausible to a reader and is rejected by every
 * consumer. Reading the output and agreeing with it proves nothing; the schema is the arbiter.
 */
class CanonicalTransformerTest {

    private static final String CANONICAL_NS = "http://schemas.tesserabank.example/canonical/v1";

    private static CanonicalTransformer transformer;
    private static XPath xpath;

    @BeforeAll
    static void readTheContract() {
        File contracts = new File(System.getProperty("tessera.contracts.dir",
                ".." + File.separator + ".." + File.separator + "contracts"));
        transformer = new CanonicalTransformer(
                new File(contracts, "xsd" + File.separator + "canonical-v1.xsd"));

        xpath = XPathFactory.newInstance().newXPath();
    }

    @Test
    void aPostedTransferBecomesCanonicalXmlThatSatisfiesTheSchema() throws Exception {
        String canonical = transformer.toCanonicalXml(event());

        // Reaching here at all means it validated - the transformer refuses to return otherwise.
        Document document = parse(canonical);
        assertEquals("canonicalTransfer", document.getDocumentElement().getLocalName());
        assertEquals(CANONICAL_NS, document.getDocumentElement().getNamespaceURI());
    }

    @Test
    void carriesTheTransferTheEventDescribed() throws Exception {
        Document document = parse(transformer.toCanonicalXml(event()));

        assertEquals("TB000000000000000042", text(document, "transferRef"));
        assertEquals("TB00000000000001", text(document, "debitAccountRef"));
        assertEquals("TB00000000000002", text(document, "creditAccountRef"));
        assertEquals("123456789", text(document, "amountMinor"));
        assertEquals("PLN", text(document, "currency"));
        assertEquals("11111111-2222-3333-4444-555555555555", text(document, "correlationId"));
    }

    /** Derived from the fact of the message existing, which is the one thing it certainly means. */
    @Test
    void theStatusIsPostedBecauseThatIsWhatTheTopicMeans() throws Exception {
        assertEquals("POSTED", text(parse(transformer.toCanonicalXml(event())), "status"));
    }

    /**
     * The gap worth knowing about. tb:Transfer makes requestedAt mandatory and the ledger's event
     * carries no request timestamp at all, so this tier cannot know when the customer asked. It
     * sends postedAt - the tightest upper bound it can defend, since a transfer is requested no
     * later than it is posted - and that is a substitution rather than a fact.
     *
     * <p>Asserted rather than left implicit, so that the day the event gains a real requestedAt this
     * test fails and names the thing that has to change.
     */
    @Test
    void requestedAtIsPostedAtBecauseTheEventCarriesNoRequestTimestamp() throws Exception {
        Document document = parse(transformer.toCanonicalXml(event()));

        assertEquals(text(document, "postedAt"), text(document, "requestedAt"),
                "requestedAt is a substitution for postedAt - see the follow-up");
    }

    @Test
    void bothLegsArriveAsMovements() throws Exception {
        Document document = parse(transformer.toCanonicalXml(event()));

        NodeList movements = (NodeList) xpath.evaluate(
                "/*[local-name()='canonicalTransfer']/*[local-name()='movement']",
                document, XPathConstants.NODESET);

        assertEquals(2, movements.getLength(), "a posting has two legs and the schema requires both");
        assertEquals("1", first(document, "legNo"));
        assertEquals("DEBIT", first(document, "direction"));
    }

    @Test
    void anAbsentRemittanceReferenceIsOmittedRatherThanEmptied() throws Exception {
        String canonical = transformer.toCanonicalXml(eventWithout("\"reference\":\"SALARY\","));

        assertFalse(canonical.contains("reference"),
                "an absent optional element was emitted empty, which is a different document");
    }

    @Test
    void aReferenceThatIsPresentIsCarried() throws Exception {
        assertTrue(transformer.toCanonicalXml(event()).contains("SALARY"));
    }

    /**
     * The check that makes the whole step worth having. A transfer with no correlationId is exactly
     * what a producer one field short sends, and every assertion written against the Java object
     * would still pass.
     */
    @Test
    void anEventMissingAMandatoryFieldIsRefusedByTheSchema() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> transformer.toCanonicalXml(eventWithout(
                        ",\"correlationId\":\"11111111-2222-3333-4444-555555555555\"")));

        assertEquals(FailureStage.TRANSFORM, refused.stage());
        assertTrue(refused.isPermanent(),
                "a message that does not satisfy the schema will not satisfy it later either");
    }

    @Test
    void anAmountOutsideTheCanonicalPatternIsRefused() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> transformer.toCanonicalXml(
                        event().replace("\"PLN\"", "\"Polish zloty\"")));

        assertEquals(FailureStage.TRANSFORM, refused.stage());
    }

    @Test
    void aMessageThatIsNotJsonIsRefusedAtTheDequeueStage() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> transformer.toCanonicalXml("this is not JSON"));

        assertEquals(FailureStage.DEQUEUE, refused.stage());
        assertTrue(refused.isPermanent());
    }

    /**
     * The failure message must be diagnostic without being a disclosure. It reaches a dead-letter
     * topic that is retained and widely readable, and the payload can carry a remittance reference.
     */
    @Test
    void aFailureMessageNeverQuotesThePayload() {
        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> transformer.toCanonicalXml(eventWithout(
                        ",\"correlationId\":\"11111111-2222-3333-4444-555555555555\"")));

        assertFalse(refused.getMessage().contains("SALARY"),
                "the remittance reference reached a message bound for the dead-letter topic");
    }

    /**
     * The stylesheet matches an element called `movements`, which is Jackson's naming for a JSON
     * array field and not a choice anybody made. Pinned here so that if it ever changes the failure
     * says so, rather than arriving as "the schema expected two movements and found none".
     */
    @Test
    void theIntermediateDocumentIsWhatTheStylesheetExpects() {
        String intermediate = transformer.intermediateFor(event());

        assertTrue(intermediate.startsWith("<event>"), intermediate.substring(0, 40));
        assertEquals(2, countOf(intermediate, "<movements>"),
                "a JSON array became something other than repeated <movements> elements");
        assertTrue(intermediate.contains("<amountMinor>123456789</amountMinor>"));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static String text(Document document, String localName) throws Exception {
        return xpath.evaluate("//*[local-name()='" + localName + "'][1]", document);
    }

    private static String first(Document document, String localName) throws Exception {
        return xpath.evaluate(
                "/*[local-name()='canonicalTransfer']/*[local-name()='movement'][1]"
                        + "/*[local-name()='" + localName + "']", document);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String eventWithout(String fragment) {
        String event = event();
        assertTrue(event.contains(fragment), "the fixture does not contain " + fragment);
        return event.replace(fragment, "");
    }

    /** Shaped by contracts/asyncapi/ledger-events.yaml, which is what the ledger really sends. */
    private static String event() {
        return "{"
                + "\"transferRef\":\"TB000000000000000042\","
                + "\"debitAccountRef\":\"TB00000000000001\","
                + "\"creditAccountRef\":\"TB00000000000002\","
                + "\"amount\":{\"amountMinor\":123456789,\"currency\":\"PLN\"},"
                + "\"reference\":\"SALARY\","
                + "\"postedAt\":\"2026-08-20T09:30:00Z\","
                + "\"movements\":["
                + "{\"movementRef\":\"TB000000000000000042-01\","
                + "\"transferRef\":\"TB000000000000000042\",\"legNo\":1,"
                + "\"accountRef\":\"TB00000000000001\",\"direction\":\"DEBIT\","
                + "\"amount\":{\"amountMinor\":123456789,\"currency\":\"PLN\"},"
                + "\"valueDate\":\"2026-08-20\",\"postedAt\":\"2026-08-20T09:30:00Z\"},"
                + "{\"movementRef\":\"TB000000000000000042-02\","
                + "\"transferRef\":\"TB000000000000000042\",\"legNo\":2,"
                + "\"accountRef\":\"TB00000000000002\",\"direction\":\"CREDIT\","
                + "\"amount\":{\"amountMinor\":123456789,\"currency\":\"PLN\"},"
                + "\"valueDate\":\"2026-08-20\",\"postedAt\":\"2026-08-20T09:30:00Z\"}"
                + "],"
                + "\"correlationId\":\"11111111-2222-3333-4444-555555555555\""
                + "}";
    }
}
