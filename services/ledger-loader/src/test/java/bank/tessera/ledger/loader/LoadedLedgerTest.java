package bank.tessera.ledger.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.adapter.jdbc.AuditChain;
import bank.tessera.ledger.adapter.jdbc.BalanceReconciliation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The load, against real PostgreSQL, checked by the ledger's own controls.
 *
 * <p>This is the test the package exists for. Everything else here verifies the loader against
 * itself; {@code BalanceReconciliation} sums the postings in SQL, reimplementing the sign convention
 * independently of the Java that wrote them, and {@code AuditChain} walks the trail end to end. A
 * loader checked only against its own arithmetic proves nothing, which is the same argument the
 * decision log makes for deriving a balance two independent ways.
 *
 * <p>The container boilerplate below is a fourth copy of scaffolding that already exists in
 * {@code ledger-persistence}'s tests, in {@code batch/reporting}'s and in {@code batch/recon}'s. It is
 * kept small deliberately - the migrations themselves come off {@code ledger-persistence}'s own
 * classpath rather than being copied - and follow-up F-66 records the duplication rather than this
 * branch widening into another module to fix it.
 */
@Testcontainers
class LoadedLedgerTest {

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tessera_ledger")
                    .withUsername("ledger")
                    .withPassword("ledger");

    private static String urlFor(String schema) {
        CONTAINER.start();
        String url = CONTAINER.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static Connection connect(String schema) throws SQLException {
        return DriverManager.getConnection(urlFor(schema), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    /** A migrated schema of its own per load, so two loads in one container cannot see each other. */
    private static void migrate(String schema) throws SQLException {
        try (Connection connection = connect("public");
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(urlFor(schema), CONTAINER.getUsername(), CONTAINER.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private record Loaded(String schema, LoadManifest manifest) {}

    private static Loaded load(String schema) throws SQLException, IOException {
        migrate(schema);
        try (Connection connection = connect(schema)) {
            CopyRowSink copy = new CopyRowSink(connection);
            DigestingSink digesting = new DigestingSink(copy);
            // A small checkpoint on purpose: the fixture is 401 accounts, and a load that only ever
            // committed once would leave the per-checkpoint path - the one that bounds the deferred
            // trigger's pending queue at volume - unexercised by this suite.
            DatasetLoader loader = new DatasetLoader(digesting, 50);
            try (InputStream in = DatasetLoaderTest.fixture()) {
                DatasetReader.read(in, loader);
            }
            LoadSummary summary = loader.finish();
            return new Loaded(schema, LoadManifest.of(summary, digesting.hex(), copy.rowsWritten(), 0));
        }
    }

    private static NamedParameterJdbcTemplate jdbc(Connection connection) {
        return new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    /**
     * REQ-LED-006, run against a loaded ledger rather than against three fixture accounts. The
     * materialised balance is a second source of truth and this is the check that it agrees with the
     * postings underneath it.
     */
    @Test
    void theLedgersOwnReconciliationIsCleanOverEveryLoadedAccount() throws Exception {
        Loaded loaded = load("recon_clean");

        try (Connection connection = connect(loaded.schema())) {
            List<BalanceReconciliation.Drift> drifts = new BalanceReconciliation(jdbc(connection)).breaks();
            assertThat(drifts).isEmpty();
            assertThat(count(connection, "SELECT count(*) FROM balance")).isEqualTo(401);
        }
    }

    @Test
    void theAuditChainVerifiesEndToEnd() throws Exception {
        Loaded loaded = load("chain_ok");

        try (Connection connection = connect(loaded.schema())) {
            AuditChain chain = new AuditChain(jdbc(connection), new ObjectMapper());
            assertThat(chain.verify()).isEmpty();
            assertThat(chain.length()).isEqualTo(loaded.manifest().chainLength());
        }
    }

    /**
     * F-43, asserted in SQL against what the loader produced rather than against what it believed it
     * produced. Every report in {@code batch/reporting} bounds its postings by this join, so an entry
     * missing from it is invisible to all of them.
     */
    @Test
    void everyEntryIsReachableThroughTheJoinTheReportsUse() throws Exception {
        Loaded loaded = load("reports_reach");

        try (Connection connection = connect(loaded.schema())) {
            long entries = count(connection, "SELECT count(*) FROM journal_entry");
            long reachable = count(
                    connection,
                    "SELECT count(*) FROM journal_entry je JOIN audit_record ar"
                            + " ON ar.subject_ref = je.reference"
                            + " WHERE ar.action IN ('TRANSFER_POSTED', 'TRANSFER_REVERSED')");
            assertThat(entries).isPositive();
            assertThat(reachable).isEqualTo(entries);
        }
    }

    /**
     * "No constraint or trigger was disabled to complete a load" is a Definition of Done box, and a
     * box is not evidence. {@code tgenabled} is 'O' for a trigger that fires normally and 'D' for one
     * somebody switched off, and this reads it out of the catalogue after the load has finished.
     */
    @Test
    void noTriggerWasDisabledToCompleteTheLoad() throws Exception {
        Loaded loaded = load("triggers_live");

        try (Connection connection = connect(loaded.schema());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT c.relname, t.tgname, t.tgenabled FROM pg_trigger t"
                                + " JOIN pg_class c ON c.oid = t.tgrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE NOT t.tgisinternal AND n.nspname = '" + loaded.schema() + "'")) {
            List<String> disabled = new ArrayList<>();
            int seen = 0;
            while (rows.next()) {
                seen++;
                if (!"O".equals(rows.getString(3))) {
                    disabled.add(rows.getString(1) + "." + rows.getString(2) + " = " + rows.getString(3));
                }
            }
            assertThat(seen).describedAs("the schema declares triggers to check").isPositive();
            assertThat(disabled).isEmpty();
        }
    }

    /**
     * The other half of the same claim, and the stronger one: the balanced-entry trigger is not
     * merely enabled, it fires. A single-leg entry is what a loader with a defect writes, and it must
     * be refused at commit rather than accepted and reconciled away later.
     */
    @Test
    void theDeferredBalanceTriggerStillRefusesAnUnbalancedEntry() throws Exception {
        Loaded loaded = load("trigger_bites");

        try (Connection connection = connect(loaded.schema())) {
            connection.setAutoCommit(false);
            CopyManager copy = new CopyManager(connection.unwrap(BaseConnection.class));
            String account = one(connection, "SELECT reference FROM account WHERE account_type = 'LIABILITY' LIMIT 1");

            copy.copyIn(
                    "COPY journal_entry (reference, value_date, currency, created_at) FROM STDIN",
                    new StringReader("TB209901010000000001\t2099-01-01\tPLN\t2099-01-01T00:00:00Z\n"));
            copy.copyIn(
                    "COPY posting (entry_ref, seq, account_ref, direction, amount_minor, currency) FROM STDIN",
                    new StringReader("TB209901010000000001\t1\t" + account + "\tDEBIT\t100\tPLN\n"));

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("does not balance");
            connection.rollback();
        }
    }

    /**
     * The Definition of Done asks for a digest rather than row counts alone, because two loads can
     * write the same number of rows and disagree about every amount in them.
     */
    @Test
    void theSameStreamLoadedTwiceProducesTheSameDigest() throws Exception {
        Loaded first = load("digest_one");
        Loaded second = load("digest_two");

        assertThat(second.manifest().datasetDigest()).isEqualTo(first.manifest().datasetDigest());
        assertThat(second.manifest().chainHead()).isEqualTo(first.manifest().chainHead());
        assertThat(first.manifest().datasetDigest()).hasSize(64);

        // And the two databases agree about what is in them, not only about the digest of what was
        // sent to them.
        try (Connection one = connect(first.schema());
                Connection two = connect(second.schema())) {
            assertThat(count(two, "SELECT count(*) FROM posting"))
                    .isEqualTo(count(one, "SELECT count(*) FROM posting"));
            assertThat(one(two, "SELECT hash FROM audit_record ORDER BY seq DESC LIMIT 1"))
                    .isEqualTo(one(one, "SELECT hash FROM audit_record ORDER BY seq DESC LIMIT 1"));
        }
    }

    /** The counters a reader has to be given rather than left to infer. */
    @Test
    void theManifestReportsWhatTheLoadHadToSubstitute() throws Exception {
        Loaded loaded = load("manifest_counts");
        Map<String, Long> counters = loaded.manifest().counters();

        assertThat(counters).containsKey(Counter.ACCOUNTS_OPENED.name());
        assertThat(counters).containsKey(Counter.TRANSFERS_POSTED.name());
        assertThat(counters).containsKey(Counter.CURRENCY_SUBSTITUTED.name());
        assertThat(loaded.manifest().busiestAccountRef()).matches("^TB[0-9A-Z]{14}$");
        assertThat(loaded.manifest().busiestAccountPostings()).isPositive();
        assertThat(loaded.manifest().formatId()).isEqualTo(LoadManifest.FORMAT_ID);
    }

    private static long count(Connection connection, String sql) throws SQLException {
        return Long.parseLong(one(connection, sql));
    }

    private static String one(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("No row from: " + sql);
            }
            return rows.getString(1);
        }
    }
}
