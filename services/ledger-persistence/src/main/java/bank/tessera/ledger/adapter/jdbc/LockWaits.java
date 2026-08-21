package bank.tessera.ledger.adapter.jdbc;

import java.util.function.Supplier;

/**
 * How long a transaction waited to be given a lock, reported to whoever is counting.
 *
 * <p>Every money-moving transaction in this ledger takes two kinds of lock, and they are separated
 * here because averaging them together makes the only interesting question unanswerable.
 *
 * <ul>
 *   <li>{@link Kind#CHAIN} is {@code pg_advisory_xact_lock}, taken by {@link JdbcAuditLog} before it
 *       reads the last hash and held until the transaction commits. It is **service-wide**: one
 *       writer at a time, across every account and every instance. ADR 0005 states that ceiling
 *       rather than discovering it under load, and F-27 has been asking since WP-09 for a measured
 *       figure instead of a hunch.
 *   <li>{@link Kind#ACCOUNT} is the {@code SELECT ... FOR UPDATE} that {@link AccountLocks} takes,
 *       in ascending reference order so that two transfers cannot deadlock. It contends only between
 *       transactions touching the same accounts, so it rises with how concentrated the day's traffic
 *       is, not with how much of it there is.
 * </ul>
 *
 * <p>One composite "lock wait" would move for those two entirely unrelated reasons, and an operator
 * watching it could not tell a busy corporate account from a service-wide ceiling. Which of the two
 * is the limit is the whole of F-27.
 *
 * <p><strong>This module does not know what a meter is, and that is deliberate.</strong> Nothing on
 * this classpath is a metrics library; the ledger's meters are registered in {@code ledger-api},
 * beside every other meter it owns, and reach these adapters as this one-method callback. The
 * arrangement is also what keeps the SLO catalogue honest: it files an objective under the component
 * that emits the metric, and {@code services/ledger-persistence} is a library rather than something
 * anybody deploys or scrapes.
 */
@FunctionalInterface
public interface LockWaits {

    /** Which lock was being waited for. */
    enum Kind {
        /** The audit chain's service-wide advisory lock. */
        CHAIN,
        /** Row locks on the accounts a transaction touches. */
        ACCOUNT
    }

    /**
     * Records one wait.
     *
     * @param kind which lock
     * @param nanos how long acquiring it took
     */
    void record(Kind kind, long nanos);

    /** Reports nothing. Used where a ledger is assembled without metrics, as most tests are. */
    LockWaits UNMEASURED = (kind, nanos) -> {};

    /**
     * Runs {@code acquisition}, records how long it took, and returns what it produced.
     *
     * <p>Provided so that no call site has to remember to stop its own clock, which is the way a
     * timing that is quietly never recorded gets into a codebase.
     */
    default <T> T timing(Kind kind, Supplier<T> acquisition) {
        long started = System.nanoTime();
        try {
            return acquisition.get();
        } finally {
            record(kind, System.nanoTime() - started);
        }
    }
}
