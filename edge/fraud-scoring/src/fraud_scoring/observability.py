"""What this service says about itself: structured logs and Prometheus metrics.

The logs are JSON on stdout, carrying the correlation id that came in on the event, so one customer
request can be followed from the gateway through the ledger to the decision taken about it here.

**No remittance reference, ever.** The ledger deliberately keeps that field out of its audit rows -
it is the one piece of free text a paying customer controls, and it is where a name or a note about
a person ends up. A log line here would put it in a store read by more people than the ledger is.
The account references and the correlation id are what this estate logs.

The metrics answer the questions an operator actually asks: is anything being scored, what is it
deciding, is it keeping up, and is anything arriving that it cannot read.
"""

from __future__ import annotations

import json
import logging
import sys
from typing import Any, Final

from prometheus_client import CollectorRegistry, Counter, Histogram, start_http_server

#: Fields the logging module puts on every record. Anything else in a record's __dict__ was added
#: by the caller and belongs in the JSON line.
_STANDARD: Final = frozenset(vars(logging.LogRecord("", 0, "", 0, "", None, None)).keys()) | {
    "message",
    "asctime",
    "taskName",
}

#: Never logged, whatever a caller passes. The remittance reference is the field this rule exists
#: for; the others are here so that adding a credential to a log line has to be deliberate.
_FORBIDDEN: Final = frozenset({"reference", "password", "token", "secret", "authorization"})


class JsonFormatter(logging.Formatter):
    """One JSON object per line, with whatever the call site attached."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "time": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _STANDARD and key.lower() not in _FORBIDDEN:
                payload[key] = value
        if record.exc_info:
            payload["error"] = self.formatException(record.exc_info)
        return json.dumps(payload, sort_keys=True, default=str)


def configure_logging(level: str) -> None:
    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(JsonFormatter())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())


class Metrics:
    """The instruments, in a registry of their own rather than the process-wide default."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()

        self.decisions = Counter(
            "tessera_fraud_decisions_total",
            "Decisions published, by outcome.",
            ["decision"],
            registry=self.registry,
        )
        self.scores = Histogram(
            "tessera_fraud_score",
            "Distribution of the scores produced.",
            # Buckets on the thresholds that matter: the two boundaries, and enough either side to
            # show a rule set drifting towards one of them before it arrives.
            buckets=(0, 100, 200, 300, 400, 500, 600, 700, 750, 850, 1000),
            registry=self.registry,
        )
        self.latency = Histogram(
            "tessera_fraud_scoring_seconds",
            "How long scoring one event took, excluding the publish.",
            buckets=(0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1),
            registry=self.registry,
        )
        self.malformed_total = Counter(
            "tessera_fraud_malformed_total",
            "Messages that could not be read as a transfer event and were skipped.",
            registry=self.registry,
        )
        self.publish_failures_total = Counter(
            "tessera_fraud_publish_failures_total",
            "Attempts to publish a decision that failed. The event is redelivered.",
            registry=self.registry,
        )

    # The Observer protocol the service calls.

    def scored(self, decision: str, score: int, seconds: float) -> None:
        self.decisions.labels(decision=decision).inc()
        self.scores.observe(score)
        self.latency.observe(seconds)

    def malformed(self) -> None:
        self.malformed_total.inc()

    def publish_failed(self) -> None:
        self.publish_failures_total.inc()

    def serve(self, port: int) -> None:
        """Expose the registry. The scrape endpoint is the only port this service listens on."""
        start_http_server(port, registry=self.registry)
