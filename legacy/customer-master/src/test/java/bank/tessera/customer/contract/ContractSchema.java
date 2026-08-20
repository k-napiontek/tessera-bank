package bank.tessera.customer.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * A validator built from the contract as authored, not from a copy of it.
 *
 * <p>The awkward part is that a SOAP response body is <em>two</em> schemas. The wrapper elements -
 * GetAccountResponse and the rest - are declared inline in the WSDL's own {@code wsdl:types}, and
 * the business types they contain come from {@code contracts/xsd/canonical-v1.xsd}, which that
 * inline schema imports by the relative path {@code ../xsd/canonical-v1.xsd}. Validating a response
 * against the canonical schema alone would check the Account and skip the envelope around it.
 *
 * <p>So the inline schema is lifted out of the WSDL and handed to the schema factory with the
 * WSDL's own location as its base URI, which is what makes the relative import resolve to the real
 * file. The namespace declarations are copied down from the ancestors first: they are declared on
 * {@code wsdl:definitions}, and a schema element that has lost the binding for {@code tb:} cannot
 * resolve a single one of its own type references.
 *
 * <p>Nothing here is written to disk and nothing is cached in the repository. The one source of
 * truth stays {@code contracts/}.
 */
public final class ContractSchema {

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";

    private ContractSchema() {
    }

    public static File wsdlFile() {
        return new File(contractsDirectory(), "wsdl" + File.separator + "customer-master-v1.wsdl");
    }

    public static File canonicalXsdFile() {
        return new File(contractsDirectory(), "xsd" + File.separator + "canonical-v1.xsd");
    }

    public static File contractsDirectory() {
        return new File(System.getProperty("tessera.contracts.dir",
                ".." + File.separator + ".." + File.separator + "contracts"));
    }

    /**
     * The schema every SOAP response this component sends must validate against: the WSDL's own
     * wrapper declarations plus the canonical types they import.
     */
    public static Schema ofTheAuthoredContract() {
        try {
            File wsdl = wsdlFile();
            Element inlineSchema = inlineSchemaOf(parse(wsdl));

            SchemaFactory factory =
                    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            StreamSource source = new StreamSource(
                    new ByteArrayInputStream(serialise(inlineSchema)),
                    // The base URI, and the reason ../xsd/canonical-v1.xsd resolves at all.
                    wsdl.toURI().toString());
            return factory.newSchema(source);
        } catch (Exception unreadable) {
            throw new IllegalStateException(
                    "cannot build a schema from " + wsdlFile().getAbsolutePath(), unreadable);
        }
    }

    private static Document parse(File wsdl) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(wsdl);
    }

    private static Element inlineSchemaOf(Document wsdl) {
        Element types = firstChild(wsdl.getDocumentElement(), WSDL_NS, "types");
        Element schema = firstChild(types, XSD_NS, "schema");
        if (schema == null) {
            throw new IllegalStateException("the WSDL declares no inline schema");
        }
        return schema;
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        Node child = parent == null ? null : parent.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && namespace.equals(child.getNamespaceURI())
                    && localName.equals(child.getLocalName())) {
                return (Element) child;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    /**
     * Serialises the inline schema on its own, carrying every namespace declaration that was in
     * scope where it sat. Without this the prefixes in {@code type="tb:Account"} bind to nothing.
     */
    private static byte[] serialise(Element inlineSchema) throws Exception {
        Element detached = (Element) inlineSchema.cloneNode(true);
        Document document = inlineSchema.getOwnerDocument().getImplementation()
                .createDocument(null, null, null);
        detached = (Element) document.importNode(detached, true);
        copyNamespaceDeclarations(inlineSchema.getParentNode(), detached);
        document.appendChild(detached);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.transform(new DOMSource(document), new StreamResult(bytes));
        return bytes.toByteArray();
    }

    private static void copyNamespaceDeclarations(Node from, Element onto) {
        if (from == null || from.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }
        // Outermost first, so a declaration nearer the schema element wins if the two disagree.
        copyNamespaceDeclarations(from.getParentNode(), onto);

        NamedNodeMap attributes = from.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName();
            if (name.equals("xmlns") || name.startsWith("xmlns:")) {
                if (!onto.hasAttribute(name)) {
                    onto.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, name,
                            attribute.getNodeValue());
                }
            }
        }
    }
}
