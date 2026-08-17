package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BalanceTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef ACCOUNT = AccountRef.of("TB00000000000001");
    private static final Instant NOW = Instant.parse("2026-08-17T09:15:00Z");

    private static Hold hold(String ref, long minor) {
        return Hold.place(HoldRef.of(ref), ACCOUNT, Money.of(minor, PLN), NOW, null);
    }

    @Nested
    @DisplayName("available balance")
    class Available {

        @Test
        @DisplayName("with no holds, available equals booked")
        void equalsBookedWhenThereAreNoHolds() {
            Balance balance = Balance.of(ACCOUNT, Money.of(1_000_00, PLN), List.of());
            assertThat(balance.booked()).isEqualTo(Money.of(1_000_00, PLN));
            assertThat(balance.available()).isEqualTo(Money.of(1_000_00, PLN));
        }

        @Test
        @DisplayName("a placed hold reduces available and leaves booked alone - no money has moved")
        void aPlacedHoldReducesAvailableOnly() {
            Balance balance = Balance.of(ACCOUNT, Money.of(1_000_00, PLN), List.of(hold("HL202608170000000001", 250_00)));
            assertThat(balance.booked()).isEqualTo(Money.of(1_000_00, PLN));
            assertThat(balance.available()).isEqualTo(Money.of(750_00, PLN));
        }

        @Test
        void severalHoldsAccumulate() {
            Balance balance = Balance.of(
                    ACCOUNT,
                    Money.of(1_000_00, PLN),
                    List.of(hold("HL202608170000000001", 250_00), hold("HL202608170000000002", 100_00)));
            assertThat(balance.available()).isEqualTo(Money.of(650_00, PLN));
        }

        @Test
        @DisplayName("only holds still PLACED count - released and captured ones do not")
        void onlyActiveHoldsCount() {
            Hold released = hold("HL202608170000000001", 250_00).release(NOW);
            Hold captured = hold("HL202608170000000002", 100_00)
                    .capture(EntryRef.of("TB202608170000000042"), NOW);
            Balance balance = Balance.of(ACCOUNT, Money.of(1_000_00, PLN), List.of(released, captured));
            assertThat(balance.available()).isEqualTo(Money.of(1_000_00, PLN));
        }

        @Test
        @DisplayName("holds may exceed the balance; available goes negative rather than lying")
        void availableMayGoNegative() {
            Balance balance = Balance.of(ACCOUNT, Money.of(100_00, PLN), List.of(hold("HL202608170000000001", 250_00)));
            assertThat(balance.available()).isEqualTo(Money.of(-150_00, PLN));
        }

        @Test
        void aHoldInAnotherCurrencyIsRejected() {
            Hold euro = Hold.place(
                    HoldRef.of("HL202608170000000009"), ACCOUNT, Money.of(1, CurrencyCode.of("EUR")), NOW, null);
            assertThatThrownBy(() -> Balance.of(ACCOUNT, Money.of(100_00, PLN), List.of(euro)))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }

    @Nested
    @DisplayName("the overdraft rule")
    class Overdraft {

        @Test
        @DisplayName("an account that forbids overdraft cannot be taken below zero")
        void forbiddenOverdraftBlocksANegativeBooked() {
            Balance balance = Balance.of(ACCOUNT, Money.of(100_00, PLN), List.of());
            assertThatThrownBy(() -> balance.afterEffect(Money.of(-100_01, PLN), OverdraftPolicy.forbidden()))
                    .isInstanceOf(OverdraftNotPermittedException.class)
                    .hasMessageContaining("-0.01");
        }

        @Test
        void reachingExactlyZeroIsAllowed() {
            Balance balance = Balance.of(ACCOUNT, Money.of(100_00, PLN), List.of());
            assertThat(balance.afterEffect(Money.of(-100_00, PLN), OverdraftPolicy.forbidden()).booked())
                    .isEqualTo(Money.zero(PLN));
        }

        @Test
        @DisplayName("an arranged limit is honoured to the penny, and not one beyond")
        void anArrangedLimitIsHonoured() {
            Balance balance = Balance.of(ACCOUNT, Money.zero(PLN), List.of());
            OverdraftPolicy limit = OverdraftPolicy.upTo(Money.of(500_00, PLN));
            assertThat(balance.afterEffect(Money.of(-500_00, PLN), limit).booked())
                    .isEqualTo(Money.of(-500_00, PLN));
            assertThatThrownBy(() -> balance.afterEffect(Money.of(-500_01, PLN), limit))
                    .isInstanceOf(OverdraftNotPermittedException.class);
        }

        @Test
        void creditsAreNeverBlocked() {
            Balance balance = Balance.of(ACCOUNT, Money.of(-400_00, PLN), List.of());
            assertThat(balance.afterEffect(Money.of(50_00, PLN), OverdraftPolicy.forbidden()).booked())
                    .isEqualTo(Money.of(-350_00, PLN));
        }
    }

    @Nested
    @DisplayName("hold lifecycle")
    class Lifecycle {

        @Test
        void aPlacedHoldCanBeCaptured() {
            Hold captured = hold("HL202608170000000001", 250_00)
                    .capture(EntryRef.of("TB202608170000000042"), NOW);
            assertThat(captured.status()).isEqualTo(HoldStatus.CAPTURED);
            assertThat(captured.capturedBy()).contains(EntryRef.of("TB202608170000000042"));
            assertThat(captured.isActive()).isFalse();
        }

        @Test
        @DisplayName("every transition out of PLACED is terminal - a hold is never reopened")
        void terminalStatesCannotTransitionAgain() {
            Hold released = hold("HL202608170000000001", 250_00).release(NOW);
            assertThatThrownBy(() -> released.capture(EntryRef.of("TB202608170000000042"), NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RELEASED");
            assertThatThrownBy(() -> released.release(NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void capturingIsRecordedAgainstTheEntryThatConsumedIt() {
            Hold placed = hold("HL202608170000000001", 250_00);
            assertThat(placed.capturedBy()).isEmpty();
            assertThat(placed.isActive()).isTrue();
        }

        @Test
        void aHoldAmountMustBePositive() {
            assertThatThrownBy(
                            () -> Hold.place(HoldRef.of("HL202608170000000001"), ACCOUNT, Money.zero(PLN), NOW, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("capturing leaves the original hold object untouched")
        void isImmutable() {
            Hold placed = hold("HL202608170000000001", 250_00);
            placed.capture(EntryRef.of("TB202608170000000042"), NOW);
            assertThat(placed.status()).isEqualTo(HoldStatus.PLACED);
        }
    }
}
