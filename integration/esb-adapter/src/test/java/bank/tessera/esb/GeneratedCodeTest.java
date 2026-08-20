package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.tessera.esb.ws.CustomerMasterPortType;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.jws.WebMethod;
import javax.jws.WebService;
import org.junit.jupiter.api.Test;

/**
 * The client is generated from the contract, and stays that way.
 *
 * <p>The failure this guards against looks like progress: somebody hits a generation problem, copies
 * the generated sources into {@code src/main/java} to get moving, and from that moment this
 * component's idea of the interface is whatever the copy says. The build still passes and the WSDL
 * still validates - they have simply stopped being the same thing.
 *
 * <p>The second assertion is the one that matters for an ESB specifically. This is the
 * <strong>second</strong> client generated from {@code customer-master-v1.wsdl}; the first is that
 * component's own server interface. Both are generated, neither is authoritative, and the contract
 * is what makes them agree - which the deployment test then proves by making a real call.
 */
class GeneratedCodeTest {

    private static final File MAIN_SOURCES = new File("src/main/java");
    private static final File GENERATED =
            new File("target/generated-sources/wsimport/bank/tessera/esb/ws");

    private static final List<String> CONTRACT_OPERATIONS =
            Arrays.asList("GetAccount", "GetAccountsByCustomer", "NotifyTransferPosted");

    @Test
    void nothingGeneratedIsCommitted() {
        List<File> sources = new ArrayList<>();
        collectJavaFiles(MAIN_SOURCES, sources);

        assertTrue(sources.size() > 0, "no hand-written sources under " + MAIN_SOURCES);
        for (File source : sources) {
            assertFalse("ObjectFactory.java".equals(source.getName()),
                    source + " is generated code under src/main/java");
        }
        assertFalse(new File(MAIN_SOURCES, "bank/tessera/esb/ws").exists(),
                "src/main/java/bank/tessera/esb/ws is the generated package and must not have a"
                        + " hand-written twin");
    }

    @Test
    void theGeneratedTreeLivesUnderTarget() {
        assertTrue(GENERATED.isDirectory(),
                "wsimport has not run, or its sourceDestDir moved: " + GENERATED.getAbsolutePath());
    }

    @Test
    void theGeneratedClientCarriesTheThreeContractOperations() {
        WebService annotation = CustomerMasterPortType.class.getAnnotation(WebService.class);
        assertNotNull(annotation, "the generated port type carries no @WebService");
        assertEquals("http://services.tesserabank.example/customer-master/v1",
                annotation.targetNamespace());

        List<String> operations = new ArrayList<>();
        for (Method method : CustomerMasterPortType.class.getMethods()) {
            WebMethod webMethod = method.getAnnotation(WebMethod.class);
            if (webMethod != null) {
                operations.add(webMethod.operationName());
            }
        }

        assertEquals(CONTRACT_OPERATIONS.size(), operations.size());
        assertTrue(operations.containsAll(CONTRACT_OPERATIONS),
                "the generated client is missing an operation the WSDL declares: " + operations);
    }

    private static void collectJavaFiles(File directory, List<File> into) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectJavaFiles(entry, into);
            } else if (entry.getName().endsWith(".java")) {
                into.add(entry);
            }
        }
    }
}
