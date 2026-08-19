package bank.tessera.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Opening an account, driven entirely by fakes - no database, no framework. */
class OpenAccountTest {

    private static final AccountRef ALICE = AccountRef.of("TB00000000000001");
    private static final AccountRef BOB = AccountRef.of("TB00000000000002");
    private static final CustomerRef CUSTOMER = CustomerRef.of("CU0000000001");
    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-19T09:00:00Z"), ZoneOffset.UTC);

    private InMemoryLedger ledger;
    private OpenAccount openAccount;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        openAccount = new OpenAccount(ledger.accounts, ledger.readModel, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
    }

    private OpenAccount.Command command(AccountRef reference, LocalDate openedDate) {
        return new OpenAccount.Command(
                reference, CUSTOMER, AccountType.LIABILITY, PLN, openedDate, OverdraftPolicy.forbidden());
    }

    @Test
    @DisplayName("an account opens at the supplied reference, with both balances at zero")
    void opensAtTheSuppliedReference() {
        AccountView view = openAccount.open(command(ALICE, LocalDate.of(2026, 3, 1)));

        assertThat(view.account().reference()).isEqualTo(ALICE);
        assertThat(view.account().status()).isEqualTo(AccountStatus.OPEN);
        assertThat(view.account().overdraft().isForbidden()).isTrue();
        assertThat(view.balance().booked()).isEqualTo(Money.zero(PLN));
        assertThat(view.balance().available()).isEqualTo(Money.zero(PLN));
        assertThat(view.dates().opened()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(view.dates().lastMovement()).isEmpty();
    }

    @Test
    @DisplayName("an omitted opening date defaults to the current business date")
    void defaultsTheOpeningDate() {
        AccountView view = openAccount.open(command(ALICE, null));

        assertThat(view.dates().opened()).isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    @DisplayName("opening the same reference twice is refused")
    void refusesADuplicateReference() {
        openAccount.open(command(ALICE, null));

        assertThatThrownBy(() -> openAccount.open(command(ALICE, null)))
                .isInstanceOf(AccountAlreadyOpenException.class)
                .hasMessageContaining(ALICE.value());
    }

    @Test
    @DisplayName("a refused second opening leaves the first account exactly as it was")
    void leavesNoSecondAccountBehind() {
        openAccount.open(command(ALICE, LocalDate.of(2026, 3, 1)));
        try {
            openAccount.open(command(ALICE, LocalDate.of(2026, 4, 1)));
        } catch (AccountAlreadyOpenException expected) {
            // the point of the test is what the store holds afterwards
        }

        // Refusing the request is not enough on its own: an implementation that saved first and
        // checked afterwards would still overwrite the opening date, and the second attempt would
        // silently rewrite the history of an account that has been trading for months.
        assertThat(ledger.accountsByRef).hasSize(1);
        assertThat(ledger.datesByAccount.get(ALICE).opened()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("opening an account takes exactly one transaction")
    void runsInOneTransaction() {
        openAccount.open(command(ALICE, null));

        // The account row and its opening date are two writes. Outside one transaction, a failure
        // between them leaves an account with no opening date, which GetAccount treats as corruption.
        assertThat(ledger.transactions).isEqualTo(1);
    }

    @Test
    @DisplayName("a forbidden overdraft is not stored as a limit of zero")
    void forbiddenIsNotZero() {
        openAccount.open(command(ALICE, null));
        openAccount.open(new OpenAccount.Command(
                BOB, CUSTOMER, AccountType.LIABILITY, PLN, null, OverdraftPolicy.upTo(Money.zero(PLN))));

        assertThat(ledger.accountsByRef.get(ALICE).overdraft().isForbidden()).isTrue();
        assertThat(ledger.accountsByRef.get(BOB).overdraft().isForbidden()).isFalse();
    }
}
