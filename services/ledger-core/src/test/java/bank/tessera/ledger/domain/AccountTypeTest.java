package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The sign convention, which is what separates a ledger from a table of balances.
 *
 * <p>The rule is not arbitrary and is not a matter of taste: asset and expense accounts increase on
 * the debit side, everything else increases on the credit side. Getting it backwards produces a
 * system that appears to work until the balance sheet is drawn up.
 */
class AccountTypeTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");

    @ParameterizedTest(name = "{0} increases on the {1} side")
    @CsvSource({
        "ASSET,     DEBIT",
        "EXPENSE,   DEBIT",
        "LIABILITY, CREDIT",
        "EQUITY,    CREDIT",
        "REVENUE,   CREDIT",
    })
    void normalBalanceIsFixedByAccountType(AccountType type, Direction expected) {
        assertThat(type.normalBalance()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a customer's current account is a LIABILITY of the bank, so crediting it increases what we owe")
    void creditingACustomerAccountIncreasesIt() {
        Money effect = AccountType.LIABILITY.signedEffect(Direction.CREDIT, Money.of(100_00, PLN));
        assertThat(effect).isEqualTo(Money.of(100_00, PLN));
    }

    @Test
    @DisplayName("debiting that same customer account reduces what we owe them")
    void debitingACustomerAccountDecreasesIt() {
        Money effect = AccountType.LIABILITY.signedEffect(Direction.DEBIT, Money.of(100_00, PLN));
        assertThat(effect).isEqualTo(Money.of(-100_00, PLN));
    }

    @Test
    @DisplayName("cash is an ASSET, so debiting it increases what the bank holds")
    void debitingCashIncreasesIt() {
        Money effect = AccountType.ASSET.signedEffect(Direction.DEBIT, Money.of(100_00, PLN));
        assertThat(effect).isEqualTo(Money.of(100_00, PLN));
    }

    @Test
    @DisplayName("the two sides of one transfer cancel out, which is why the books balance")
    void aDebitAndCreditOfTheSameAmountCancel() {
        Money amount = Money.of(250_00, PLN);
        Money onCash = AccountType.ASSET.signedEffect(Direction.DEBIT, amount);
        Money onCustomer = AccountType.LIABILITY.signedEffect(Direction.DEBIT, amount);
        assertThat(onCash.plus(onCustomer.negate())).isEqualTo(amount.plus(amount));
    }

    @Test
    void directionsAreOpposites() {
        assertThat(Direction.DEBIT.opposite()).isEqualTo(Direction.CREDIT);
        assertThat(Direction.CREDIT.opposite()).isEqualTo(Direction.DEBIT);
    }
}
