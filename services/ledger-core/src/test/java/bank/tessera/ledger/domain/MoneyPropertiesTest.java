package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Properties of {@link Money}, checked against many generated inputs rather than a handful of chosen
 * ones. Examples prove a case works; properties prove no case does not.
 */
class MoneyPropertiesTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");

    /** Bounded so that the arithmetic under test cannot overflow - overflow has its own test. */
    @Provide
    Arbitrary<Long> minorUnits() {
        return Arbitraries.longs().between(-1_000_000_000_000L, 1_000_000_000_000L);
    }

    @Provide
    Arbitrary<CurrencyCode> currencies() {
        return Arbitraries.of("PLN", "EUR", "USD", "GBP", "CHF", "JPY", "KRW", "BHD", "KWD", "TND")
                .map(CurrencyCode::of);
    }

    @Property
    void additionIsCommutative(@ForAll("minorUnits") long a, @ForAll("minorUnits") long b) {
        assertThat(Money.of(a, PLN).plus(Money.of(b, PLN)))
                .isEqualTo(Money.of(b, PLN).plus(Money.of(a, PLN)));
    }

    @Property
    void additionIsAssociative(
            @ForAll("minorUnits") long a, @ForAll("minorUnits") long b, @ForAll("minorUnits") long c) {
        Money x = Money.of(a, PLN);
        Money y = Money.of(b, PLN);
        Money z = Money.of(c, PLN);
        assertThat(x.plus(y).plus(z)).isEqualTo(x.plus(y.plus(z)));
    }

    @Property
    void subtractionInvertsAddition(@ForAll("minorUnits") long a, @ForAll("minorUnits") long b) {
        Money x = Money.of(a, PLN);
        assertThat(x.plus(Money.of(b, PLN)).minus(Money.of(b, PLN))).isEqualTo(x);
    }

    @Property
    void zeroIsTheAdditiveIdentity(@ForAll("minorUnits") long a, @ForAll("currencies") CurrencyCode c) {
        Money x = Money.of(a, c);
        assertThat(x.plus(Money.zero(c))).isEqualTo(x);
    }

    @Property
    void negatingTwiceReturnsTheOriginal(@ForAll("minorUnits") long a) {
        Money x = Money.of(a, PLN);
        assertThat(x.negate().negate()).isEqualTo(x);
    }

    @Property
    void absIsNeverNegative(@ForAll("minorUnits") long a, @ForAll("currencies") CurrencyCode c) {
        assertThat(Money.of(a, c).abs().isNegative()).isFalse();
    }

    @Property
    void anyTwoDifferentCurrenciesRefuseToCombine(
            @ForAll("currencies") CurrencyCode left, @ForAll("currencies") CurrencyCode right) {
        if (left.equals(right)) {
            return;
        }
        assertThatThrownBy(() -> Money.of(1, left).plus(Money.of(1, right)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Property
    @net.jqwik.api.Label("the formatted string always carries exactly the currency's scale")
    void formattingUsesExactlyTheCurrencyScale(
            @ForAll("minorUnits") long a, @ForAll("currencies") CurrencyCode c) {
        String formatted = Money.of(a, c).toPlainString();
        int decimals = formatted.contains(".") ? formatted.length() - formatted.indexOf('.') - 1 : 0;
        assertThat(decimals).isEqualTo(c.scale());
    }

    @Property
    @net.jqwik.api.Label("the formatted value equals the minor units scaled by the currency")
    void formattingIsExact(@ForAll("minorUnits") long a, @ForAll("currencies") CurrencyCode c) {
        assertThat(new BigDecimal(Money.of(a, c).toPlainString()))
                .isEqualByComparingTo(BigDecimal.valueOf(a, c.scale()));
    }

    @Property
    void additionNearTheLimitThrowsRatherThanWrapping(@ForAll @IntRange(min = 1, max = 1000) int delta) {
        Money max = Money.of(Long.MAX_VALUE - delta + 1, PLN);
        assertThatThrownBy(() -> max.plus(Money.of(delta, PLN))).isInstanceOf(ArithmeticException.class);
    }
}
