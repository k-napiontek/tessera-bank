package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A hold remembers when it stopped being a hold.
 *
 * <p>Follow-up F-21: {@code capture}, {@code release} and {@code expire} all demanded an
 * {@code Instant}, validated it, and then threw it away - every transition rebuilt the aggregate from
 * {@code placedAt}. A released hold could not say when it was released, and the persistence adapter
 * had to pass a value it knew was ignored in order to rebuild one. WP-09 closes it because the audit
 * chain records when a thing happened, and "when the hold was placed" is not an answer to that.
 */
class HoldTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final Instant PLACED_AT = Instant.parse("2026-08-19T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-19T11:30:00Z");

    private static Hold placed() {
        return Hold.place(
                HoldRef.of("HL202608190000000001"),
                AccountRef.of("TB00000000000001"),
                Money.of(25_00, PLN),
                PLACED_AT,
                null);
    }

    @Test
    @DisplayName("a hold that is still placed has not transitioned")
    void aPlacedHoldHasNoTransitionInstant() {
        assertThat(placed().transitionedAt()).isEmpty();
    }

    @Test
    @DisplayName("a released hold remembers when it was released")
    void releaseKeepsItsInstant() {
        Hold released = placed().release(LATER);

        assertThat(released.transitionedAt()).contains(LATER);
        assertThat(released.placedAt()).isEqualTo(PLACED_AT);
    }

    @Test
    @DisplayName("a captured hold remembers when it was captured, and by what")
    void captureKeepsItsInstant() {
        EntryRef entry = EntryRef.of("TB202608190000000001");

        Hold captured = placed().capture(entry, LATER);

        assertThat(captured.transitionedAt()).contains(LATER);
        assertThat(captured.capturedBy()).contains(entry);
    }

    @Test
    @DisplayName("an expired hold remembers when it lapsed")
    void expireKeepsItsInstant() {
        assertThat(placed().expire(LATER).transitionedAt()).contains(LATER);
    }

    @Test
    @DisplayName("the transition instant does not go backwards past the placement")
    void aTransitionCannotPrecedeThePlacement() {
        // Now that the value is kept it is worth something, and a stored value that is worth
        // something has to be right. A hold released before it was placed is not a clock problem to
        // paper over: it is a caller passing the wrong instant, and the audit chain would record it.
        assertThatThrownBy(() -> placed().release(PLACED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before it was placed");
    }

    @Test
    @DisplayName("every transition out of PLACED is still final")
    void transitionsAreStillFinal() {
        Hold released = placed().release(LATER);

        assertThatThrownBy(() -> released.capture(EntryRef.of("TB202608190000000001"), LATER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already RELEASED");
    }
}
