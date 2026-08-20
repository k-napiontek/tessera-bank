package bank.tessera.backoffice.web;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import org.junit.Test;

/**
 * The descriptor says what the operations team reads it for, and says the same thing the code does.
 *
 * <p>Cheap, and it catches the failure mode that costs a deployment: a servlet class renamed in
 * Java and not in {@code web.xml} compiles perfectly and fails at context start with a
 * {@code ClassNotFoundException} naming a class nobody can find in the source tree.
 */
public class DeploymentDescriptorTest {

    private static final File WEB_XML = new File("src/main/webapp/WEB-INF/web.xml");

    @Test
    public void everyDeclaredServletClassExists() throws Exception {
        String descriptor = read(WEB_XML);
        String[] classes = {
            BreaksServlet.class.getName(),
            RejectsServlet.class.getName(),
            ActionServlet.class.getName(),
        };
        for (int i = 0; i < classes.length; i++) {
            assertTrue(classes[i] + " is not declared in web.xml", descriptor.contains(classes[i]));
        }
    }

    /**
     * Every screen and the action endpoint are behind the operator role. A URL added to the WAR and
     * not to the constraint is a page anybody can reach, which is the whole finding.
     */
    @Test
    public void everyMappedUrlIsBehindTheOperatorRole() throws Exception {
        String descriptor = read(WEB_XML);
        String[] patterns = {"/breaks", "/rejects", "/action"};
        int constraint = descriptor.indexOf("<security-constraint>");
        int end = descriptor.indexOf("</security-constraint>");
        assertTrue("there is no security constraint", constraint >= 0 && end > constraint);
        String protectedBlock = descriptor.substring(constraint, end);
        for (int i = 0; i < patterns.length; i++) {
            assertTrue(patterns[i] + " is not behind the operator role",
                    protectedBlock.contains("<url-pattern>" + patterns[i] + "</url-pattern>"));
        }
        assertTrue(protectedBlock.contains("<role-name>operator</role-name>"));
    }

    @Test
    public void theDatabaseIsDeclaredAsAContainerResource() throws Exception {
        String descriptor = read(WEB_XML);
        assertTrue("no resource-ref, so no connection string can stay out of this WAR",
                descriptor.contains("jdbc/customerMaster"));
        assertTrue(descriptor.contains("<res-auth>Container</res-auth>"));
    }

    @Test
    public void bothInputDirectoriesAreDeclared() throws Exception {
        String descriptor = read(WEB_XML);
        assertTrue(descriptor.contains("tessera.breaks.dir"));
        assertTrue(descriptor.contains("tessera.rejects.dir"));
    }

    private static String read(File file) throws Exception {
        StringBuilder text = new StringBuilder();
        Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
        try {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, read);
            }
        } finally {
            reader.close();
        }
        return text.toString();
    }
}
