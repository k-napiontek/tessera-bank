package bank.tessera.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The zero-framework constraint, enforced rather than merely written down.
 *
 * <p>WP-06 says the domain must have no framework dependency at all, and WP-07 will replace this
 * with a proper ArchUnit rule once there is an infrastructure layer for it to police. Until then a
 * source scan does the job: it is crude, but a constraint checked crudely holds, and a constraint
 * written in a document does not.
 *
 * <p>The point is not purity for its own sake. A domain with no framework on its classpath is
 * testable in milliseconds and survives the framework rewrite that will eventually come.
 */
class DomainPurityTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final List<String> FORBIDDEN = List.of(
            "org.springframework",
            "jakarta.persistence",
            "jakarta.validation",
            "jakarta.inject",
            "javax.persistence",
            "javax.validation",
            "javax.inject",
            "com.fasterxml.jackson",
            "org.hibernate",
            "lombok");

    @Test
    @DisplayName("no production source imports a framework")
    void theDomainImportsNoFramework() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (!line.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN) {
                        if (line.contains(forbidden)) {
                            violations.add(SOURCES.relativize(file) + ":" + (i + 1) + "  " + line);
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("the ledger domain must depend on no framework - see WP-06 constraints")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan actually reaches the sources it claims to check")
    void theScanFindsTheSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            long count = files.filter(p -> p.toString().endsWith(".java")).count();
            assertThat(count)
                    .as("a purity scan that finds no files would pass vacuously")
                    .isGreaterThanOrEqualTo(15);
        }
    }

    @Test
    @DisplayName("money is never a floating-point type anywhere in the domain")
    void moneyIsNeverFloatingPoint() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String code = line.strip();
                    String stripped = stripCommentsAndLiterals(code);
                    if (stripped.matches(".*\\b(double|float)\\b.*") || stripped.contains("BigDecimal")) {
                        violations.add(SOURCES.relativize(file) + ":" + (i + 1) + "  " + code);
                    }
                }
            }
        }

        assertThat(violations)
                .as("money is minor units plus a currency; a float in the domain is a defect")
                .isEmpty();
    }

    /**
     * Removes comments and string literals before scanning.
     *
     * <p>Without this the check fires on prose - the phrase "not double entry" in an error message
     * is not a floating-point type. A check with false positives gets switched off, which is worse
     * than not having one.
     */
    private static String stripCommentsAndLiterals(String line) {
        if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
            return "";
        }
        String withoutLiterals = line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        int comment = withoutLiterals.indexOf("//");
        return comment >= 0 ? withoutLiterals.substring(0, comment) : withoutLiterals;
    }
}
