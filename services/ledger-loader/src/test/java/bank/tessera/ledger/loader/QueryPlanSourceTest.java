package bank.tessera.ledger.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code scripts/plans.sql} to the code the queries in it were transcribed from.
 *
 * <p>A plan capture has to run the query the application actually sends, and the only way to run one
 * through {@code psql} is to write it out again. Writing it out again is what **F-64** records
 * costing five requirement ids their meaning, in the one document that copied instead of reading -
 * so the transcription is checked rather than trusted.
 *
 * <p><strong>What this catches:</strong> a query rewritten, a join dropped, an ORDER BY reordered, a
 * predicate removed. Any of those makes a captured plan a plan of a query nobody runs.
 *
 * <p><strong>What it does not catch:</strong> a query added somewhere else that nobody thought to
 * capture, and a change that alters only the parts not listed here. A plan capture is a sample, and
 * saying so is better than implying it is a proof.
 */
class QueryPlanSourceTest {

    private static final Path REPO = Path.of("..", "..");
    private static final Path PLANS = Path.of("scripts", "plans.sql");

    /**
     * Fragments that must appear both in {@code plans.sql} and in the file they came from.
     *
     * <p>Chosen to be parameter-free: the read model binds {@code :account} and reporting binds
     * {@code %(position)s}, so anything carrying a placeholder cannot be compared across the two.
     */
    private static final Map<String, List<String>> TRANSCRIBED = Map.of(
            "services/ledger-persistence/src/main/java/bank/tessera/ledger/adapter/jdbc/JdbcLedgerReadModel.java",
            List.of(
                    "FROM posting p",
                    "JOIN journal_entry e ON e.reference = p.entry_ref",
                    "ORDER BY e.value_date, e.created_at, p.entry_ref, p.seq",
                    "(e.value_date, e.created_at, p.entry_ref, p.seq)",
                    "JOIN account a ON a.reference = p.account_ref",
                    "COALESCE(SUM(CASE WHEN (a.account_type IN ('ASSET', 'EXPENSE'))"),
            "batch/reporting/src/reporting/ledger.py",
            List.of(
                    "JOIN journal_entry je ON je.reference = p.entry_ref",
                    "ON ar.subject_ref = je.reference",
                    "ON opened.subject_ref = a.reference",
                    "AND opened.action = 'ACCOUNT_OPENED'",
                    "ORDER BY p.entry_ref, p.seq",
                    "GROUP BY a.reference, a.customer_ref, a.account_type, a.currency, a.status,"));

    @Test
    void everyTranscribedFragmentStillExistsInTheCodeItCameFrom() throws IOException {
        String plans = normalise(Files.readString(PLANS));

        assertThat(TRANSCRIBED).isNotEmpty();
        for (Map.Entry<String, List<String>> source : TRANSCRIBED.entrySet()) {
            String authority = normalise(Files.readString(REPO.resolve(source.getKey())));
            for (String fragment : source.getValue()) {
                String wanted = normalise(fragment);
                assertThat(authority)
                        .describedAs("%s no longer contains %s, which plans.sql transcribes",
                                source.getKey(), fragment)
                        .contains(wanted);
                assertThat(plans)
                        .describedAs("plans.sql no longer contains %s, which it claims to capture", fragment)
                        .contains(wanted);
            }
        }
    }

    /**
     * The same text laid out two ways is the same query.
     *
     * <p>The Java concatenates its SQL across string literals and {@code plans.sql} indents it to be
     * read, so a comparison that cared about whitespace would fail on the formatting rather than on
     * the query. Every run of whitespace collapses to one space.
     */
    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ");
    }
}
