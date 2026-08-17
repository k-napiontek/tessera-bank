package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");

    @Nested
    @DisplayName("scale comes from ISO 4217, never from an assumption")
    class Scale {

        @Test
        void plnAndEurHaveTwoDecimals() {
            assertThat(PLN.scale()).isEqualTo(2);
            assertThat(EUR.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("JPY has none - a hard-coded 2 must fail here, not pass quietly")
        void jpyHasNoDecimals() {
            assertThat(JPY.scale()).isZero();
        }

        @Test
        @DisplayName("BHD has three")
        void bhdHasThreeDecimals() {
            assertThat(BHD.scale()).isEqualTo(3);
        }

        @Test
        void anUnknownCurrencyIsRejectedRatherThanDefaulted() {
            assertThatThrownBy(() -> CurrencyCode.of("XYZ"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XYZ");
        }

        @Test
        void aMalformedCodeIsRejected() {
            assertThatThrownBy(() -> CurrencyCode.of("pln")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CurrencyCode.of("PLNX")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CurrencyCode.of(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void addsWithinTheSameCurrency() {
            assertThat(Money.of(120_00, PLN).plus(Money.of(30_50, PLN))).isEqualTo(Money.of(150_50, PLN));
        }

        @Test
        void subtractsWithinTheSameCurrency() {
            assertThat(Money.of(120_00, PLN).minus(Money.of(30_50, PLN))).isEqualTo(Money.of(89_50, PLN));
        }

        @Test
        @DisplayName("a balance may legitimately go negative; an amount may not")
        void negates() {
            assertThat(Money.of(10_00, PLN).negate()).isEqualTo(Money.of(-10_00, PLN));
            assertThat(Money.of(-10_00, PLN).abs()).isEqualTo(Money.of(10_00, PLN));
        }

        @Test
        void refusesToMixCurrencies() {
            assertThatThrownBy(() -> Money.of(100, PLN).plus(Money.of(100, EUR)))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("PLN")
                    .hasMessageContaining("EUR");
        }

        @Test
        @DisplayName("there is no conversion anywhere, so comparing across currencies is a bug")
        void refusesToCompareAcrossCurrencies() {
            assertThatThrownBy(() -> Money.of(100, PLN).compareTo(Money.of(100, EUR)))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("overflow throws rather than wrapping - a wrapping ledger is worse than a broken one")
        void overflowThrows() {
            Money max = Money.of(Long.MAX_VALUE, PLN);
            assertThatThrownBy(() -> max.plus(Money.of(1, PLN))).isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, PLN).negate())
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("presentation")
    class Presentation {

        @Test
        @DisplayName("the decimal point is applied at the boundary, from the currency's scale")
        void formatsUsingTheCurrencyScale() {
            assertThat(Money.of(123_456_789L, PLN).toPlainString()).isEqualTo("1234567.89");
            assertThat(Money.of(1000, JPY).toPlainString()).isEqualTo("1000");
            assertThat(Money.of(100, BHD).toPlainString()).isEqualTo("0.100");
            assertThat(Money.of(-5, BHD).toPlainString()).isEqualTo("-0.005");
            assertThat(Money.zero(PLN).toPlainString()).isEqualTo("0.00");
        }
    }
}
