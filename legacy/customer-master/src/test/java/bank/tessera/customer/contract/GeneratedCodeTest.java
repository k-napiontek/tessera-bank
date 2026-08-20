package bank.tessera.customer.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import bank.tessera.customer.ws.CustomerMasterPortType;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.jws.WebMethod;
import javax.jws.WebService;
import java.lang.reflect.Method;
import org.junit.Test;

/**
 * The WSDL-first discipline, asserted rather than trusted.
 *
 * <p>The contract is authored by hand and the Java is generated from it. The failure this guards
 * against is the one that looks like progress: somebody hits a generation problem, copies the
 * generated sources into src/main/java to get moving, and from that moment the interface is
 * whatever the copy says. The build still passes, the WSDL still validates, and the two have
 * silently stopped being the same thing. A generated file under src/ is therefore a build failure
 * here, not a code-review remark.
 *
 * <p>The second test is the other half: that the generated interface really carries the three
 * operations the port type declares. Together they say the code under test came from the contract
 * and from nowhere else.
 */
public class GeneratedCodeTest {

    private static final File MAIN_SOURCES = new File("src/main/java");
    private static final File GENERATED_SOURCES =
            new File("target/generated-sources/wsimport/bank/tessera/customer/ws");

    /** Every operation the WSDL's portType declares, by the name it declares it under. */
    private static final List<String> CONTRACT_OPERATIONS = Arrays.asList(
            "GetAccount", "GetAccountsByCustomer", "NotifyTransferPosted");

    @Test
    public void nothingGeneratedIsCommitted() {
        List<File> sources = new ArrayList<File>();
        collectJavaFiles(MAIN_SOURCES, sources);

        assertTrue("no hand-written sources found under " + MAIN_SOURCES.getAbsolutePath()
                + " - this test cannot prove anything about an empty tree", sources.size() > 0);

        for (int i = 0; i < sources.size(); i++) {
            File source = sources.get(i);
            String name = source.getName();
            assertTrue(name + " is a JAXB ObjectFactory under src/main/java. Generated code belongs"
                    + " in target/generated-sources/wsimport and is never committed.",
                    !"ObjectFactory.java".equals(name));
            assertTrue(name + " is a generated JAXB package-info under src/main/java.",
                    !"package-info.java".equals(name) || !underWsPackage(source));
        }
    }

    @Test
    public void theGeneratedTreeLivesUnderTarget() {
        assertTrue("wsimport has not run, or its sourceDestDir moved. Expected "
                + GENERATED_SOURCES.getAbsolutePath(), GENERATED_SOURCES.isDirectory());

        File committed = new File(MAIN_SOURCES, "bank/tessera/customer/ws");
        assertTrue("src/main/java/bank/tessera/customer/ws exists. That is the generated package -"
                + " it must not have a hand-written twin, or the two will disagree.",
                !committed.exists());
    }

    @Test
    public void theGeneratedPortTypeCarriesTheThreeContractOperations() {
        WebService annotation = CustomerMasterPortType.class.getAnnotation(WebService.class);
        assertNotNull("the generated port type carries no @WebService", annotation);
        assertEquals("http://services.tesserabank.example/customer-master/v1",
                annotation.targetNamespace());
        assertEquals("CustomerMasterPortType", annotation.name());

        List<String> operations = new ArrayList<String>();
        Method[] methods = CustomerMasterPortType.class.getMethods();
        for (int i = 0; i < methods.length; i++) {
            WebMethod webMethod = methods[i].getAnnotation(WebMethod.class);
            if (webMethod != null) {
                operations.add(webMethod.operationName());
            }
        }

        assertEquals("the generated interface does not carry the operations the WSDL declares",
                CONTRACT_OPERATIONS.size(), operations.size());
        for (int i = 0; i < CONTRACT_OPERATIONS.size(); i++) {
            String operation = CONTRACT_OPERATIONS.get(i);
            assertTrue("the generated interface is missing operation " + operation,
                    operations.contains(operation));
        }
    }

    /**
     * The contract really is read from the repository root, not from a copy. The WSDL imports the
     * canonical XSD as ../xsd/canonical-v1.xsd, so the sibling directory has to be there too - a
     * copied WSDL fails at generation with an error naming the schema rather than the copy.
     */
    @Test
    public void theContractDirectoryIsTheRepositoryRootOne() {
        File contracts = new File(System.getProperty(
                "tessera.contracts.dir", ".." + File.separator + ".." + File.separator
                        + "contracts"));

        File wsdl = new File(new File(contracts, "wsdl"), "customer-master-v1.wsdl");
        File xsd = new File(new File(contracts, "xsd"), "canonical-v1.xsd");

        assertTrue("no WSDL at " + wsdl.getAbsolutePath(), wsdl.isFile());
        assertTrue("the WSDL's ../xsd/ sibling is missing at " + xsd.getAbsolutePath(),
                xsd.isFile());
    }

    private static boolean underWsPackage(File source) {
        return source.getAbsolutePath().contains(
                "bank" + File.separator + "tessera" + File.separator + "customer"
                        + File.separator + "ws");
    }

    private static void collectJavaFiles(File directory, List<File> into) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].isDirectory()) {
                collectJavaFiles(entries[i], into);
            } else if (entries[i].getName().endsWith(".java")) {
                into.add(entries[i]);
            }
        }
    }
}
