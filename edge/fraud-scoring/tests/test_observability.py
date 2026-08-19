"""What is logged, what is counted, and what must never appear in either."""

from __future__ import annotations

import json
import logging
from io import StringIO

from prometheus_client import generate_latest

from fraud_scoring.observability import JsonFormatter, Metrics


def capture(**extra: object) -> dict[str, object]:
    stream = StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())

    logger = logging.getLogger("test.observability")
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)
    logger.info("transfer scored", extra=extra)

    return json.loads(stream.getvalue())


def test_a_log_line_is_json_carrying_what_the_caller_attached() -> None:
    line = capture(
        transfer_ref="TB202608190000000001",
        correlation_id="8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e",
        decision="REVIEW",
        score=600,
    )

    assert line["msg"] == "transfer scored"
    assert line["level"] == "INFO"
    assert line["transfer_ref"] == "TB202608190000000001"
    # The id that ties this decision back to the customer request that caused it, across four tiers.
    assert line["correlation_id"] == "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e"
    assert line["score"] == 600


def test_the_remittance_reference_never_reaches_a_log_line() -> None:
    line = capture(transfer_ref="TB202608190000000001", reference="SYNTHETIC-REF")

    # The one field a paying customer controls, and the one the ledger deliberately keeps out of its
    # audit rows. A log store is read by more people than the ledger is.
    assert "reference" not in line
    assert "SYNTHETIC-REF" not in json.dumps(line)


def test_credential_shaped_fields_are_dropped_too() -> None:
    # Built as a mapping rather than as keyword arguments: these are pretend credentials, and the
    # linter is right to flag a literal one - the test's whole point is that a caller who attaches
    # one does not get it logged.
    pretend_credentials = {
        "token": "eyJhbGciOi",
        "password": "hunter2",
        "authorization": "Bearer abc",
    }
    line = capture(**pretend_credentials)

    for forbidden in ("token", "password", "authorization"):
        assert forbidden not in line
    assert "eyJhbGciOi" not in json.dumps(line)
    assert "hunter2" not in json.dumps(line)


def test_an_exception_is_reported_without_becoming_the_message() -> None:
    stream = StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    logger = logging.getLogger("test.observability.errors")
    logger.handlers = [handler]
    logger.propagate = False

    try:
        raise RuntimeError("the broker refused the decision")
    except RuntimeError:
        logger.exception("failed to handle a message")

    line = json.loads(stream.getvalue())
    assert line["msg"] == "failed to handle a message"
    assert "RuntimeError" in line["error"]


def scrape(metrics: Metrics) -> str:
    return generate_latest(metrics.registry).decode()


def test_decisions_are_counted_by_outcome() -> None:
    metrics = Metrics()

    metrics.scored("ALLOW", 0, 0.001)
    metrics.scored("REVIEW", 600, 0.002)
    metrics.scored("REVIEW", 620, 0.002)

    body = scrape(metrics)
    assert 'tessera_fraud_decisions_total{decision="ALLOW"} 1.0' in body
    assert 'tessera_fraud_decisions_total{decision="REVIEW"} 2.0' in body


def test_the_score_distribution_is_visible() -> None:
    metrics = Metrics()

    metrics.scored("BLOCK", 880, 0.001)

    body = scrape(metrics)
    # A rule set drifting towards a threshold shows up here long before anybody notices the change
    # in outcomes, which is the point of a histogram rather than a counter.
    assert "tessera_fraud_score_bucket" in body
    assert "tessera_fraud_score_count 1.0" in body


def test_what_could_not_be_read_and_what_could_not_be_published_are_counted_apart() -> None:
    metrics = Metrics()

    metrics.malformed()
    metrics.publish_failed()
    metrics.publish_failed()

    body = scrape(metrics)
    # Two different incidents: a producer sending nonsense, and a broker refusing. Folding them
    # into one counter makes the first alert useless.
    assert "tessera_fraud_malformed_total 1.0" in body
    assert "tessera_fraud_publish_failures_total 2.0" in body


def test_two_instances_do_not_share_a_registry() -> None:
    first, second = Metrics(), Metrics()

    first.scored("ALLOW", 0, 0.001)

    # The family is declared in both registries; what must not cross is a sample. The process-wide
    # default registry would make the second construction fail outright, or leak counts silently.
    assert 'tessera_fraud_decisions_total{decision="ALLOW"} 1.0' in scrape(first)
    assert 'tessera_fraud_decisions_total{decision="ALLOW"}' not in scrape(second)


def test_the_metrics_satisfy_what_the_service_calls() -> None:
    from fraud_scoring.service import Observer

    # Structural, not nominal: the service asks for an Observer, and Metrics is one without saying
    # so. A rename on either side should fail here rather than at three in the morning.
    metrics: Observer = Metrics()
    metrics.scored("ALLOW", 0, 0.0)
    metrics.malformed()
    metrics.publish_failed()
