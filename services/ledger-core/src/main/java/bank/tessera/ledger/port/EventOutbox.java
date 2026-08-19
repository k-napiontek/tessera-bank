package bank.tessera.ledger.port;

/**
 * Where a domain event is written so that it cannot be published without its postings committing.
 *
 * <p><strong>This is not a message broker.</strong> It is a table in the same database as the
 * postings, written in the same transaction, and that is the entire point: publishing to a broker
 * from inside a transaction is the dual-write problem, and every arrangement that tries it can lose
 * an event or invent one. The broker is reached afterwards, by a relay, from rows that are known to
 * have committed.
 *
 * <p>The cost is that delivery becomes at-least-once - the relay can crash between publishing and
 * marking the row sent, and it republishes. The AsyncAPI document states that plainly and requires
 * every consumer to de-duplicate on {@code transferRef}. That trade is deliberate: a duplicate a
 * consumer can detect is recoverable, and a lost posting notification is not.
 */
public interface EventOutbox {

    /** Enqueues the event, inside whatever transaction the caller is already in. */
    void publish(TransferPostedEvent event);
}
