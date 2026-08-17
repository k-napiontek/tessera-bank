package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef REF = AccountRef.of("TB00000000000001");
    private static final CustomerRef CUSTOMER = CustomerRef.of("CU0000000001");

    private static Account open() {
        return Account.builder()
                .reference(REF)
                .customer(CUSTOMER)
                .type(AccountType.LIABILITY)
                .currency(PLN)
                .status(AccountStatus.OPEN)
                .overdraft(OverdraftPolicy.forbidden())
                .build();
    }

    @Test
    void carriesItsIdentityAndClassification() {
        Account account = open();
        assertThat(account.reference()).isEqualTo(REF);
        assertThat(account.customer()).isEqualTo(CUSTOMER);
        assertThat(account.type()).isEqualTo(AccountType.LIABILITY);
        assertThat(account.currency()).isEqualTo(PLN);
        assertThat(account.status()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    @DisplayName("only an OPEN account may be posted to")
    void postingIsAllowedOnlyWhenOpen() {
        assertThat(open().canBePosted()).isTrue();
        assertThat(open().withStatus(AccountStatus.BLOCKED).canBePosted()).isFalse();
        assertThat(open().withStatus(AccountStatus.CLOSED).canBePosted()).isFalse();
    }

    @Test
    @DisplayName("changing status returns a new instance and leaves the original untouched")
    void isImmutable() {
        Account original = open();
        Account blocked = original.withStatus(AccountStatus.BLOCKED);
        assertThat(blocked).isNotSameAs(original);
        assertThat(original.status()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    @DisplayName("a malformed reference cannot enter the domain at all")
    void rejectsMalformedReferences() {
        assertThatThrownBy(() -> AccountRef.of("XX00000000000001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountRef.of("TB001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountRef.of("tb00000000000001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerRef.of("CU00000000")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TB00000000000001", "TB0000000000000Z", "TBAAAAAAAAAAAAAA"})
    void acceptsReferencesMatchingTheCanonicalPattern(String reference) {
        assertThat(AccountRef.of(reference).value()).isEqualTo(reference);
    }

    @Test
    void requiresEveryField() {
        assertThatThrownBy(() -> Account.builder().reference(REF).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a forbidden overdraft permits zero but not a penny below")
    void forbiddenOverdraftAllowsExactlyZero() {
        OverdraftPolicy policy = OverdraftPolicy.forbidden();
        assertThat(policy.permits(Money.zero(PLN))).isTrue();
        assertThat(policy.permits(Money.of(1, PLN))).isTrue();
        assertThat(policy.permits(Money.of(-1, PLN))).isFalse();
    }

    @Test
    @DisplayName("an agreed limit permits exactly that much and no more")
    void limitedOverdraftAllowsUpToTheLimit() {
        OverdraftPolicy policy = OverdraftPolicy.upTo(Money.of(500_00, PLN));
        assertThat(policy.permits(Money.of(-500_00, PLN))).isTrue();
        assertThat(policy.permits(Money.of(-500_01, PLN))).isFalse();
    }

    @Test
    void anOverdraftLimitCannotBeNegative() {
        assertThatThrownBy(() -> OverdraftPolicy.upTo(Money.of(-1, PLN)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
