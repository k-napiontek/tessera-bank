package bank.tessera.ledger.loader;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CustomerRef;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code workload-dataset} stream and hands it to a visitor.
 *
 * <p><strong>An unknown field is a failure, not a field to skip.</strong> The emitter and this reader
 * are two halves of one contract with no schema between them, so the only thing standing between a
 * field being added on one side and being silently dropped on the other is that Jackson refuses what
 * it does not recognise. A load that quietly ignored a new column would produce a ledger that is
 * plausible and wrong, which is the failure mode this repository cares about most.
 *
 * <p>One flat shape rather than three, and one parse per line rather than a tree and then a bind: a
 * year of a bank's day is millions of lines, and a reader that costs twice as much per line is a
 * loader that measures itself.
 */
public final class DatasetReader {

    private DatasetReader() {}

    /** Reads the whole stream. The visitor sees the header first and the actions in stream order. */
    public static void read(InputStream in, DatasetVisitor visitor) throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        ObjectReader reader = mapper.readerFor(Line.class);

        boolean sawHeader = false;
        long number = 0;
        try (BufferedReader lines =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20)) {
            String text;
            while ((text = lines.readLine()) != null) {
                number++;
                if (text.isEmpty()) {
                    continue;
                }
                Line line;
                try {
                    line = reader.readValue(text);
                } catch (IOException unreadable) {
                    throw new IOException("line " + number + " of the dataset: " + unreadable.getMessage(), unreadable);
                }
                switch (line.kind) {
                    case "population" -> {
                        if (sawHeader) {
                            throw new IOException("line " + number + ": a second population header");
                        }
                        sawHeader = true;
                        visitor.population(line.toHeader());
                    }
                    case "open" -> {
                        requireHeader(sawHeader, number);
                        visitor.open(line.toOpenAccount());
                    }
                    case "action" -> {
                        requireHeader(sawHeader, number);
                        visitor.action(line.toAction());
                    }
                    default -> throw new IOException("line " + number + ": unknown kind '" + line.kind + "'");
                }
            }
        }
        if (!sawHeader) {
            throw new IOException("the dataset carries no population header");
        }
    }

    private static void requireHeader(boolean sawHeader, long number) throws IOException {
        if (!sawHeader) {
            throw new IOException("line " + number + " arrives before the population header");
        }
    }

    /** The union of every line shape the emitter writes. See the class comment on why it is flat. */
    static final class Line {
        public String kind;

        // population
        public String modelId;
        public String modelVersion;
        public String modelDigest;
        public long seed;
        public double scale;
        public String from;
        public String to;
        public int customers;
        public int accountsPerCustomer;
        public String baseCurrency;
        public String customerAccountType;
        public String treasuryAccountType;
        public List<Cohort> cohorts;
        public String treasuryCustomerRef;
        public String treasuryAccountRef;

        // open
        public String accountType;
        public boolean treasury;

        // action
        public String date;
        public long seq;
        public long atMillis;
        public String cohort;
        public String operation;
        public String counterpartyRef;
        public String transferRef;
        public String holdRef;
        public long amountMinor;
        public String currency;

        // shared by open and action
        public String customerRef;
        public String accountRef;

        Header toHeader() {
            Map<String, Long> medians = new LinkedHashMap<>();
            if (cohorts != null) {
                for (Cohort cohort : cohorts) {
                    medians.put(cohort.id, cohort.medianAmountMinor);
                }
            }
            return new Header(
                    modelId,
                    modelVersion,
                    modelDigest,
                    seed,
                    scale,
                    LocalDate.parse(from),
                    LocalDate.parse(to),
                    customers,
                    accountsPerCustomer,
                    baseCurrency,
                    customerAccountType,
                    treasuryAccountType,
                    medians,
                    treasuryCustomerRef,
                    treasuryAccountRef);
        }

        OpenAccount toOpenAccount() {
            return new OpenAccount(
                    CustomerRef.of(customerRef),
                    AccountRef.of(accountRef),
                    AccountType.valueOf(accountType),
                    cohort == null ? "" : cohort,
                    treasury);
        }

        DrawnAction toAction() {
            return new DrawnAction(
                    LocalDate.parse(date),
                    seq,
                    atMillis,
                    cohort,
                    operation,
                    customerRef,
                    accountRef,
                    counterpartyRef,
                    transferRef,
                    holdRef,
                    amountMinor,
                    currency);
        }

        static final class Cohort {
            public String id;
            public long medianAmountMinor;
        }
    }
}
