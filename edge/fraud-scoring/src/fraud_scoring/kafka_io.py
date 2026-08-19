"""The Kafka ends of the service, kept as thin as they can be.

Everything interesting - what is scored, and in what order things happen - lives in ``service.py``
and is tested without a broker. What is here is the configuration of librdkafka and the translation
between its shapes and this service's, proved against a real broker in one integration test rather
than reasoned about.

Two settings carry the guarantees:

``enable.auto.commit=false`` on the consumer. Automatic commits move the offset on a timer, with no
relation to whether the decision was published - which would silently reintroduce exactly the loss
that publishing before committing exists to prevent.

``acks=all`` with idempotence on the producer. "Published" has to mean the replicas have it, not
that one leader acknowledged it and may still fail over; otherwise committing the offset afterwards
is committing against a promise that can be withdrawn.
"""

from __future__ import annotations

import logging
from typing import Any

from confluent_kafka import Consumer, KafkaError, KafkaException, Message, Producer

from fraud_scoring.config import Settings
from fraud_scoring.service import Envelope

LOG = logging.getLogger(__name__)

# librdkafka reports "you have read everything there is" as an error code. It is information, not a
# failure, and treating it as one would make an idle topic look like a broken consumer.
_CAUGHT_UP = KafkaError._PARTITION_EOF


class KafkaEventSource:
    """Reads transfer events, and commits only when told to."""

    def __init__(self, settings: Settings) -> None:
        self._consumer = Consumer(
            {
                "bootstrap.servers": settings.brokers,
                "group.id": settings.group_id,
                # The offset only moves when this service says so. A timer-driven commit would
                # move it past an event whose decision was never published.
                "enable.auto.commit": False,
                # A new consumer group reads the topic from the beginning: a backlog that accrued
                # while this service was down is a backlog to score, not one to skip.
                "auto.offset.reset": "earliest",
            }
        )
        self._consumer.subscribe([settings.transfer_topic])

    def poll(self, timeout_seconds: float) -> Envelope | None:
        message: Message | None = self._consumer.poll(timeout_seconds)
        if message is None:
            return None
        if message.error():
            if message.error().code() == _CAUGHT_UP:
                return None
            raise KafkaException(message.error())

        value = message.value()
        if value is None:
            # A tombstone on this topic is not a transfer. Returning an empty payload lets the
            # service's own parser refuse it and skip past, which is where that decision belongs.
            value = b""
        return Envelope(value=value, offset=message)

    def commit(self, envelope: Envelope) -> None:
        # Synchronous, and for this message only. Fire-and-forget would let the process exit with
        # the offset unmoved, and the next start would rescore everything since the last timer tick.
        self._consumer.commit(message=envelope.offset, asynchronous=False)

    def close(self) -> None:
        self._consumer.close()


class KafkaDecisionSink:
    """Publishes decisions, and waits for the broker to say it has them."""

    def __init__(self, settings: Settings) -> None:
        self._topic = settings.decision_topic
        self._producer = Producer(
            {
                "bootstrap.servers": settings.brokers,
                "acks": "all",
                "enable.idempotence": True,
                # Bounded. A producer that retries for ever is a service that looks healthy while
                # nothing has been published for an hour.
                "message.timeout.ms": 30_000,
            }
        )

    def publish(self, key: str, payload: bytes) -> None:
        outcome: dict[str, Any] = {}

        def delivered(error: KafkaError | None, message: Message) -> None:
            outcome["error"] = error

        self._producer.produce(self._topic, key=key, value=payload, on_delivery=delivered)
        # flush, not poll: this returns when the broker has acknowledged the message, which is what
        # makes the commit that follows it safe.
        remaining = self._producer.flush(timeout=30)
        if remaining:
            raise KafkaException(f"the decision for {key} was not acknowledged in time")
        if outcome.get("error") is not None:
            raise KafkaException(outcome["error"])

    def close(self) -> None:
        self._producer.flush(timeout=10)
