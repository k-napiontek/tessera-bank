package bank.tessera.ledger.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import bank.tessera.ledger.api.LedgerApiTest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The ledger reports on the database that does its work, and a scrape is where that is checked.
 *
 * <p>WP-23 exists because this tier counted how many transfers posted and said nothing at all about
 * the PostgreSQL that posted them - no pool utilisation, no lock wait, no table growth, no vacuum
 * activity - while every transfer takes two row locks and one global advisory lock, and F-27 and
 * F-28 are open questions that only a database signal can answer.
 *
 * <p>Asserted against {@code /actuator/prometheus} rather than against the registry, for the reason
 * {@link BusinessMetricsTest} gives: the registry holding a meter and a scrape exposing it are
 * different facts, and the second is the one an operator gets. It also settles the half of the
 * catalogue that a contract check cannot - the exposed name is Micrometer's rendering of the meter
 * name, and this is the only place that rendering is real rather than assumed.
 */
// Without this, Boot leaves a SimpleMeterRegistry, /actuator/prometheus does not exist, and every
// assertion below would be written against nothing. F-32 records the trap; the symptom is a 500
// from an endpoint that plainly should be there.
@AutoConfigureObservability
class DatabaseSignalsTest extends LedgerApiTest {

    private static final Pattern SERIES = Pattern.compile("^(ledger_db_[a-z_]+)\\{([^}]*)}\\s+(\\S+)$");

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
    }

    private static List<String> samples(String scrape) {
        return Arrays.stream(scrape.split("\n"))
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    @Test
    @DisplayName("every database signal the package promises is in a real scrape")
    void theSignalsAreScrapeable() throws Exception {
        List<String> samples = samples(scrape());

        // The exposed names, not the meter names. Micrometer renders dots as underscores and
        // appends the base unit; this list is what an operator's query actually has to say.
        for (String exposed : List.of(
                "ledger_db_table_size_bytes",
                "ledger_db_index_size_bytes",
                "ledger_db_dead_tuples",
                "ledger_db_live_tuples",
                "ledger_db_autovacuums",
                "ledger_db_autovacuum_age_seconds")) {
            assertThat(samples)
                    .as("%s is exposed", exposed)
                    .anyMatch(line -> line.startsWith(exposed + "{"));
        }
    }

    @Test
    @DisplayName("the signals name every table the ledger owns, and no others")
    void theTableListMatchesTheSchema() {
        Set<String> inTheDatabase = new TreeSet<>(jdbc.queryForList(
                """
                SELECT tablename FROM pg_tables
                 WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """,
                Map.of(),
                String.class));

        assertThat(new TreeSet<>(DatabaseSignals.TABLES))
                .as("a migration that adds a table must add its signals with it")
                .isEqualTo(inTheDatabase);
    }

    @Test
    @DisplayName("cardinality is bounded by construction: one series per table, from a fixed list")
    void cardinalityIsBounded() throws Exception {
        List<String> series = samples(scrape()).stream()
                .map(SERIES::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1) + "|" + matcher.group(2))
                .toList();

        Set<String> tables = series.stream()
                .map(one -> one.replaceAll(".*table=\"([^\"]+)\".*", "$1"))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(tables)
                .as("a label taken from pg_stat_user_tables as it comes grows a series every migration")
                .containsExactlyInAnyOrderElementsOf(DatabaseSignals.TABLES);
        assertThat(series).hasSize(DatabaseSignals.MEASURES * DatabaseSignals.TABLES.size());
    }

    @Test
    @DisplayName("a table that has never been autovacuumed reports NaN, never zero")
    void neverVacuumedIsNotReportedAsJustVacuumed() throws Exception {
        // Zero would read as "vacuumed a moment ago", which is the exact opposite of the truth and
        // is the kind of plausible wrong number this repository keeps a trap list about.
        List<String> ages = samples(scrape()).stream()
                .filter(line -> line.startsWith("ledger_db_autovacuum_age_seconds{"))
                .toList();

        assertThat(ages).isNotEmpty();
        assertThat(ages)
                .as("a fresh database has autovacuumed nothing, so every age is unknown")
                .allMatch(line -> line.endsWith("NaN"));
    }
}
