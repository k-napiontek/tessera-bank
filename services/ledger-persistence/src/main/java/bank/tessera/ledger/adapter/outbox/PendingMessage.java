package bank.tessera.ledger.adapter.outbox;

/**
 * One outbox row on its way to the broker.
 *
 * <p>The payload is the JSON text as the column holds it, not a re-serialised object. A relay that
 * rebuilt the payload could publish something different from what was committed alongside the
 * postings, which is the one thing the outbox is there to prevent.
 *
 * @param id the outbox row, so the relay can mark exactly what it published
 * @param key the partition key - {@code transferRef}, so a transfer's events keep their order
 */
public record PendingMessage(long id, String topic, String key, String payload) {}
