package bank.tessera.customer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Proves the stratum 1 version pin on every build.
 *
 * <p>Stratum 1 is pinned to Java 8 by {@code CLAUDE.md}, and that pin is the reason this tier
 * exists: Java 8, Spring Boot 2.7 and Tomcat 8.5 form the one immovable block real banks are held
 * by. The parent POM refuses to build on another JDK, but an enforcer rule can be skipped from the
 * command line and a build file can drift in silence. This asserts the JVM that actually ran the
 * suite.
 *
 * <p>{@code Runtime.version()} is how stratum 3 makes the same assertion and it does not exist here
 * - it arrived in Java 9. Reading {@code java.specification.version} is the Java 8 way, and the
 * difference is exactly the sort of thing this tier is supposed to teach.
 */
public class ToolchainTest {

    @Test
    public void runsOnJavaEight() {
        assertEquals(
                "stratum 1 is pinned to Java 8; see CLAUDE.md",
                "1.8",
                System.getProperty("java.specification.version"));
    }

    /**
     * A second, independent statement. The property above is a string the JVM is asked to report;
     * this asks the class library what it contains. {@code java.time.Duration.ofDays} exists in 8,
     * while {@code java.util.List.of} arrived in 9 - so if this suite were ever run against a newer
     * class library, the property could still read 1.8 under {@code -source 1.8} while the code
     * compiled against APIs that did not exist in 2011.
     */
    @Test
    public void classLibraryIsJavaEight() throws Exception {
        assertNull(
                "java.util.List.of arrived in Java 9 - this is not a Java 8 class library",
                findMethod(java.util.List.class, "of"));
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name) {
        java.lang.reflect.Method[] methods = type.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name)) {
                return methods[i];
            }
        }
        return null;
    }
}
