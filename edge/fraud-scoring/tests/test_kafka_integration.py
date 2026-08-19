"""One test against a real broker.

Everything else in this suite drives the service through fakes, which is right: the fakes make the
ordering and the scoring assertable without a container. What they cannot prove is that the adapter
in ``kafka_io.py`` is configured correctly - that the offset is not auto-committed behind the
service's back, that a decision is really acknowledged before ``publish`` returns, and that a
restart does not rescore what has already been handled.

That is the same argument WP-09 made for ``KafkaOutboxContractTest`` on the Java side, and it comes
out the same way: an adapter proved only against a double is verified by construction rather than by
use.

Needs a running Docker daemon. It is not marked as optional, because a suite that quietly skips its
only real-broker test on the machine where Docker is not running is a suite that reports success for
a service nobody has proved works.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from datetime import UTC, datetime

import pytest
from confluent_kafka import Consumer, Producer
from confluent_kafka.admin import AdminClient
from jsonschema import Draft202012Validator
from testcontainers.community.kafka import KafkaContainer

from fraud_scoring.config import Settings, load
from fraud_scoring.kafka_io import KafkaDecisionSink, KafkaEventSource
from fraud_scoring.rules import RULE_SET
from fraud_scoring.scoring import Engine, Parameters
from fraud_scoring.service import ScoringService

from .fixtures import transfer_payload
from .test_contract import document

TRANSFER_TOPIC = "tessera.ledger.transfer-posted.v1"
DECISION_TOPIC = "tessera.fraud.decision.v1"

PARAMETERS = Parameters(
    high_amount_minor=10_000_000,
    reporting_threshold_minor=1_000_000,
    review_threshold=400,
    block_threshold=750,
)


@pytest.fixture(scope="module")
def broker() -> Iterator[str]:
    with KafkaContainer() as container:
        yield container.get_bootstrap_server()


@pytest.fixture
def settings(broker: str) -> Settings:
    return load(
        {
            "TB_FRAUD_BROKERS": broker,
            "TB_FRAUD_TRANSFER_TOPIC": TRANSFER_TOPIC,
            "TB_FRAUD_DECISION_TOPIC": DECISION_TOPIC,
            # A group of its own per test, so one test's committed offsets cannot decide what
            # another test sees.
            "TB_FRAUD_GROUP_ID": f"fraud-scoring-{datetime.now(UTC).timestamp()}",
            "TB_FRAUD_POLL_SECONDS": "1",
        }
    )


def publish_transfer(broker: str, **overrides: object) -> str:
    payload = transfer_payload(**overrides)
    producer = Producer({"bootstrap.servers": broker, "acks": "all"})
    producer.produce(
        TRANSFER_TOPIC,
        key=payload["transferRef"],
        value=json.dumps(payload).encode(),
    )
    producer.flush(timeout=30)
    return str(payload["transferRef"])


def publish_raw(broker: str, key: str, value: bytes) -> None:
    producer = Producer({"bootstrap.servers": broker, "acks": "all"})
    producer.produce(TRANSFER_TOPIC, key=key, value=value)
    producer.flush(timeout=30)


def decisions_for(broker: str, key: str, timeout_seconds: int = 30) -> list[dict]:
    """Every decision published for one transfer.

    Filtered by key rather than counted, because the topic outlives each test in this module: taking
    "the first message on the topic" would assert against whatever the previous test left there.
    """
    consumer = Consumer(
        {
            "bootstrap.servers": broker,
            "group.id": f"assertions-{datetime.now(UTC).timestamp()}",
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
    consumer.subscribe([DECISION_TOPIC])
    try:
        collected: list[dict] = []
        idle = 0
        deadline = datetime.now(UTC).timestamp() + timeout_seconds
        while datetime.now(UTC).timestamp() < deadline:
            message = consumer.poll(1.0)
            if message is None or message.error():
                idle += 1
                # Two consecutive empty polls once something has been found means the topic has
                # been read to its end.
                if idle >= 2 and collected:
                    break
                continue
            idle = 0
            if message.key() is not None and message.key().decode() == key:
                collected.append(json.loads(message.value()))
        return collected
    finally:
        consumer.close()


def service_for(settings: Settings) -> ScoringService:
    return ScoringService(
        source=KafkaEventSource(settings),
        sink=KafkaDecisionSink(settings),
        engine=Engine(RULE_SET, PARAMETERS),
        poll_seconds=settings.poll_seconds,
    )


def drain(service: ScoringService, attempts: int = 20) -> int:
    handled = 0
    for _ in range(attempts):
        if service.run_once():
            handled += 1
    return handled


def test_a_real_transfer_produces_a_schema_valid_decision(broker: str, settings: Settings) -> None:
    transfer_ref = publish_transfer(broker, amount={"amountMinor": 999_000, "currency": "PLN"})

    service = service_for(settings)
    try:
        assert drain(service) >= 1
    finally:
        service.close()

    published = decisions_for(broker, key=transfer_ref)
    assert published, "no decision was published"

    payload = published[-1]
    # Keyed by transferRef, so the decision co-partitions with the transfer and a duplicate
    # collapses under compaction - which is what the key filter above relies on.
    assert payload["transferRef"] == transfer_ref
    assert payload["decision"] == "ALLOW"
    assert payload["reasonCodes"] == ["AMT_STRC"]
    assert payload["modelVersion"].startswith("rules-2026.08.1+")

    # The published bytes, against the contract itself rather than against what this code believes
    # the contract says.
    Draft202012Validator(
        {"$ref": "#/components/schemas/FraudDecisionPayload", **document()}
    ).validate(payload)


def test_a_handled_event_is_not_rescored_after_a_restart(broker: str, settings: Settings) -> None:
    publish_transfer(broker)

    first = service_for(settings)
    try:
        assert drain(first) >= 1
    finally:
        first.close()

    # A second service in the same consumer group. If the offset had not been committed, or had
    # been committed by a timer rather than by the service, this would score the event again.
    second = service_for(settings)
    try:
        assert drain(second, attempts=3) == 0
    finally:
        second.close()


def test_a_poison_message_does_not_stop_the_ones_behind_it(broker: str, settings: Settings) -> None:
    publish_raw(broker, key="TB000000000000POISON", value=b"{ not json at all")
    transfer_ref = publish_transfer(broker, transferRef="TB202608190000000009")

    service = service_for(settings)
    try:
        drain(service)
    finally:
        service.close()

    # The malformed message was skipped and committed; the transfer behind it was scored. Left
    # uncommitted, that one message would be the only thing this service ever read again.
    assert decisions_for(broker, key=transfer_ref)


def test_the_topics_exist_under_the_names_the_contract_declares(broker: str) -> None:
    metadata = AdminClient({"bootstrap.servers": broker}).list_topics(timeout=30)

    assert TRANSFER_TOPIC in metadata.topics
    assert DECISION_TOPIC in metadata.topics
