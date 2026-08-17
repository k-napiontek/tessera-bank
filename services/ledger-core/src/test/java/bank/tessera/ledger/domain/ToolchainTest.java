package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the stratum 3 version pin on every build.
 *
 * <p>Stratum 3 is pinned to Java 17 by {@code CLAUDE.md}. The pin is deliberate - even the modern
 * tier of a real bank runs a few years behind - and a build file can drift from it silently. This
 * asserts the JVM actually running the tests, so a toolchain change fails the build rather than
 * passing unnoticed.
 */
class ToolchainTest {

    @Test
    @DisplayName("the tests run on Java 17, the pinned version for stratum 3")
    void runsOnJavaSeventeen() {
        assertThat(Runtime.version().feature())
                .as("stratum 3 is pinned to Java 17; see CLAUDE.md")
                .isEqualTo(17);
    }
}
