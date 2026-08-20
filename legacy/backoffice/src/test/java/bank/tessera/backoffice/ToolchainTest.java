package bank.tessera.backoffice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Proves the stratum 1 version pin on every build.
 *
 * <p>The same assertion {@code customer-master} makes, made again here because this is a separate
 * module with a separate build. A pin proved in one module says nothing about its sibling, and a
 * WAR that compiled under a newer JDK fails at deployment rather than at build time.
 *
 * <p>{@code Runtime.version()} is how stratum 3 makes this assertion and it does not exist here -
 * it arrived in Java 9. Reading {@code java.specification.version} is the Java 8 way.
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
     * A second, independent statement: the property above is what the JVM reports, this is what the
     * class library actually contains. {@code java.util.List.of} arrived in Java 9.
     */
    @Test
    public void classLibraryIsJavaEight() throws Exception {
        assertNull(
                "java.util.List.of exists, so this is a Java 9+ class library",
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
