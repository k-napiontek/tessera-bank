package bank.tessera.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** The relationship between booked, available and holds, over generated inputs. */
class BalancePropertiesTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef ACCOUNT = AccountRef.of("TB00000000000001");
    private static final Instant NOW = Instant.parse("2026-08-17T09:15:00Z");

    @Provide
    Arbitrary<List<Long>> holdAmounts() {
        return Arbitraries.longs().between(1, 1_000_000L).list().ofMinSize(0).ofMaxSize(10);
    }

    @Provide
    Arbitrary<Long> bookedAmounts() {
        return Arbitraries.longs().between(-1_000_000L, 10_000_000L);
    }

    @Property
    @Label("available always equals booked less the sum of holds still PLACED")
    void availableIsBookedLessActiveHolds(
            @ForAll("bookedAmounts") long booked, @ForAll("holdAmounts") List<Long> amounts) {
        List<Hold> holds = holds(amounts);
        long reserved = amounts.stream().mapToLong(Long::longValue).sum();

        Balance balance = Balance.of(ACCOUNT, Money.of(booked, PLN), holds);

        assertThat(balance.available()).isEqualTo(Money.of(booked - reserved, PLN));
    }

    @Property
    @Label("releasing every hold restores available to booked exactly")
    void releasingEveryHoldRestoresAvailable(
            @ForAll("bookedAmounts") long booked, @ForAll("holdAmounts") List<Long> amounts) {
        List<Hold> released = new ArrayList<>();
        for (Hold hold : holds(amounts)) {
            released.add(hold.release(NOW));
        }
        Balance balance = Balance.of(ACCOUNT, Money.of(booked, PLN), released);
        assertThat(balance.available()).isEqualTo(balance.booked());
    }

    @Property
    @Label("capturing a hold never reduces available twice")
    void capturingDoesNotDoubleCount(@ForAll("holdAmounts") List<Long> amounts) {
        if (amounts.isEmpty()) {
            return;
        }
        long booked = 100_000_000L;
        List<Hold> holds = holds(amounts);

        Balance beforeCapture = Balance.of(ACCOUNT, Money.of(booked, PLN), holds);

        // Capture the first hold: the entry posts its amount, and the hold stops reserving it.
        Hold first = holds.get(0);
        List<Hold> after = new ArrayList<>(holds);
        after.set(0, first.capture(EntryRef.of("TB202608170000000042"), NOW));

        Balance afterCapture = Balance.of(ACCOUNT, Money.of(booked, PLN).minus(first.amount()), after);

        assertThat(afterCapture.available())
                .as("available must be unchanged: the reservation became a posting, it did not add to it")
                .isEqualTo(beforeCapture.available());
    }

    private static List<Hold> holds(List<Long> amounts) {
        List<Hold> holds = new ArrayList<>(amounts.size());
        for (int i = 0; i < amounts.size(); i++) {
            holds.add(Hold.place(
                    HoldRef.of(String.format("HL%018d", i)), ACCOUNT, Money.of(amounts.get(i), PLN), NOW, null));
        }
        return holds;
    }
}
