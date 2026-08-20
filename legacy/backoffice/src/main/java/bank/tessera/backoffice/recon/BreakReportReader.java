package bank.tessera.backoffice.recon;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * Reads {@code BREAKS-CCYYMMDD.json}, the report {@code batch/recon} writes.
 *
 * <p><strong>The server parses it, not the browser.</strong> WP-15's Constraints forbid single-page
 * behaviour, so the page is rendered from this rather than assembled by jQuery from the raw file.
 * The tempting shortcut - stream the file and let the browser do the work - would need no JSON
 * library at stratum 1 at all, and would make this a single-page application in everything but name.
 *
 * <p><strong>A document that does not match the contract is refused.</strong>
 * {@code contracts/recon/break-report-v1.md} names every field, and a screen that renders whatever
 * it is handed will one day render a file it does not understand and say nothing. The format id is
 * checked first, because that is the field whose whole job is to make this cheap.
 *
 * <p>Jackson 1.x - {@code org.codehaus.jackson} - because that is the release a 2011 Java shop had.
 * Jackson 2 is a different package and a different era; using it here would date this code wrongly.
 */
public final class BreakReportReader {

    /** The only format this screen understands. */
    public static final String FORMAT_ID = "TB-RECON-BREAKS-V1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BreakReportReader() {
    }

    /** The report for a business date, or null when the reconciliation has not run for it. */
    public static BreakReport readFor(File directory, String businessDate) throws IOException {
        File path = new File(directory, "BREAKS-" + businessDate + ".json");
        if (!path.isFile()) {
            return null;
        }
        return read(path);
    }

    public static BreakReport read(File path) throws IOException {
        JsonNode root = MAPPER.readTree(path);

        String formatId = text(root, "formatId", path);
        if (!FORMAT_ID.equals(formatId)) {
            throw new IOException(path + " is format " + formatId + ", and this screen renders "
                    + FORMAT_ID + " only. It is not displayed: a break list drawn from a document"
                    + " nobody has checked is a break list nobody can rely on.");
        }

        JsonNode cutOff = object(root, "cutOff", path);
        JsonNode masterFile = object(root, "masterFile", path);
        JsonNode totals = object(root, "totals", path);

        List<Break> breaks = new ArrayList<Break>();
        JsonNode array = root.get("breaks");
        if (array == null || !array.isArray()) {
            throw new IOException(path + " has no breaks array");
        }
        for (int i = 0; i < array.size(); i++) {
            breaks.add(toBreak(array.get(i), path));
        }

        int compared = number(totals, "accountsCompared", path);
        int matched = number(totals, "accountsMatched", path);
        int broken = number(totals, "accountsBroken", path);
        if (compared != matched + broken) {
            throw new IOException(path + " has control totals that do not balance: " + compared
                    + " compared, " + matched + " matched, " + broken + " broken. The writer"
                    + " refuses to produce one, so this file has been altered since it was written.");
        }
        if (broken != breaks.size()) {
            throw new IOException(path + " says " + broken + " breaks and carries " + breaks.size());
        }

        return new BreakReport(
                text(root, "businessDate", path),
                longValue(root, "ledgerPosition", path),
                text(root, "ledgerChainHash", path),
                text(cutOff, "movementFile", path),
                number(cutOff, "transferRefCount", path),
                text(masterFile, "name", path),
                number(masterFile, "recordCount", path),
                breaks,
                compared,
                matched,
                broken,
                longValue(totals, "totalAbsoluteDriftMinor", path));
    }

    private static Break toBreak(JsonNode node, File path) throws IOException {
        String raw = text(node, "classification", path);
        Classification classification;
        try {
            classification = Classification.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            // A classification this screen has never heard of means the contract moved and this
            // module did not. Rendering it as "other" would hide exactly that.
            throw new IOException(path + " carries classification " + raw
                    + ", which this screen does not know. The break report contract has changed.");
        }
        return new Break(
                text(node, "accountRef", path),
                classification,
                text(node, "currency", path),
                nullableLong(node, "masterBookedMinor"),
                nullableLong(node, "ledgerBookedMinor"),
                nullableLong(node, "differenceMinor"));
    }

    private static JsonNode object(JsonNode parent, String field, File path) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            throw new IOException(path + " has no " + field + " object");
        }
        return node;
    }

    private static String text(JsonNode parent, String field, File path) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw new IOException(path + " has no " + field);
        }
        return node.getTextValue();
    }

    private static int number(JsonNode parent, String field, File path) throws IOException {
        return (int) longValue(parent, field, path);
    }

    private static long longValue(JsonNode parent, String field, File path) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new IOException(path + " has no integral " + field);
        }
        return node.getLongValue();
    }

    private static Long nullableLong(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return Long.valueOf(node.getLongValue());
    }
}
