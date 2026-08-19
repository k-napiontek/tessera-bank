package bank.tessera.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CurrencyMismatchException;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.domain.HoldStatus;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.domain.Posting;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reversing a transfer.
 *
 * <p>A correction is a new entry that names what it corrects. The original is never mutated and
 * never deleted, so the books record both that a mistake was made and that it was put right.
 */
class ReverseTransferTest {

    private static final AccountRef ALICE = AccountRef.of("TB00000000000001");
    private static final AccountRef BOB = AccountRef.of("TB00000000000002");
    private static final AccountRef CAROL = AccountRef.of("TB00000000000003");
    private static final AccountRef EURO = AccountRef.of("TB00000000000004");
    /** The bank's own cash account. A deposit raises an asset and a liability together. */
    private static final AccountRef VAULT = AccountRef.of("TB00000000000009");
    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Instant NOW = Instant.parse("2026-08-19T09:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private InMemoryLedger ledger;
    private Transfer transfer;
    private ReverseTransfer reverseTransfer;
    private GetTransfer getTransfer;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        OpenAccount openAccount =
                new OpenAccount(ledger.accounts, ledger.readModel, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        openAccount.open(open(ALICE, PLN));
        openAccount.open(open(BOB, PLN));
        openAccount.open(open(CAROL, PLN));
        openAccount.open(open(EURO, EUR));
        openAccount.open(new OpenAccount.Command(
                VAULT,
                CustomerRef.of("CU0000000000"),
                AccountType.ASSET,
                PLN,
                null,
                OverdraftPolicy.forbidden()));

        InMemoryLedger.SequentialReferences references = new InMemoryLedger.SequentialReferences();
        transfer = new Transfer(
                ledger.accounts, ledger.entries, ledger.readModel, references, ledger.unitOfWork,
                ledger.auditTrail(FIXED), ledger.transferEvents(), FIXED);
        reverseTransfer = new ReverseTransfer(
                ledger.accounts, ledger.entries, ledger.readModel, references, ledger.unitOfWork,
                ledger.auditTrail(FIXED), ledger.transferEvents(), FIXED);
        getTransfer = new GetTransfer(ledger.entries, ledger.readModel, ledger.unitOfWork);

        fund(ALICE, 500_00);
    }

    private OpenAccount.Command open(AccountRef reference, CurrencyCode currency) {
        return new OpenAccount.Command(
                reference,
                CustomerRef.of("CU0000000001"),
                AccountType.LIABILITY,
                currency,
                null,
                OverdraftPolicy.forbidden());
    }

    /**
     * Pays money in from the bank's own cash account, the way a deposit actually posts: the vault is
     * an ASSET and rises on a debit, the customer account is a LIABILITY and rises on a credit.
     * Funding out of another customer would leave that customer overdrawn, and every later assertion
     * would then be about the wrong thing.
     */
    private void fund(AccountRef account, long minor) {
        ledger.entries.append(JournalEntry.of(
                EntryRef.of("TB202608180000000001"),
                LocalDate.of(2026, 8, 18),
                List.of(
                        Posting.of(VAULT, Direction.DEBIT, Money.of(minor, PLN)),
                        Posting.of(account, Direction.CREDIT, Money.of(minor, PLN)))));
    }


    @Test
    @DisplayName("posts the opposite entry, names the original, and leaves it untouched")
    void postsTheOpposite() {
        TransferView original = transfer.execute(
                new Transfer.Command(ALICE, BOB, Money.of(40_00, PLN), null, null));
        Money afterTransfer = ledger.entries.balanceOf(ALICE).booked();

        TransferView reversal = reverseTransfer.execute(
                new ReverseTransfer.Command(original.transferReference(), "keyed in error", null));

        assertThat(reversal.entry().reverses()).contains(original.transferReference());
        assertThat(reversal.debitAccount()).isEqualTo(BOB);
        assertThat(reversal.creditAccount()).isEqualTo(ALICE);
        assertThat(ledger.entries.balanceOf(ALICE).booked())
                .isEqualTo(afterTransfer.plus(Money.of(40_00, PLN)));

        // The original entry is still exactly what it was. A correction that edited it would
        // leave no evidence that a mistake was ever made.
        assertThat(ledger.entriesByRef.get(original.transferReference()).postings())
                .isEqualTo(original.entry().postings());
    }

    @Test
    @DisplayName("flips the original's status to REVERSED without touching the original")
    void theOriginalReportsReversed() {
        TransferView original = transfer.execute(
                new Transfer.Command(ALICE, BOB, Money.of(10_00, PLN), null, null));

        reverseTransfer.execute(
                new ReverseTransfer.Command(original.transferReference(), "duplicate", null));

        assertThat(getTransfer.byReference(original.transferReference()).orElseThrow().status())
                .isEqualTo(TransferStatus.REVERSED);
    }

    @Test
    @DisplayName("reversing twice is refused")
    void reversingTwiceIsRefused() {
        TransferView original = transfer.execute(
                new Transfer.Command(ALICE, BOB, Money.of(10_00, PLN), null, null));
        reverseTransfer.execute(
                new ReverseTransfer.Command(original.transferReference(), "duplicate", null));

        // A second reversal balances perfectly and credits the account twice for one error, so
        // nothing downstream would report it. The unique index on journal_entry.reverses says
        // the same thing in the database, because two concurrent requests both pass this check.
        assertThatThrownBy(() -> reverseTransfer.execute(
                        new ReverseTransfer.Command(original.transferReference(), "again", null)))
                .isInstanceOf(AlreadyReversedException.class);
    }

    @Test
    @DisplayName("a reversal whose own debit would breach a policy is refused")
    void aReversalIsNotExemptFromTheOverdraftPolicy() {
        TransferView original = transfer.execute(
                new Transfer.Command(ALICE, BOB, Money.of(500_00, PLN), null, null));
        // BOB spends the money onward, so BOB is back at zero and a reversal would debit an
        // account with nothing in it. The correction is still owed - but it cannot be taken out
        // of an account whose policy forbids an overdraft, and pretending otherwise would move
        // the problem rather than report it.
        transfer.execute(new Transfer.Command(BOB, CAROL, Money.of(500_00, PLN), null, null));

        assertThatThrownBy(() -> reverseTransfer.execute(
                        new ReverseTransfer.Command(original.transferReference(), "recall", null)))
                .isInstanceOf(bank.tessera.ledger.domain.OverdraftNotPermittedException.class);
    }

    @Test
    @DisplayName("an unknown transfer cannot be reversed")
    void anUnknownTransferIsNotFound() {
        assertThatThrownBy(() -> reverseTransfer.execute(new ReverseTransfer.Command(
                        EntryRef.of("TB202608190000099999"), "whatever", null)))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    @DisplayName("a reversal must state a reason")
    void aReasonIsRequired() {
        assertThatThrownBy(() -> new ReverseTransfer.Command(
                        EntryRef.of("TB202608190000000001"), "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must state a reason");
    }
}
