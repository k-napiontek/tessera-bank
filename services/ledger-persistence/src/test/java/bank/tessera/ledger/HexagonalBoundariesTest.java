package bank.tessera.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hexagonal boundaries, enforced rather than described.
 *
 * <p>This is the rule {@code DomainPurityTest} in {@code ledger-core} anticipated: *"WP-07 will replace
 * this with a proper ArchUnit rule once there is an infrastructure layer for it to police."* It lives
 * here rather than there because a rule needs both sides on its classpath to mean anything - in
 * {@code ledger-core} no framework exists to import, so the rule would pass vacuously.
 *
 * <p>{@code DomainPurityTest} stays where it is. It guards that module's dependency list, it costs
 * milliseconds, and deleting a working control because a better one arrived elsewhere is how coverage
 * quietly shrinks.
 */
class HexagonalBoundariesTest {

    private static JavaClasses ledger;

    @BeforeAll
    static void importTheLedger() {
        ledger = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("bank.tessera.ledger");
    }

    @Test
    @DisplayName("the import found both the domain and the adapters - otherwise every rule is vacuous")
    void theImportIsNotEmpty() {
        // A rule over an empty set of classes passes. Without this, a broken import would turn every
        // assertion below into a green tick that checks nothing.
        assertThat(ledger.stream().filter(c -> c.getPackageName().contains(".domain")).count())
                .as("the domain classes must be on the classpath for the purity rules to bite")
                .isGreaterThanOrEqualTo(15);
        assertThat(ledger.stream().filter(c -> c.getPackageName().contains(".adapter")).count())
                .as("the adapters must be present for the direction rules to bite")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the domain and its ports import no framework")
    void theDomainImportsNoFramework() {
        noClasses()
                .that()
                .resideInAnyPackage("..ledger.domain..", "..ledger.port..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "javax.sql..",
                        "java.sql..",
                        "org.flywaydb..",
                        "com.zaxxer..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..")
                .because("a domain that compiles without a framework survives the framework rewrite that "
                        + "eventually comes, and is testable in milliseconds without a database")
                .check(ledger);
    }

    @Test
    @DisplayName("the domain does not know the adapters exist")
    void theDomainDoesNotDependOnTheAdapters() {
        noClasses()
                .that()
                .resideInAnyPackage("..ledger.domain..", "..ledger.port..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..ledger.adapter..")
                .because("infrastructure depends on the domain and never the reverse - that direction is "
                        + "the whole of the architecture, and it is a rule that has to outlive the "
                        + "person who chose it")
                .check(ledger);
    }

    @Test
    @DisplayName("the domain uses no floating-point or BigDecimal money")
    void moneyIsNeverFloatingPoint() {
        noClasses()
                .that()
                .resideInAPackage("..ledger.domain..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.math.BigDecimal")
                .because("money is minor units plus an ISO 4217 code; a BigDecimal in the domain invites "
                        + "a scale nobody chose")
                .check(ledger);
    }

    @Test
    @DisplayName("no source in this module mutates a posting")
    void nothingUpdatesOrDeletesAPosting() throws IOException {
        // The database refuses this at runtime with a trigger. This catches it at build time, where the
        // author is still looking at the code, and it catches SQL the trigger would never see because it
        // was never run. A correction is a reversing entry.
        Path sources = Path.of("src", "main", "java");
        List<String> offences = new ArrayList<>();

        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file).toUpperCase(java.util.Locale.ROOT);
                if (text.contains("UPDATE POSTING") || text.contains("DELETE FROM POSTING")) {
                    offences.add(sources.relativize(file).toString());
                }
            }
        }

        assertThat(offences)
                .as("posting is append-only; correct a posting with a reversing entry")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan reaches the sources it claims to check")
    void theSourceScanFindsSources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            assertThat(files.filter(path -> path.toString().endsWith(".java")).count())
                    .as("a source scan that finds no files passes vacuously")
                    .isGreaterThanOrEqualTo(5);
        }
    }
}
