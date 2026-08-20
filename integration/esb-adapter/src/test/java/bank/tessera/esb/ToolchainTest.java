package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Stratum 2 is pinned to Java 8, and the pin is checked rather than described.
 *
 * <p>The same control as stratum 1's, in the other era's test framework. JUnit 5 here and JUnit 4
 * there is not an inconsistency: 2011 had no JUnit 5 and a 2019 Spring Boot project would have used
 * nothing else, so the frameworks differ for the same reason the languages do.
 *
 * <p>Boot 2.7 is the last line supporting Java 8. If this test ever fails, either the JDK moved or
 * somebody raised the framework - and both are the change ADR 0002 forbids without an instruction
 * from the repository owner.
 */
class ToolchainTest {

    @Test
    void runsOnJavaEight() {
        assertEquals("1.8", System.getProperty("java.specification.version"),
                "stratum 2 is pinned to Java 8; see CLAUDE.md");
    }

    @Test
    void classLibraryIsJavaEight() throws Exception {
        assertNull(findMethod(java.util.List.class, "of"),
                "java.util.List.of arrived in Java 9 - this is not a Java 8 class library");
    }

    @Test
    void springBootIsTheLastLineThatSupportsJavaEight() {
        String version = org.springframework.boot.SpringBootVersion.getVersion();
        assertEquals("2.7.18", version,
                "stratum 2 is pinned to Spring Boot 2.7.18; see ADR 0002");
    }

    private static Method findMethod(Class<?> type, String name) {
        Method[] methods = type.getMethods();
        for (Method method : methods) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
