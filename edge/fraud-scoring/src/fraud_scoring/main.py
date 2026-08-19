"""The process: read the configuration, build the parts, run the loop until asked to stop.

Nothing here decides anything. If this file grows a rule, or a reason to treat one transfer
differently from another, it has taken work that belongs in ``rules.py`` where it can be versioned
and reproduced.
"""

from __future__ import annotations

import logging
import os
import signal
import sys
from types import FrameType

from fraud_scoring.config import ConfigError, Settings, load
from fraud_scoring.observability import Metrics, configure_logging
from fraud_scoring.rules import RULE_SET
from fraud_scoring.scoring import Engine, Parameters
from fraud_scoring.service import ScoringService

LOG = logging.getLogger(__name__)


def parameters_of(settings: Settings) -> Parameters:
    """The tunable half of the configuration, which the recorded model version is a digest of."""
    return Parameters(
        high_amount_minor=settings.high_amount_minor,
        reporting_threshold_minor=settings.reporting_threshold_minor,
        review_threshold=settings.review_threshold,
        block_threshold=settings.block_threshold,
    )


def main() -> int:
    try:
        settings = load(os.environ)
    except ConfigError as broken:
        # Before there is a logger, and an operator reading a crash loop wants plain text rather
        # than a JSON object wrapped around the reason their deployment will not start.
        print(broken, file=sys.stderr)
        return 1

    configure_logging(settings.log_level)

    engine = Engine(RULE_SET, parameters_of(settings))
    metrics = Metrics()
    metrics.serve(settings.metrics_port)

    # Imported here rather than at the top: everything above this line runs without a broker and
    # without librdkafka, which is what lets the whole of the scoring logic be tested on its own.
    from fraud_scoring.kafka_io import KafkaDecisionSink, KafkaEventSource

    service = ScoringService(
        source=KafkaEventSource(settings),
        sink=KafkaDecisionSink(settings),
        engine=engine,
        observer=metrics,
        poll_seconds=settings.poll_seconds,
    )

    running = True

    def stop(signum: int, frame: FrameType | None) -> None:
        # The loop finishes the message it is holding: an event scored but not yet published would
        # otherwise be redelivered and scored again for no reason.
        nonlocal running
        LOG.info("stopping", extra={"signal": signal.Signals(signum).name})
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    LOG.info(
        "fraud-scoring starting",
        extra={
            "brokers": settings.brokers,
            "consuming": settings.transfer_topic,
            "publishing": settings.decision_topic,
            "group": settings.group_id,
            "model_version": engine.model_version,
            "review_threshold": settings.review_threshold,
            "block_threshold": settings.block_threshold,
            "metrics_port": settings.metrics_port,
        },
    )

    try:
        service.run_until(lambda: running)
    finally:
        service.close()
    LOG.info("fraud-scoring stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
