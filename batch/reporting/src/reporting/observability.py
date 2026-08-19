"""What a reporting run says about itself: structured logs and a metrics textfile.

**Metrics go to a file, not to an endpoint.** A batch job is gone before anything could scrape it,
so the node_exporter textfile collector is the mechanism that actually works: the run writes a file,
the exporter picks it up on its next pass, and the figures survive the process that produced them.
A pushgateway would keep the last value indefinitely, which turns a job that has stopped running
into a job whose figures look perfectly healthy - the failure this component would least like to
hide.

**Logs are JSON on stdout**, carrying the business date and the position, so a line can be tied to
the exact cut of the ledger it describes. Never the remittance reference: it is the one piece of
free text a paying customer controls, the ledger deliberately keeps it out of its audit rows, and a
report's log is read by more people than that audit trail is.
"""

from __future__ import annotations

import json
import logging
import pathlib
import sys
from typing import TYPE_CHECKING, Any, Final

from prometheus_client import CollectorRegistry, Gauge, write_to_textfile

if TYPE_CHECKING:  # pragma: no cover - import cycle avoided at runtime, kept for readers
    from reporting.run import RunResult

__all__ = ["JsonFormatter", "configure_logging", "write_metrics"]

#: Fields the logging module puts on every record. Anything else in a record's __dict__ was added by
#: the caller and belongs in the JSON line.
_STANDARD: Final = frozenset(vars(logging.LogRecord("", 0, "", 0, "", None, None)).keys()) | {
    "message",
    "asctime",
    "taskName",
}

#: Never logged, whatever a caller passes. The remittance reference is the field this rule exists
#: for; the others are here so that putting a credential in a log line has to be deliberate.
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


def write_metrics(path: pathlib.Path, result: RunResult) -> None:
    """Write the run's figures in the node_exporter textfile format.

    Gauges rather than counters throughout, because each is the state of one run rather than
    something that accumulates across runs. A counter that resets to zero on every process start is
    a counter whose rate is meaningless.

    ``business_date`` labels the volume metrics so a rerun for a past date cannot be mistaken for
    this morning's figures. ``duration`` and ``position`` are deliberately unlabelled: they are
    about the run and the ledger rather than about the day being reported on, and an operator
    watching either wants one series to look at.
    """
    registry = CollectorRegistry()

    duration = Gauge(
        "tessera_reporting_duration_seconds",
        "Wall-clock seconds the last reporting run took, end to end.",
        registry=registry,
    )
    duration.set(result.duration_seconds)

    position = Gauge(
        "tessera_reporting_position",
        "The ledger audit position the last run was cut at. A figure that does not advance between "
        "nightly runs means nothing posted - a quiet day, or an upstream that stopped.",
        registry=registry,
    )
    position.set(result.position.seq)

    accounts = Gauge(
        "tessera_reporting_accounts_read",
        "Accounts in scope for the reported business date.",
        ["business_date"],
        registry=registry,
    )
    accounts.labels(business_date=result.business_date_ccyymmdd).set(result.accounts_read)

    movements = Gauge(
        "tessera_reporting_movements_read",
        "Postings in scope for the reported business date.",
        ["business_date"],
        registry=registry,
    )
    movements.labels(business_date=result.business_date_ccyymmdd).set(result.movements_read)

    records = Gauge(
        "tessera_reporting_records_written",
        "Records written to each report file.",
        ["business_date", "report"],
        registry=registry,
    )
    for name, count in sorted(result.records_written.items()):
        records.labels(business_date=result.business_date_ccyymmdd, report=name).set(count)

    path.parent.mkdir(parents=True, exist_ok=True)
    write_to_textfile(str(path), registry)
