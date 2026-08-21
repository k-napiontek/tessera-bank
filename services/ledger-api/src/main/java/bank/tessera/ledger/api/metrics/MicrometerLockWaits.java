package bank.tessera.ledger.api.metrics;

import bank.tessera.ledger.adapter.jdbc.LockWaits;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The ledger's two lock waits, as two meters.
 *
 * <p>They are separate series rather than one series with a tag, because the two are not comparable
 * quantities and a dashboard that summed them would be summing a service-wide ceiling with
 * per-account contention. F-27 asks which of the two is the limit; one number cannot answer it.
 *
 * <p>Registered here rather than in {@code ledger-persistence} for two reasons that agree. That
 * module has no metrics library on its classpath and is better for it - see {@link LockWaits} - and
 * the SLO catalogue files a metric under the component that emits it, which is this service and not
 * a library nobody deploys.
 */
public final class MicrometerLockWaits implements LockWaits {

    static final String CHAIN_TIMER = "ledger.lock.chain";
    static final String ACCOUNT_TIMER = "ledger.lock.account";

    private final Map<Kind, Timer> timers = new EnumMap<>(Kind.class);

    public MicrometerLockWaits(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        timers.put(Kind.CHAIN, Timer.builder(CHAIN_TIMER)
                .description("Time spent waiting for the audit chain's service-wide advisory lock")
                .register(registry));
        timers.put(Kind.ACCOUNT, Timer.builder(ACCOUNT_TIMER)
                .description("Time spent taking row locks on the accounts a transaction touches")
                .register(registry));
    }

    @Override
    public void record(Kind kind, long nanos) {
        timers.get(kind).record(nanos, TimeUnit.NANOSECONDS);
    }
}
