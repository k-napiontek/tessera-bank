package bank.tessera.ledger.loader;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.loader.LedgerRows.AuditRow;
import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.AuditEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The audit trail a load leaves behind, verified the way {@code AuditChain} verifies it.
 *
 * <p>This is the unit-level half. The half that matters runs {@code AuditChain.verify()} itself
 * against a loaded database - see {@code LoadedLedgerTest} - because a chain that verifies in memory
 * and does not survive a round trip through {@code jsonb} is a chain nobody can audit.
 */
class ChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<AuditRow> chainOf() throws IOException {
        return DatasetLoaderTest.load().sink().audit;
    }

    @Test
    void everyAccountAndEveryEntryLeavesExactlyOneAuditRow() throws IOException {
        DatasetLoaderTest.Loaded loaded = DatasetLoaderTest.load();

        long accounts = loaded.summary().count(Counter.ACCOUNTS_OPENED);
        long entries = loaded.summary().count(Counter.ENTRIES);
        long holdTransitions = loaded.summary().count(Counter.HOLDS_PLACED)
                + loaded.summary().count(Counter.HOLDS_CAPTURED)
                + loaded.summary().count(Counter.HOLDS_RELEASED);

        assertThat(loaded.sink().audit).hasSize((int) (accounts + entries + holdTransitions));
        assertThat(loaded.summary().chainLength()).isEqualTo(loaded.sink().audit.size());
    }

    /**
     * F-43 in one assertion: every report bounds its postings by joining {@code journal_entry} to
     * {@code audit_record} on {@code subject_ref}, so an entry whose reference names no audit row is
     * invisible to every one of them - silently, and without breaking anything else.
     */
    @Test
    void everyEntryReferenceAppearsAsAnAuditSubject() throws IOException {
        DatasetLoaderTest.Loaded loaded = DatasetLoaderTest.load();

        Set<String> subjects = new HashSet<>();
        loaded.sink().audit.forEach(row -> subjects.add(row.subjectRef()));

        assertThat(loaded.sink().entries).isNotEmpty();
        assertThat(loaded.sink().entries)
                .allSatisfy(entry -> assertThat(subjects).contains(entry.reference()));
        assertThat(loaded.sink().accounts)
                .allSatisfy(account -> assertThat(subjects).contains(account.reference()));
    }

    /** The chain is a chain: each row names its predecessor's hash and the first names the genesis. */
    @Test
    void everyRowChainsOntoTheOneBeforeIt() throws IOException {
        List<AuditRow> chain = chainOf();

        assertThat(chain).isNotEmpty();
        String expected = AuditEntry.GENESIS_HASH;
        for (AuditRow row : chain) {
            assertThat(row.previousHash()).isEqualTo(expected);
            expected = row.hash();
        }
    }

    /**
     * Recomputed from the stored fields rather than compared against what the writer remembered,
     * which is exactly what {@code AuditChain.verify()} does - a row whose contents were altered
     * after it was written fails here.
     */
    @Test
    void everyRowsContentsHashToTheHashItRecords() throws IOException {
        for (AuditRow row : chainOf()) {
            AuditEntry rebuilt = AuditEntry.of(
                    row.occurredAt(),
                    row.actor(),
                    AuditAction.valueOf(row.action()),
                    row.subjectRef(),
                    row.correlationId(),
                    read(row.beforeState()),
                    read(row.afterState()));
            assertThat(rebuilt.hashWith(row.previousHash())).isEqualTo(row.hash());
        }
    }

    /** Two rows chaining onto one predecessor makes the trail a tree, which V7 refuses outright. */
    @Test
    void noTwoRowsChainOntoTheSamePredecessor() throws IOException {
        assertThat(chainOf())
                .extracting(AuditRow::previousHash)
                .doesNotHaveDuplicates();
        assertThat(chainOf()).extracting(AuditRow::hash).doesNotHaveDuplicates();
    }

    /** An audit row is retained for years, which makes it the worst possible place for a name. */
    @Test
    void theTrailCarriesReferencesAndAmountsAndNothingElse() throws IOException {
        Set<String> permitted = Set.of(
                "customerRef", "accountType", "currency", "status", "openedDate",
                "debitAccountRef", "creditAccountRef", "amountMinor", "valueDate",
                "reversesTransferRef", "reason", "accountRef", "capturedByTransferRef",
                "capturedAmountMinor", "transitionedAt");

        for (AuditRow row : chainOf()) {
            assertThat(read(row.beforeState()).keySet()).isSubsetOf(permitted);
            assertThat(read(row.afterState()).keySet()).isSubsetOf(permitted);
        }
    }

    private static Map<String, String> read(String state) throws IOException {
        return JSON.readValue(state, new TypeReference<Map<String, String>>() {});
    }
}
