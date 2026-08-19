package bank.tessera.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.domain.Posting;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading accounts, balances, transfers and holds. */
class AccountReadUseCasesTest {

    private static final AccountRef ALICE = AccountRef.of("TB00000000000001");
    private static final AccountRef BOB = AccountRef.of("TB00000000000002");
    private static final AccountRef UNKNOWN = AccountRef.of("TB00000000009999");
    private static final CustomerRef CUSTOMER = CustomerRef.of("CU0000000001");
    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final Instant AT = Instant.parse("2026-08-19T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(AT, ZoneOffset.UTC);

    private InMemoryLedger ledger;
    private InMemoryLedger.SequentialReferences references;
    private GetAccount getAccount;
    private GetBalance getBalance;
    private GetTransfer getTransfer;
    private ListHolds listHolds;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        references = new InMemoryLedger.SequentialReferences();
        OpenAccount openAccount =
                new OpenAccount(ledger.accounts, ledger.readModel, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        openAccount.open(open(ALICE, LocalDate.of(2026, 3, 1)));
        openAccount.open(open(BOB, LocalDate.of(2026, 3, 2)));

        getAccount =
                new GetAccount(ledger.accounts, ledger.entries, ledger.readModel, ledger.unitOfWork);
        getBalance = new GetBalance(ledger.accounts, ledger.entries, ledger.unitOfWork);
        getTransfer = new GetTransfer(ledger.entries, ledger.readModel, ledger.unitOfWork);
        listHolds = new ListHolds(ledger.accounts, ledger.holds, ledger.unitOfWork);
    }

    private OpenAccount.Command open(AccountRef reference, LocalDate openedDate) {
        return new OpenAccount.Command(
                reference, CUSTOMER, AccountType.LIABILITY, PLN, openedDate, OverdraftPolicy.forbidden());
    }

    @Test
    @DisplayName("an account comes back with its dates and both balances")
    void anAccountCarriesItsDatesAndBalances() {
        AccountView view = getAccount.byReference(ALICE).orElseThrow();

        assertThat(view.account().customer()).isEqualTo(CUSTOMER);
        assertThat(view.dates().opened()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(view.balance().booked()).isEqualTo(Money.zero(PLN));
    }

    @Test
    @DisplayName("an unknown account is absent, which is not a balance of zero")
    void anUnknownAccountIsAbsent() {
        // A ledger that answered "zero" here would tell a caller an account exists and is empty.
        // Absent and empty are different facts, and only one of them is true.
        assertThat(getAccount.byReference(UNKNOWN)).isEmpty();
        assertThat(getBalance.of(UNKNOWN)).isEmpty();
        assertThat(listHolds.on(UNKNOWN, true)).isEmpty();
    }

    @Test
    @DisplayName("available balance drops by an active hold and booked balance does not")
    void availableDropsByAnActiveHold() {
        ledger.holds.save(Hold.place(references.nextHoldReference(), ALICE, Money.of(2_500, PLN), AT, null));

        Balance balance = getBalance.of(ALICE).orElseThrow();

        assertThat(balance.booked()).isEqualTo(Money.zero(PLN));
        assertThat(balance.available()).isEqualTo(Money.of(-2_500, PLN));
    }

    @Test
    @DisplayName("a released hold reserves nothing")
    void aReleasedHoldReservesNothing() {
        ledger.holds.save(
                Hold.place(references.nextHoldReference(), ALICE, Money.of(2_500, PLN), AT, null).release(AT));

        assertThat(getBalance.of(ALICE).orElseThrow().available()).isEqualTo(Money.zero(PLN));
    }

    @Test
    @DisplayName("holds are active-only by default and complete when asked")
    void listingHonoursIncludeInactive() {
        Hold active =
                Hold.place(references.nextHoldReference(), ALICE, Money.of(100, PLN), AT, null);
        Hold released =
                Hold.place(references.nextHoldReference(), ALICE, Money.of(200, PLN), AT, null).release(AT);
        ledger.holds.save(active);
        ledger.holds.save(released);

        Optional<List<Hold>> activeOnly = listHolds.on(ALICE, false);
        Optional<List<Hold>> everything = listHolds.on(ALICE, true);

        assertThat(activeOnly).contains(List.of(active));
        assertThat(everything.orElseThrow()).hasSize(2);
    }

    @Test
    @DisplayName("a posted transfer reports POSTED, with its entry and posting instant")
    void aPostedTransferReportsPosted() {
        EntryRef reference = references.nextEntryReference();
        ledger.entries.append(JournalEntry.of(
                reference,
                LocalDate.of(2026, 8, 19),
                List.of(
                        Posting.of(ALICE, Direction.DEBIT, Money.of(10_00, PLN)),
                        Posting.of(BOB, Direction.CREDIT, Money.of(10_00, PLN)))));

        TransferView view = getTransfer.byReference(reference).orElseThrow();

        assertThat(view.transferReference()).isEqualTo(reference);
        assertThat(view.status()).isEqualTo(TransferStatus.POSTED);
        assertThat(view.reversedBy()).isEmpty();
        assertThat(view.entry().postings()).hasSize(2);
    }

    @Test
    @DisplayName("a transfer another entry reverses reports REVERSED")
    void aReversedTransferReportsReversed() {
        EntryRef original = references.nextEntryReference();
        JournalEntry entry = JournalEntry.of(
                original,
                LocalDate.of(2026, 8, 19),
                List.of(
                        Posting.of(ALICE, Direction.DEBIT, Money.of(10_00, PLN)),
                        Posting.of(BOB, Direction.CREDIT, Money.of(10_00, PLN))));
        ledger.entries.append(entry);

        EntryRef correction = references.nextEntryReference();
        ledger.entries.append(entry.reverse(correction, LocalDate.of(2026, 8, 20)));

        // The original is never mutated, so the only way to know it was reversed is to find the
        // entry pointing back at it. Reading the status off the original would always say POSTED.
        assertThat(getTransfer.byReference(original).orElseThrow().status())
                .isEqualTo(TransferStatus.REVERSED);
        assertThat(getTransfer.byReference(original).orElseThrow().reversedBy()).contains(correction);
        assertThat(getTransfer.byReference(correction).orElseThrow().status())
                .isEqualTo(TransferStatus.POSTED);
    }

    @Test
    @DisplayName("an unknown transfer is absent")
    void anUnknownTransferIsAbsent() {
        assertThat(getTransfer.byReference(EntryRef.of("TB202608190000099999"))).isEmpty();
    }
}
