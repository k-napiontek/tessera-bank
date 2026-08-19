package bank.tessera.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftNotPermittedException;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.AuditEntry;
import bank.tessera.ledger.port.TransferPostedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every money-moving use case leaves a record, and no use case leaves one for work it did not do.
 *
 * <p>Driven through the in-memory fakes, so what is asserted is <em>which</em> entries each use case
 * appends and what they contain. Nothing here rolls back, which makes the negative assertions
 * sharper rather than weaker: a use case that recorded a rejected transfer would be caught by a test
 * that never has to simulate a transaction at all. The claim that a rollback removes a committed
 * audit row is a database claim, and it is made in {@code ledger-persistence}.
 */
class AuditTrailTest {

    private static final AccountRef VAULT = AccountRef.of("TB00000000000009");
    private static final AccountRef ALICE = AccountRef.of("TB00000000000001");
    private static final AccountRef BOB = AccountRef.of("TB00000000000002");
    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final Instant NOW = Instant.parse("2026-08-19T09:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private InMemoryLedger ledger;
    private OpenAccount openAccount;
    private Transfer transfer;
    private ReverseTransfer reverseTransfer;
    private PlaceHold placeHold;
    private ReleaseHold releaseHold;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        InMemoryLedger.SequentialReferences references = new InMemoryLedger.SequentialReferences();
        openAccount =
                new OpenAccount(ledger.accounts, ledger.readModel, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        transfer = new Transfer(
                ledger.accounts,
                ledger.entries,
                ledger.readModel,
                references,
                ledger.unitOfWork,
                ledger.auditTrail(FIXED),
                ledger.transferEvents(),
                FIXED);
        reverseTransfer = new ReverseTransfer(
                ledger.accounts,
                ledger.entries,
                ledger.readModel,
                references,
                ledger.unitOfWork,
                ledger.auditTrail(FIXED),
                ledger.transferEvents(),
                FIXED);
        placeHold = new PlaceHold(
                ledger.accounts, ledger.holds, references, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        releaseHold = new ReleaseHold(ledger.holds, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);

        open(VAULT, AccountType.ASSET);
        open(ALICE, AccountType.LIABILITY);
        open(BOB, AccountType.LIABILITY);
        transfer.execute(new Transfer.Command(VAULT, ALICE, Money.of(500_00, PLN), null, null));
        ledger.auditEntries.clear();
        ledger.outboxEvents.clear();
    }

    private void open(AccountRef reference, AccountType type) {
        openAccount.open(new OpenAccount.Command(
                reference, CustomerRef.of("CU0000000001"), type, PLN, null, OverdraftPolicy.forbidden()));
    }

    private AuditEntry only() {
        assertThat(ledger.auditEntries).hasSize(1);
        return ledger.auditEntries.get(0);
    }

    @Test
    @DisplayName("opening an account records what was opened, under the caller's correlation id")
    void openingIsRecorded() {
        ledger.auditEntries.clear();
        open(AccountRef.of("TB00000000000005"), AccountType.LIABILITY);

        AuditEntry recorded = only();
        assertThat(recorded.action()).isEqualTo(AuditAction.ACCOUNT_OPENED);
        assertThat(recorded.subject()).isEqualTo("TB00000000000005");
        assertThat(recorded.actor()).isEqualTo("ledger-api");
        assertThat(recorded.correlationId()).contains(InMemoryLedger.CORRELATION_ID);
        assertThat(recorded.occurredAt()).isEqualTo(NOW);
        assertThat(recorded.before()).isEmpty();
        assertThat(recorded.after())
                .containsEntry("status", "OPEN")
                .containsEntry("currency", "PLN")
                .containsEntry("accountType", "LIABILITY");
    }

    @Test
    @DisplayName("a posted transfer records both accounts and the amount in minor units")
    void aPostedTransferIsRecorded() {
        transfer.execute(new Transfer.Command(ALICE, BOB, Money.of(40_00, PLN), null, null));

        AuditEntry recorded = only();
        assertThat(recorded.action()).isEqualTo(AuditAction.TRANSFER_POSTED);
        assertThat(recorded.after())
                .containsEntry("debitAccountRef", ALICE.value())
                .containsEntry("creditAccountRef", BOB.value())
                .containsEntry("amountMinor", "4000")
                .containsEntry("currency", "PLN");
    }

    @Test
    @DisplayName("a rejected transfer records nothing at all")
    void aRejectedTransferIsNotRecorded() {
        // The audit append is the last thing a use case does, after the entry exists. A design that
        // recorded the attempt first would leave a row saying money moved when none did - which is a
        // worse defect than no audit trail, because it is a trail that lies.
        assertThatThrownBy(() -> transfer.execute(
                        new Transfer.Command(ALICE, BOB, Money.of(10_000_00, PLN), null, null)))
                .isInstanceOf(OverdraftNotPermittedException.class);

        assertThat(ledger.auditEntries).isEmpty();
    }

    @Test
    @DisplayName("a reversal records why, and which transfer it reverses")
    void aReversalIsRecorded() {
        var posted = transfer.execute(new Transfer.Command(ALICE, BOB, Money.of(10_00, PLN), null, null));
        ledger.auditEntries.clear();

        reverseTransfer.execute(new ReverseTransfer.Command(
                posted.transferReference(), "duplicate instruction", null));

        AuditEntry recorded = only();
        assertThat(recorded.action()).isEqualTo(AuditAction.TRANSFER_REVERSED);
        assertThat(recorded.after())
                .containsEntry("reversesTransferRef", posted.transferReference().value())
                .containsEntry("reason", "duplicate instruction");
    }

    @Test
    @DisplayName("releasing a hold records the status change and when the reservation ended")
    void aReleaseRecordsBeforeAndAfter() {
        Hold placed = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(20_00, PLN), null, null));
        ledger.auditEntries.clear();

        releaseHold.execute(placed.reference());

        AuditEntry recorded = only();
        assertThat(recorded.action()).isEqualTo(AuditAction.HOLD_RELEASED);
        assertThat(recorded.before()).containsEntry("status", "PLACED");
        assertThat(recorded.after())
                .containsEntry("status", "RELEASED")
                // F-21's instant. Before WP-09 the aggregate discarded it and this row could only
                // have said when the hold was placed, which is not what an auditor asked.
                .containsEntry("transitionedAt", NOW.toString());
    }

    @Test
    @DisplayName("a posted transfer enqueues exactly one event, shaped by the AsyncAPI contract")
    void aPostedTransferIsEnqueued() {
        var posted = transfer.execute(new Transfer.Command(ALICE, BOB, Money.of(40_00, PLN), null, "INV-1"));

        assertThat(ledger.outboxEvents).hasSize(1);
        TransferPostedEvent event = ledger.outboxEvents.get(0);
        assertThat(event.transferRef()).isEqualTo(posted.transferReference().value());
        assertThat(event.debitAccountRef()).isEqualTo(ALICE.value());
        assertThat(event.creditAccountRef()).isEqualTo(BOB.value());
        assertThat(event.amount()).isEqualTo(Money.of(40_00, PLN));
        assertThat(event.correlationId()).isEqualTo(InMemoryLedger.CORRELATION_ID);
        assertThat(event.reversesTransferRef()).isNull();
        assertThat(event.movements()).hasSize(2);
        assertThat(event.movements().get(0).legNo()).isEqualTo(1);
        assertThat(event.movements().get(0).direction()).isEqualTo("DEBIT");
        assertThat(event.movements().get(0).movementRef())
                .isEqualTo(posted.transferReference().value() + "-01");
        assertThat(event.movements().get(1).direction()).isEqualTo("CREDIT");
    }

    @Test
    @DisplayName("a rejected transfer enqueues nothing")
    void aRejectedTransferIsNotEnqueued() {
        assertThatThrownBy(() -> transfer.execute(
                        new Transfer.Command(ALICE, BOB, Money.of(10_000_00, PLN), null, null)))
                .isInstanceOf(OverdraftNotPermittedException.class);

        assertThat(ledger.outboxEvents).isEmpty();
    }

    @Test
    @DisplayName("a reversal is announced on the same channel, naming what it reverses")
    void aReversalIsEnqueued() {
        var posted = transfer.execute(new Transfer.Command(ALICE, BOB, Money.of(10_00, PLN), null, null));
        ledger.outboxEvents.clear();

        reverseTransfer.execute(new ReverseTransfer.Command(
                posted.transferReference(), "duplicate instruction", null));

        assertThat(ledger.outboxEvents).hasSize(1);
        assertThat(ledger.outboxEvents.get(0).reversesTransferRef())
                .isEqualTo(posted.transferReference().value());
    }

    @Test
    @DisplayName("no audit row carries the remittance reference a payer supplied")
    void freeTextIsNeverRecorded() {
        // The one field a paying customer controls. The canonical model classifies it restricted if
        // misused, and an audit row outlives every other copy of it.
        String supplied = "SYNTHETIC-MARKER-DO-NOT-RECORD";
        transfer.execute(new Transfer.Command(ALICE, BOB, Money.of(5_00, PLN), null, supplied));
        Hold placed = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(5_00, PLN), null, supplied));
        releaseHold.execute(placed.reference());

        assertThat(ledger.auditEntries).isNotEmpty();
        List<String> everything = ledger.auditEntries.stream()
                .flatMap(entry -> java.util.stream.Stream.concat(
                        entry.before().values().stream(), entry.after().values().stream()))
                .toList();
        assertThat(everything).doesNotContain(supplied);
    }
}
