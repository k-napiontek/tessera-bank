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
 * Holds: reserving against available balance, capturing into a transfer, and releasing.
 *
 * <p>A hold moves no money until it is captured. The distinction between booked and available is
 * the whole point, and every test here is about keeping the two from being counted twice.
 */
class HoldTest {

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
    private PlaceHold placeHold;
    private CaptureHold captureHold;
    private ReleaseHold releaseHold;

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
        placeHold = new PlaceHold(ledger.accounts, ledger.holds, references, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        captureHold = new CaptureHold(ledger.holds, transfer, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        releaseHold = new ReleaseHold(ledger.holds, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);

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
    @DisplayName("a placed hold reduces available balance and leaves booked alone")
    void aHoldReservesWithoutMoving() {
        Money bookedBefore = ledger.entries.balanceOf(ALICE).booked();

        Hold hold = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(120_00, PLN), null, null));

        assertThat(hold.status()).isEqualTo(HoldStatus.PLACED);
        assertThat(ledger.entries.balanceOf(ALICE).booked()).isEqualTo(bookedBefore);
        assertThat(ledger.entries.balanceOf(ALICE).available())
                .isEqualTo(bookedBefore.minus(Money.of(120_00, PLN)));
    }

    @Test
    @DisplayName("capturing moves booked balance exactly once and clears the reservation")
    void captureMovesTheMoneyOnce() {
        Money bookedBefore = ledger.entries.balanceOf(ALICE).booked();
        Hold hold = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(120_00, PLN), null, null));

        TransferView posted = captureHold.execute(
                new CaptureHold.Command(hold.reference(), BOB, Money.of(120_00, PLN), null));

        Money expected = bookedBefore.minus(Money.of(120_00, PLN));
        assertThat(ledger.entries.balanceOf(ALICE).booked()).isEqualTo(expected);
        // The reservation is gone, so available equals booked again. Leaving the hold PLACED
        // would charge the customer twice: once in the books and once in what they may spend.
        assertThat(ledger.entries.balanceOf(ALICE).available()).isEqualTo(expected);
        assertThat(ledger.holdsByRef.get(hold.reference()).status()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(ledger.holdsByRef.get(hold.reference()).capturedBy())
                .contains(posted.transferReference());
    }

    @Test
    @DisplayName("a partial capture is allowed and the remainder is not reserved afterwards")
    void aPartialCaptureIsAllowed() {
        Hold hold = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(120_00, PLN), null, null));

        captureHold.execute(new CaptureHold.Command(hold.reference(), BOB, Money.of(80_00, PLN), null));

        assertThat(ledger.entries.balanceOf(ALICE).booked()).isEqualTo(Money.of(420_00, PLN));
        assertThat(ledger.entries.balanceOf(ALICE).available()).isEqualTo(Money.of(420_00, PLN));
    }

    @Test
    @DisplayName("capturing more than was reserved is refused")
    void anOverCaptureIsRefused() {
        Hold hold = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(100_00, PLN), null, null));

        assertThatThrownBy(() -> captureHold.execute(
                        new CaptureHold.Command(hold.reference(), BOB, Money.of(100_01, PLN), null)))
                .isInstanceOf(NotActionableException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("capturing a hold twice is refused, and the second capture posts nothing")
    void capturingTwiceIsRefused() {
        Hold hold = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(100_00, PLN), null, null));
        captureHold.execute(new CaptureHold.Command(hold.reference(), BOB, Money.of(100_00, PLN), null));
        int entriesAfterFirst = ledger.entriesByRef.size();

        assertThatThrownBy(() -> captureHold.execute(
                        new CaptureHold.Command(hold.reference(), BOB, Money.of(100_00, PLN), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.entriesByRef).hasSize(entriesAfterFirst);
    }

    @Test
    @DisplayName("releasing returns the reservation and moves no money")
    void releasingReturnsTheReservation() {
        Money bookedBefore = ledger.entries.balanceOf(ALICE).booked();
        Hold hold = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(120_00, PLN), null, null));

        Hold released = releaseHold.execute(hold.reference());

        assertThat(released.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(ledger.entries.balanceOf(ALICE).booked()).isEqualTo(bookedBefore);
        assertThat(ledger.entries.balanceOf(ALICE).available()).isEqualTo(bookedBefore);
    }

    @Test
    @DisplayName("releasing a captured hold is refused")
    void releasingACapturedHoldIsRefused() {
        Hold hold = placeHold.execute(
                new PlaceHold.Command(ALICE, Money.of(100_00, PLN), null, null));
        captureHold.execute(new CaptureHold.Command(hold.reference(), BOB, Money.of(100_00, PLN), null));

        assertThatThrownBy(() -> releaseHold.execute(hold.reference()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an unknown hold is not found")
    void anUnknownHoldIsNotFound() {
        assertThatThrownBy(() -> releaseHold.execute(HoldRef.of("HL202608190000099999")))
                .isInstanceOf(HoldNotFoundException.class);
    }

    @Test
    @DisplayName("a hold in the wrong currency, on a blocked account, or of nothing, is refused")
    void invalidHoldsAreRefused() {
        assertThatThrownBy(() -> placeHold.execute(
                        new PlaceHold.Command(ALICE, Money.of(1_00, EUR), null, null)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> placeHold.execute(
                        new PlaceHold.Command(ALICE, Money.of(0, PLN), null, null)))
                .isInstanceOf(NotActionableException.class);

        ledger.accounts.save(ledger.accountsByRef.get(ALICE).withStatus(AccountStatus.BLOCKED));
        assertThatThrownBy(() -> placeHold.execute(
                        new PlaceHold.Command(ALICE, Money.of(1_00, PLN), null, null)))
                .isInstanceOf(NotActionableException.class);
    }
}
