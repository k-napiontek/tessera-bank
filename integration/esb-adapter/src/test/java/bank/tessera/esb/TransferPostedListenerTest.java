package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;

/**
 * What the listener does when the work fails, which is the only thing it decides.
 *
 * <p>Every one of these is about a message that went wrong, because the success path is one line
 * and the failure paths are where a bridge between eras actually lives. Getting the
 * permanent-versus-transient call backwards is expensive in both directions: a permanent failure
 * retried forever blocks every transfer behind it, and a transient failure dead-lettered
 * immediately discards a good payment because a network blinked.
 */
class TransferPostedListenerTest {

    private static final String TOPIC = "tessera.ledger.transfer-posted.v1";
    private static final String TRANSFER_REF = "TB000000000000000042";
    private static final String PAYLOAD =
            "{\"transferRef\":\"TB000000000000000042\",\"correlationId\":"
                    + "\"11111111-2222-3333-4444-555555555555\"}";

    private TransferHandler handler;
    private DeadLetterRecorder deadLetters;
    private Acknowledgment ack;
    private TransferPostedListener listener;

    @BeforeEach
    void setUp() {
        handler = Mockito.mock(TransferHandler.class);
        deadLetters = Mockito.mock(DeadLetterRecorder.class);
        ack = Mockito.mock(Acknowledgment.class);
        listener = new TransferPostedListener(handler, deadLetters);
    }

    @Test
    void acknowledgesAMessageItCarriedAcross() {
        listener.onMessage(record(TRANSFER_REF, PAYLOAD), ack);

        verify(handler).handle(PAYLOAD);
        verify(ack).acknowledge();
        verifyNoInteractions(deadLetters);
    }

    @Test
    void deadLettersAPermanentFailureAndKeepsThePartitionMoving() {
        Mockito.doThrow(TransferHandlingException.permanent(
                        FailureStage.TRANSFORM, "the result does not satisfy the canonical schema"))
                .when(handler).handle(any());

        listener.onMessage(record(TRANSFER_REF, PAYLOAD), ack);

        verify(deadLetters).record(eq(TRANSFER_REF), eq(PAYLOAD), any(), anyInt());
        verify(ack).acknowledge();
    }

    /**
     * The one that matters most. Acknowledging here would discard a payment because something was
     * briefly unavailable, and nothing downstream would ever know a transfer was missing.
     */
    @Test
    void neverAcknowledgesATransientFailure() {
        Mockito.doThrow(TransferHandlingException.transient_(
                        FailureStage.SOAP, "connection refused", new java.io.IOException()))
                .when(handler).handle(any());

        assertThrows(TransferHandlingException.class,
                () -> listener.onMessage(record(TRANSFER_REF, PAYLOAD), ack));

        verify(ack, never()).acknowledge();
        verifyNoInteractions(deadLetters);
    }

    /**
     * An exception nobody classified is a bug in this component, and a bug in this component is not
     * a reason to throw away somebody's money.
     */
    @Test
    void treatsAnUnclassifiedFailureAsTransient() {
        Mockito.doThrow(new IllegalStateException("nobody thought about this"))
                .when(handler).handle(any());

        assertThrows(IllegalStateException.class,
                () -> listener.onMessage(record(TRANSFER_REF, PAYLOAD), ack));

        verify(ack, never()).acknowledge();
        verifyNoInteractions(deadLetters);
    }

    /**
     * The key first, and the payload only as a fallback. A message that cannot be parsed still has a
     * key, and that is exactly the message most in need of being identifiable afterwards.
     */
    @Test
    void identifiesAMessageByItsKeyEvenWhenTheBodyIsUnreadable() {
        Mockito.doThrow(TransferHandlingException.permanent(FailureStage.DEQUEUE, "not JSON"))
                .when(handler).handle(any());

        listener.onMessage(record(TRANSFER_REF, "this is not JSON at all"), ack);

        verify(deadLetters).record(eq(TRANSFER_REF), eq("this is not JSON at all"), any(), anyInt());
    }

    @Test
    void fallsBackToThePayloadWhenTheKeyIsUnusable() {
        Mockito.doThrow(TransferHandlingException.permanent(FailureStage.SOAP, "refused"))
                .when(handler).handle(any());

        listener.onMessage(record("not-a-transfer-ref", PAYLOAD), ack);

        verify(deadLetters).record(eq(TRANSFER_REF), eq(PAYLOAD), any(), anyInt());
    }

    /**
     * No key and no readable body. The dead-letter contract permits an absent transferRef precisely
     * so that the worst message is still recorded rather than being the only one that cannot be.
     */
    @Test
    void recordsAMessageThatCannotBeIdentifiedAtAll() {
        Mockito.doThrow(TransferHandlingException.permanent(FailureStage.DEQUEUE, "not JSON"))
                .when(handler).handle(any());

        listener.onMessage(record(null, "{{{"), ack);

        verify(deadLetters).record(isNull(), eq("{{{"), any(), anyInt());
        verify(ack).acknowledge();
    }

    @Test
    void aRedeliveryIsHandledAgainRatherThanSuppressed() {
        // Idempotency belongs to the far end, which claims the transfer with a unique constraint and
        // answers alreadyApplied. This listener deliberately keeps no record of what it has seen: a
        // second one would be a second source of truth about the bank's money.
        ConsumerRecord<String, String> first = record(TRANSFER_REF, PAYLOAD);
        ConsumerRecord<String, String> redelivery = record(TRANSFER_REF, PAYLOAD);

        listener.onMessage(first, ack);
        listener.onMessage(redelivery, ack);

        verify(handler, times(2)).handle(PAYLOAD);
        verify(ack, times(2)).acknowledge();
    }

    @Test
    void theFailureStagesAreTheOnesTheContractDeclares() {
        // The contract's enum, mirrored here. A stage this component invents is a value no operator
        // dashboard and no consumer of the dead-letter channel would recognise.
        assertEquals(5, FailureStage.values().length);
        for (String declared : new String[] {"DEQUEUE", "TRANSFORM", "SOAP", "ENCODE", "WRITE"}) {
            assertTrue(java.util.Arrays.stream(FailureStage.values())
                            .anyMatch(stage -> stage.name().equals(declared)),
                    declared + " is declared in the contract and missing from FailureStage");
        }
        assertNull(TransferHandlingException.permanent(FailureStage.SOAP, "x").faultCode());
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, key, value);
    }
}
