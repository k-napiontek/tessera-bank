package bank.tessera.ledger.loader;

import java.util.Map;

/**
 * What a load did.
 *
 * @param busiest the account that ended up with the most postings, which is where the deep-cursor
 *     query plan is captured
 * @param chainHead the hash of the last audit row, which is what says which chain a figure came from
 */
public record LoadSummary(
        Header header, Map<Counter, Long> counters, Busiest busiest, long chainLength, String chainHead) {

    /** An account and its posting count. */
    public record Busiest(String accountRef, long postings) {

        /** Before any posting has been written. */
        public static Busiest none() {
            return new Busiest("", 0);
        }
    }

    public long count(Counter counter) {
        return counters.getOrDefault(counter, 0L);
    }
}
