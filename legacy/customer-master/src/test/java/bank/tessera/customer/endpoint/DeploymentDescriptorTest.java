package bank.tessera.customer.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import bank.tessera.customer.contract.ContractSchema;
import bank.tessera.customer.ws.CustomerMasterPortType;
import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;

/**
 * Four documents have to agree before this WAR serves anything, and three of them are XML nobody
 * compiles.
 *
 * <p>The contract names an address. sun-jaxws.xml names a url-pattern, a service and a port.
 * web.xml maps a servlet and declares a resource-ref. The endpoint class looks a DataSource up by
 * name. Every one of those is a string, and a mismatch in any of them produces a deployment that
 * starts cleanly and then answers 404, or one that answers 500 at the first request with a message
 * naming neither the resource-ref nor the lookup.
 *
 * <p>None of that is caught by a compiler and none of it is caught by the operations tests, which
 * call the endpoint object directly. It is caught here, in milliseconds, before a container is
 * involved at all.
 */
public class DeploymentDescriptorTest {

    private static final String SERVICE_NS =
            "http://services.tesserabank.example/customer-master/v1";

    /** The WAR's context root, which is finalName in the POM. */
    private static final String CONTEXT_ROOT = "/customer-master";

    private static Document sunJaxws;
    private static Document webXml;
    private static Document wsdl;
    private static XPath xpath;

    @BeforeClass
    public static void readTheDescriptors() throws Exception {
        sunJaxws = parse(new File("src/main/webapp/WEB-INF/sun-jaxws.xml"));
        webXml = parse(new File("src/main/webapp/WEB-INF/web.xml"));
        wsdl = parse(ContractSchema.wsdlFile());
        xpath = XPathFactory.newInstance().newXPath();
    }

    @Test
    public void theEndpointIsThePortTypeTheContractDeclares() throws Exception {
        String implementation = text(sunJaxws, "//*[local-name()='endpoint']/@implementation");

        Class<?> endpoint = Class.forName(implementation);
        assertTrue(implementation + " does not implement the generated port type",
                CustomerMasterPortType.class.isAssignableFrom(endpoint));
    }

    @Test
    public void sunJaxwsNamesTheServiceAndPortTheContractDeclares() throws Exception {
        assertEquals("{" + SERVICE_NS + "}" + text(wsdl, "//*[local-name()='service']/@name"),
                text(sunJaxws, "//*[local-name()='endpoint']/@service"));
        assertEquals("{" + SERVICE_NS + "}" + text(wsdl, "//*[local-name()='port']/@name"),
                text(sunJaxws, "//*[local-name()='endpoint']/@port"));
    }

    /**
     * The address in the contract is not decoration. A consumer generates its client from this
     * document, and the port it gets points at the path written here.
     */
    @Test
    public void theEndpointIsPublishedAtThePathTheContractAdvertises() throws Exception {
        String address = text(wsdl, "//*[local-name()='address']/@location");
        String path = address.substring(address.indexOf(CONTEXT_ROOT) + CONTEXT_ROOT.length());

        assertEquals("sun-jaxws.xml publishes the endpoint somewhere the contract does not name",
                path, text(sunJaxws, "//*[local-name()='endpoint']/@url-pattern"));
        assertEquals("web.xml maps the servlet somewhere the contract does not name",
                path, text(webXml, "//*[local-name()='servlet-mapping']/*[local-name()='url-pattern']"));
    }

    @Test
    public void webXmlMapsTheServletThatSunJaxwsExpects() throws Exception {
        assertEquals("com.sun.xml.ws.transport.http.servlet.WSServletContextListener",
                text(webXml, "//*[local-name()='listener']/*[local-name()='listener-class']"));
        assertEquals("com.sun.xml.ws.transport.http.servlet.WSServlet",
                text(webXml, "//*[local-name()='servlet']/*[local-name()='servlet-class']"));
        assertEquals("the servlet-mapping names a servlet web.xml does not declare",
                text(webXml, "//*[local-name()='servlet']/*[local-name()='servlet-name']"),
                text(webXml, "//*[local-name()='servlet-mapping']/*[local-name()='servlet-name']"));
    }

    /**
     * The resource-ref and the JNDI lookup are one string written twice. This is the one that fails
     * at the first request rather than at deployment, which is the worst time for it to fail.
     */
    @Test
    public void theResourceRefIsTheNameTheEndpointLooksUp() throws Exception {
        String declared = text(webXml,
                "//*[local-name()='resource-ref']/*[local-name()='res-ref-name']");

        assertEquals("java:comp/env/" + declared, CustomerMasterEndpoint.DATA_SOURCE_NAME);
        assertEquals("javax.sql.DataSource",
                text(webXml, "//*[local-name()='resource-ref']/*[local-name()='res-type']"));
    }

    /**
     * Both declarations of the WSDL's location, held to the same string.
     *
     * <p>Neither is strictly load-bearing: the RI finds the single document under WEB-INF/wsdl on
     * its own, which the deployment test established by removing both and watching nothing change.
     * They are here because a reader of either file should be able to see which document is served
     * without inferring it from a directory listing - and because the day a second WSDL appears
     * under WEB-INF/wsdl, the automatic behaviour stops being unambiguous and these become the
     * answer. That is exactly when a silent disagreement between them would cost the most.
     *
     * <p>The path keeps the contract's own wsdl/ and xsd/ shape so the schema import still resolves
     * after the copy into the WAR.
     */
    @Test
    public void theEndpointPublishesTheAuthoredContract() throws Exception {
        String declared = text(sunJaxws, "//*[local-name()='endpoint']/@wsdl");

        assertEquals("WEB-INF/wsdl/wsdl/customer-master-v1.wsdl", declared);
        assertTrue("the wsdl attribute must sit under WEB-INF/wsdl, where the RI looks",
                declared.startsWith("WEB-INF/wsdl/"));

        javax.jws.WebService annotation =
                CustomerMasterEndpoint.class.getAnnotation(javax.jws.WebService.class);
        assertNotNull("the endpoint class carries no @WebService", annotation);
        assertEquals("sun-jaxws.xml and @WebService name different WSDLs",
                declared, annotation.wsdlLocation());
    }

    private static String text(Document document, String expression) throws Exception {
        String value = xpath.evaluate(expression, document);
        assertTrue("nothing matched " + expression, value != null && value.length() > 0);
        return value;
    }

    private static Document parse(File file) throws Exception {
        assertTrue("no such file: " + file.getAbsolutePath(), file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }
}
