package bank.tessera.ledger.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CustomerRef;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the loader does with a drawn transfer, and what it refuses to do.
 *
 * <p>Driven from hand-built records rather than from the sample stream, because the interesting cases
 * - an account that cannot cover a debit, a currency the estate cannot hold - are rare in a real draw
 * and a test that waited for one to turn up would be testing the draw.
 */
class TransferTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
    private static final String SENDER = "TB00000000000001";
    private static final String RECEIVER = "TB00000000000002";
    private static final String TREASURY = "TB00000000000099";

    private RecordingSink sink;
    private DatasetLoader loader;

    @BeforeEach
    void openAnEstate() {
        sink = new RecordingSink();
        loader = new DatasetLoader(sink, 1_000_000);
        loader.population(header());
        loader.open(new OpenAccount(
                CustomerRef.of("CU0000000099"), AccountRef.of(TREASURY), AccountType.ASSET, "", true));
        loader.open(new OpenAccount(
                CustomerRef.of("CU0000000001"), AccountRef.of(SENDER), AccountType.LIABILITY, "retail", false));
        loader.open(new OpenAccount(
                CustomerRef.of("CU0000000002"), AccountRef.of(RECEIVER), AccountType.LIABILITY, "retail", false));
    }

    private static Header header() {
        return new Header(
                "TB-WORKLOAD-DAY-V1",
                "1.0.0",
                "digest",
                42,
                1.0,
                DAY,
                DAY,
                2,
                1,
                "PLN",
                "LIABILITY",
                "ASSET",
                // 200 x 100 = an opening balance of 20 000 minor units on each customer account.
                Map.of("retail", 100L),
                "CU0000000099",
                TREASURY);
    }

    private static DrawnAction transfer(long amountMinor, String currency) {
        return new DrawnAction(
                DAY, 7, 3_600_000, "retail", "createTransfer",
                "CU0000000001", SENDER, RECEIVER, "TB202603020000000007", null, amountMinor, currency);
    }

    @Test
    void aTransferMovesTheSameAmountOffOneAccountAndOntoTheOther() {
        loader.action(transfer(5_000, "PLN"));

        assertThat(loader.counters()).containsEntry(Counter.TRANSFERS_POSTED, 1L);
        assertThat(sink.postings)
                .filteredOn(posting -> posting.entryRef().equals("TB202603020000000007"))
                .hasSize(2)
                .allSatisfy(posting -> assertThat(posting.amountMinor()).isEqualTo(5_000));

        // 200 x 100 opening, less 5 000 sent.
        assertThat(loader.require(SENDER).bookedMinor).isEqualTo(20_000 - 5_000);
        assertThat(loader.require(RECEIVER).bookedMinor).isEqualTo(20_000 + 5_000);
    }

    /**
     * The control the package's Constraints are about. Nothing in the schema stops a negative
     * balance, so this counter is the only thing between the dataset and a ledger full of rows the
     * ledger's own transfer path would have rejected.
     */
    @Test
    void aTransferTheLedgerWouldRefuseIsCountedAndNotWritten() {
        loader.action(transfer(20_001, "PLN"));

        assertThat(loader.counters()).containsEntry(Counter.REFUSED_INSUFFICIENT_FUNDS, 1L);
        assertThat(loader.counters()).doesNotContainKey(Counter.TRANSFERS_POSTED);
        assertThat(sink.entries).filteredOn(entry -> entry.reference().equals("TB202603020000000007")).isEmpty();
        assertThat(loader.require(SENDER).bookedMinor).isEqualTo(20_000);
    }

    /** Exactly to zero is permitted; a minor unit further is not. That boundary is the policy's. */
    @Test
    void aTransferOfTheWholeBalanceIsPermitted() {
        loader.action(transfer(20_000, "PLN"));

        assertThat(loader.counters()).containsEntry(Counter.TRANSFERS_POSTED, 1L);
        assertThat(loader.require(SENDER).bookedMinor).isZero();
    }

    /**
     * The estate is opened in one currency and the model draws from a mix of up to five. The
     * substitution is the honest option - the alternative is a dataset that is mostly rows the ledger
     * refuses - and it is counted so that nobody reads the currency column as the model's mix. F-72.
     */
    @Test
    void aTransferDrawnInAnotherCurrencyIsPostedInTheEstatesAndCounted() {
        loader.action(transfer(5_000, "EUR"));

        assertThat(loader.counters()).containsEntry(Counter.CURRENCY_SUBSTITUTED, 1L);
        assertThat(sink.postings)
                .filteredOn(posting -> posting.entryRef().equals("TB202603020000000007"))
                .allSatisfy(posting -> assertThat(posting.currency()).isEqualTo("PLN"));
    }

    @Test
    void aReadWritesNoRowAtAll() {
        int before = sink.postings.size();
        loader.action(new DrawnAction(
                DAY, 8, 0, "retail", "getBalance", "CU0000000001", SENDER, null, null, null, 0, null));

        assertThat(sink.postings).hasSize(before);
        assertThat(loader.counters()).containsEntry(Counter.READS_IGNORED, 1L);
    }

    @Test
    void aTransferToTheSameAccountIsRefusedLoudly() {
        DrawnAction toItself = new DrawnAction(
                DAY, 9, 0, "retail", "createTransfer",
                "CU0000000001", SENDER, SENDER, "TB202603020000000009", null, 100, "PLN");

        assertThatThrownBy(() -> loader.action(toItself))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("to itself");
    }

    @Test
    void theBusiestAccountIsTheOneTheDrawGaveTheMostPostings() {
        loader.action(transfer(1_000, "PLN"));
        loader.action(new DrawnAction(
                DAY, 8, 0, "retail", "createTransfer",
                "CU0000000002", RECEIVER, SENDER, "TB202603020000000008", null, 500, "PLN"));

        LoadSummary summary = loader.finish();

        // Both customer accounts have one opening leg and two transfer legs. The treasury has two
        // opening debits and is excluded outright: it carries a leg for every account in the estate,
        // so it wins every time and a statement page captured against it would be a plan of the
        // opening phase rather than of a customer's year.
        assertThat(summary.busiest().postings()).isEqualTo(3);
        assertThat(summary.busiest().accountRef()).isIn(SENDER, RECEIVER);
    }
}
