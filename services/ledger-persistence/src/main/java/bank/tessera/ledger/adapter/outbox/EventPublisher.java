package bank.tessera.ledger.adapter.outbox;

/**
 * Whatever actually reaches the broker.
 *
 * <p>The relay is about a table and a state machine; this is about Kafka. Separating them is what
 * lets the relay's guarantee - a row is marked dispatched only after a successful publish, and never
 * before - be proved without a broker, and proved for the case that matters, which is the publish
 * that succeeds and is then lost.
 */
public interface EventPublisher {

    /**
     * Publishes one message and returns when the broker has acknowledged it.
     *
     * <p>Must be synchronous. An implementation that returned before the acknowledgement would let
     * the relay mark a row dispatched that the broker never accepted, which is the one failure the
     * outbox exists to rule out.
     *
     * @throws RuntimeException if the broker did not accept it
     */
    void publish(PendingMessage message);
}
