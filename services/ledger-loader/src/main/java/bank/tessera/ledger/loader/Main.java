package bank.tessera.ledger.loader;

import bank.tessera.ledger.adapter.jdbc.AuditChain;
import bank.tessera.ledger.adapter.jdbc.BalanceReconciliation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The loader's command line: {@code load} reads a dataset stream on stdin, {@code verify} runs the
 * ledger's own controls against whatever is in the database.
 *
 * <pre>
 * go -C workload run ./cmd/workload-dataset --model ... --from ... --to ... \
 *   | ledger-loader load --url jdbc:postgresql://localhost:5432/ledger --user ledger
 * ledger-loader verify --url jdbc:postgresql://localhost:5432/ledger --user ledger
 * </pre>
 *
 * <p>It is a test fixture, not a component of the bank - the same category
 * {@code workload/} and {@code walkthrough.sh} occupy. Nothing in the estate depends on it.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IOException | SQLException failed) {
            System.err.println("ledger-loader: " + failed.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException, SQLException {
        if (args.length == 0) {
            System.err.println("usage: ledger-loader migrate|load|verify --url <jdbc> [--user u] [--password p]"
                    + " [--manifest path] [--checkpoint-accounts n]");
            return 2;
        }
        Map<String, String> options = options(args);
        String url = required(options, "url");
        String user = options.getOrDefault("user", "ledger");
        String password = options.getOrDefault("password", "ledger");

        return switch (args[0]) {
            case "migrate" -> migrate(url, user, password);
            case "load" -> load(options, url, user, password);
            case "verify" -> verify(url, user, password);
            default -> {
                System.err.println("ledger-loader: unknown command '" + args[0] + "'");
                yield 2;
            }
        };
    }

    /**
     * Applies the ledger's own migrations.
     *
     * <p>This module writes none of them - they are {@code ledger-persistence}'s, read off its
     * classpath - and WP-22's Out of scope forbids changing the schema from this branch. What this
     * does is save a run from having to boot the whole ledger once just to create the tables.
     */
    private static int migrate(String url, String user, String password) {
        var result = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        System.out.println("ledger-loader: schema at version " + result.targetSchemaVersion
                + ", " + result.migrationsExecuted + " migrations applied");
        return 0;
    }

    private static int load(Map<String, String> options, String url, String user, String password)
            throws IOException, SQLException {
        long checkpointEvery = Long.parseLong(options.getOrDefault("checkpoint-accounts", "20000"));
        Instant started = Instant.now();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            CopyRowSink copy = new CopyRowSink(connection);
            DigestingSink digesting = new DigestingSink(copy);
            DatasetLoader loader = new DatasetLoader(digesting, checkpointEvery);

            try (BufferedInputStream in = new BufferedInputStream(System.in, 1 << 20)) {
                DatasetReader.read(in, loader);
            }
            LoadSummary summary = loader.finish();
            long seconds = Duration.between(started, Instant.now()).toSeconds();
            LoadManifest manifest =
                    LoadManifest.of(summary, digesting.hex(), copy.rowsWritten(), seconds);

            String rendered = json().writeValueAsString(manifest);
            String path = options.get("manifest");
            if (path == null) {
                System.out.println(rendered);
            } else {
                Files.writeString(Path.of(path), rendered + System.lineSeparator());
                System.out.println("ledger-loader: wrote " + path);
            }
            print(manifest);
        }
        return 0;
    }

    /**
     * Runs the ledger's own controls against the loaded database.
     *
     * <p>Not the loader's arithmetic: {@code BalanceReconciliation} sums the postings in SQL
     * independently of the materialised balances, and {@code AuditChain} walks the whole trail. A
     * loader checked only against itself proves nothing, which is the argument the decision log makes
     * for deriving a balance two independent ways.
     */
    private static int verify(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            SingleConnectionDataSource source = new SingleConnectionDataSource(connection, true);
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(source);

            List<BalanceReconciliation.Drift> drifts = new BalanceReconciliation(jdbc).breaks();
            AuditChain chain = new AuditChain(jdbc, new ObjectMapper());
            var broken = chain.verify();

            System.out.println("Balance reconciliation   " + (drifts.isEmpty()
                    ? "clean over every account"
                    : drifts.size() + " accounts drifted"));
            drifts.stream().limit(10).forEach(drift -> System.out.println("  " + drift));
            System.out.println("Audit chain              " + chain.length() + " rows, "
                    + broken.map(link -> "BROKEN at seq " + link.seq() + ": " + link.reason())
                            .orElse("verified end to end"));

            return drifts.isEmpty() && broken.isEmpty() ? 0 : 1;
        }
    }

    private static void print(LoadManifest manifest) {
        System.out.printf("  Model           %s %s digest %s%n",
                manifest.modelId(), manifest.modelVersion(), manifest.modelDigest().substring(0, 12));
        System.out.printf("  Range           %s to %s, opened %s%n",
                manifest.from(), manifest.to(), manifest.openingDate());
        System.out.printf("  Population      %d customers, %d accounts each, opened in %s%n",
                manifest.customers(), manifest.accountsPerCustomer(), manifest.baseCurrency());
        System.out.printf("  Rows            %d in %d s%n", manifest.rowsWritten(), manifest.loadSeconds());
        System.out.printf("  Dataset digest  %s%n", manifest.datasetDigest());
        System.out.printf("  Chain           %d rows, head %s%n",
                manifest.chainLength(), manifest.chainHead());
        System.out.printf("  Treasury        %s%n", manifest.treasuryAccountRef());
        System.out.printf("  Busiest account %s with %d postings%n",
                manifest.busiestAccountRef(), manifest.busiestAccountPostings());
        manifest.counters().forEach((name, value) -> System.out.printf("  %-24s %d%n", name, value));
    }

    private static ObjectMapper json() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }
}
