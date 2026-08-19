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
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftNotPermittedException;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.domain.Posting;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transfer use case: locking, the overdraft policy and the append, composed.
 *
 * <p>Follow-up F-22 is what this class exists to close, and
 * {@link #aForbiddenOverdraftIsRefusedAndNothingIsWritten} is the assertion that closes it.
 */
class TransferTest {

    private static final AccountRef ALICE = AccountRef.of("TB00000000000001");
    private static final AccountRef BOB = AccountRef.of("TB00000000000002");
    private static final AccountRef EURO = AccountRef.of("TB00000000000003");
    private static final AccountRef UNKNOWN = AccountRef.of("TB00000000009999");
    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-19T09:00:00Z"), ZoneOffset.UTC);

    private InMemoryLedger ledger;
    private Transfer transfer;
    private OpenAccount openAccount;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        openAccount = new OpenAccount(ledger.accounts, ledger.readModel, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        openAccount.open(open(ALICE, PLN, OverdraftPolicy.forbidden()));
        openAccount.open(open(BOB, PLN, OverdraftPolicy.forbidden()));
        openAccount.open(open(EURO, EUR, OverdraftPolicy.forbidden()));
        transfer = new Transfer(
                ledger.accounts,
                ledger.entries,
                ledger.readModel,
                new InMemoryLedger.SequentialReferences(),
                ledger.unitOfWork,
                ledger.auditTrail(FIXED),
                ledger.transferEvents(),
                FIXED);
    }

    private OpenAccount.Command open(AccountRef reference, CurrencyCode currency, OverdraftPolicy policy) {
        return new OpenAccount.Command(
                reference, CustomerRef.of("CU0000000001"), AccountType.LIABILITY, currency, null, policy);
    }

    /** Puts money into an account without going through the use case, so a debit has something to take. */
    private void fund(AccountRef account, long minor, CurrencyCode currency) {
        ledger.entries.append(JournalEntry.of(
                EntryRef.of("TB202608180000000001"),
                LocalDate.of(2026, 8, 18),
                List.of(
                        Posting.of(BOB, Direction.DEBIT, Money.of(minor, currency)),
                        Posting.of(account, Direction.CREDIT, Money.of(minor, currency)))));
    }

    private Transfer.Command command(AccountRef debit, AccountRef credit, long minor, CurrencyCode currency) {
        return new Transfer.Command(debit, credit, Money.of(minor, currency), null, null);
    }

    @Test
    @DisplayName("a transfer posts one balanced entry and moves both balances")
    void aTransferMovesBothBalances() {
        fund(ALICE, 100_00, PLN);

        TransferView view = transfer.execute(command(ALICE, BOB, 30_00, PLN));

        assertThat(view.status()).isEqualTo(TransferStatus.POSTED);
        assertThat(view.entry().postings()).hasSize(2);
        assertThat(view.debitAccount()).isEqualTo(ALICE);
        assertThat(view.creditAccount()).isEqualTo(BOB);
        assertThat(view.amount()).isEqualTo(Money.of(30_00, PLN));
        assertThat(ledger.entries.balanceOf(ALICE).booked()).isEqualTo(Money.of(70_00, PLN));
    }

    @Test
    @DisplayName("both accounts are locked before anything is read, and in one transaction")
    void bothAccountsAreLockedFirst() {
        fund(ALICE, 100_00, PLN);
        ledger.lockRequests.clear();

        transfer.execute(command(ALICE, BOB, 1_00, PLN));

        // The set is what matters, not the order the caller wrote it in: the adapter sorts, and that
        // sorting is what keeps two opposite transfers over the same pair from deadlocking.
        assertThat(ledger.lockRequests).containsExactly(List.of(ALICE, BOB));
    }

    @Test
    @DisplayName("a debit that would breach a forbidden overdraft is refused and nothing is written")
    void aForbiddenOverdraftIsRefusedAndNothingIsWritten() {
        fund(ALICE, 10_00, PLN);
        int entriesBefore = ledger.entriesByRef.size();

        assertThatThrownBy(() -> transfer.execute(command(ALICE, BOB, 10_01, PLN)))
                .isInstanceOf(OverdraftNotPermittedException.class);

        // F-22, stated as a test. Without the afterEffect call above this passes the exception check
        // and fails here: append writes the entry, and an account whose policy forbids an overdraft
        // ends the day at -0.01 with nothing having reported it.
        assertThat(ledger.entriesByRef).hasSize(entriesBefore);
        assertThat(ledger.entries.balanceOf(ALICE).booked()).isEqualTo(Money.of(10_00, PLN));
    }

    @Test
    @DisplayName("a debit down to exactly zero is permitted")
    void spendingTheLastPennyIsAllowed() {
        fund(ALICE, 10_00, PLN);

        transfer.execute(command(ALICE, BOB, 10_00, PLN));

        assertThat(ledger.entries.balanceOf(ALICE).booked()).isEqualTo(Money.zero(PLN));
    }

    @Test
    @DisplayName("an arranged overdraft is honoured up to its limit and not past it")
    void anArrangedOverdraftIsHonouredToItsLimit() {
        // A ledger of its own: ALICE is opened here with an arranged facility rather than the
        // forbidden policy the rest of this class uses.
        InMemoryLedger fresh = new InMemoryLedger();
        OpenAccount opener = new OpenAccount(fresh.accounts, fresh.readModel, fresh.unitOfWork, fresh.auditTrail(FIXED), FIXED);
        opener.open(new OpenAccount.Command(
                ALICE,
                CustomerRef.of("CU0000000001"),
                AccountType.LIABILITY,
                PLN,
                null,
                OverdraftPolicy.upTo(Money.of(50_00, PLN))));
        opener.open(new OpenAccount.Command(
                BOB, CustomerRef.of("CU0000000001"), AccountType.LIABILITY, PLN, null,
                OverdraftPolicy.forbidden()));
        Transfer overdrawable = new Transfer(
                fresh.accounts,
                fresh.entries,
                fresh.readModel,
                new InMemoryLedger.SequentialReferences(),
                fresh.unitOfWork,
                fresh.auditTrail(FIXED),
                fresh.transferEvents(),
                FIXED);

        overdrawable.execute(new Transfer.Command(ALICE, BOB, Money.of(50_00, PLN), null, null));

        assertThat(fresh.entries.balanceOf(ALICE).booked()).isEqualTo(Money.of(-50_00, PLN));
        assertThatThrownBy(() ->
                        overdrawable.execute(new Transfer.Command(ALICE, BOB, Money.of(1, PLN), null, null)))
                .isInstanceOf(OverdraftNotPermittedException.class);
    }

    @Test
    @DisplayName("a credit is never blocked, even into an account already past its limit")
    void aCreditIsNeverBlocked() {
        fund(ALICE, 100_00, PLN);
        // BOB is now deeply negative from funding ALICE, and BOB forbids overdrafts. Paying money
        // back in must still work: refusing a repayment because the balance is too negative would
        // trap the account in the state it needs to leave.
        assertThat(ledger.entries.balanceOf(BOB).booked().isNegative()).isTrue();

        transfer.execute(command(ALICE, BOB, 10_00, PLN));

        assertThat(ledger.entries.balanceOf(BOB).booked()).isEqualTo(Money.of(-90_00, PLN));
    }

    @Test
    @DisplayName("a blocked account can neither be debited nor credited")
    void aBlockedAccountIsRefused() {
        fund(ALICE, 100_00, PLN);
        ledger.accounts.save(ledger.accountsByRef.get(BOB).withStatus(AccountStatus.BLOCKED));

        assertThatThrownBy(() -> transfer.execute(command(ALICE, BOB, 1_00, PLN)))
                .isInstanceOf(NotActionableException.class)
                .hasMessageContaining("BLOCKED");
        assertThatThrownBy(() -> transfer.execute(command(BOB, ALICE, 1_00, PLN)))
                .isInstanceOf(NotActionableException.class);
    }

    @Test
    @DisplayName("an amount in the wrong currency is refused, never converted")
    void aCurrencyMismatchIsRefused() {
        fund(ALICE, 100_00, PLN);

        assertThatThrownBy(() -> transfer.execute(command(ALICE, BOB, 1_00, EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> transfer.execute(command(ALICE, EURO, 1_00, PLN)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("a zero or negative amount is refused")
    void anAmountThatIsNotPositiveIsRefused() {
        assertThatThrownBy(() -> transfer.execute(command(ALICE, BOB, 0, PLN)))
                .isInstanceOf(NotActionableException.class)
                .hasMessageContaining("strictly positive");
        assertThatThrownBy(() -> transfer.execute(command(ALICE, BOB, -1, PLN)))
                .isInstanceOf(NotActionableException.class);
    }

    @Test
    @DisplayName("a transfer to the same account is refused")
    void aSelfTransferIsRefused() {
        assertThatThrownBy(() -> transfer.execute(command(ALICE, ALICE, 1_00, PLN)))
                .isInstanceOf(NotActionableException.class)
                .hasMessageContaining("two different accounts");
    }

    @Test
    @DisplayName("an unknown account is a different failure from a refused one")
    void anUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> transfer.execute(command(UNKNOWN, BOB, 1_00, PLN)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("remittance information is attached to the entry, and absent when not supplied")
    void remittanceInformationIsCarried() {
        fund(ALICE, 100_00, PLN);

        TransferView withReference = transfer.execute(
                new Transfer.Command(ALICE, BOB, Money.of(1_00, PLN), null, "INVOICE 2026-08-19"));
        TransferView without = transfer.execute(command(ALICE, BOB, 1_00, PLN));

        assertThat(withReference.remittanceReference()).contains("INVOICE 2026-08-19");
        assertThat(without.remittanceReference()).isEmpty();
    }

    @Test
    @DisplayName("an omitted value date defaults to the current business date")
    void theValueDateDefaults() {
        fund(ALICE, 100_00, PLN);

        TransferView view = transfer.execute(command(ALICE, BOB, 1_00, PLN));

        assertThat(view.entry().valueDate()).isEqualTo(LocalDate.of(2026, 8, 19));
    }
}
