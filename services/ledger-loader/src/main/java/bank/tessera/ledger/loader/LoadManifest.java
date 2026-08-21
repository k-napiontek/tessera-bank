package bank.tessera.ledger.loader;

import java.util.Map;
import java.util.TreeMap;

/**
 * What a load produced, in a form somebody can compare two of.
 *
 * <p>The same shape of artefact as WP-20's run manifest and for the same reason: a dataset nobody can
 * reproduce is a dataset nobody can argue with. It carries the dials it was produced at, the digest
 * of every row it wrote, the substitutions it had to make, and the account the deep-cursor query plan
 * should be captured against.
 *
 * @param datasetDigest SHA-256 over every row written, in write order
 * @param chainHead the last audit hash, which says which chain a later report's figures came from
 * @param treasuryAccountRef the bank's own account, which every opening balance was debited from -
 *     it carries one leg per account in the estate and is excluded from busiestAccountRef for that
 *     reason
 * @param busiestAccountRef the customer account the draw gave the most postings, named rather than
 *     chosen
 */
public record LoadManifest(
        String formatId,
        String modelId,
        String modelVersion,
        String modelDigest,
        long seed,
        double scale,
        String from,
        String to,
        String openingDate,
        int customers,
        int accountsPerCustomer,
        String baseCurrency,
        String treasuryAccountRef,
        long openingMultiple,
        Map<String, Long> counters,
        long rowsWritten,
        String datasetDigest,
        long chainLength,
        String chainHead,
        String busiestAccountRef,
        long busiestAccountPostings,
        long loadSeconds) {

    /** The manifest format, versioned like every other artefact in this estate. */
    public static final String FORMAT_ID = "TB-LEDGER-DATASET-V1";

    public static LoadManifest of(
            LoadSummary summary, String datasetDigest, long rowsWritten, long loadSeconds) {
        Header header = summary.header();
        Map<String, Long> counters = new TreeMap<>();
        summary.counters().forEach((counter, value) -> counters.put(counter.name(), value));
        return new LoadManifest(
                FORMAT_ID,
                header.modelId(),
                header.modelVersion(),
                header.modelDigest(),
                header.seed(),
                header.scale(),
                header.from().toString(),
                header.to().toString(),
                header.openingDate().toString(),
                header.customers(),
                header.accountsPerCustomer(),
                header.baseCurrency(),
                header.treasuryAccountRef(),
                DatasetLoader.OPENING_MULTIPLE,
                counters,
                rowsWritten,
                datasetDigest,
                summary.chainLength(),
                summary.chainHead(),
                summary.busiest().accountRef(),
                summary.busiest().postings(),
                loadSeconds);
    }
}
