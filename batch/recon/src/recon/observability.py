"""What a reconciliation run says about itself: a metrics textfile.

**Metrics go to a file, not to an endpoint.** A batch job is gone before anything could scrape it,
so the node_exporter textfile collector is the mechanism that actually works: the run writes a file,
the exporter picks it up on its next pass, and the figures survive the process that produced them.
A pushgateway would keep the last value indefinitely, which turns a job that has stopped running
into a job whose figures look perfectly healthy.

**Breaks are counted by classification, never as one number.** An alert on "breaks > 0" would page
somebody every morning for timing differences that are expected - the same failure as classifying
them as drift, arriving by a different route. The series an operator should alert on is
``VALUE_DRIFT``, ``MISSING_ON_MASTER`` and ``MISSING_IN_LEDGER``; ``TIMING`` is there to be looked
at, not to wake anybody.

**Every classification gets a series even at zero.** A series that disappears when everything is
fine is one nobody can build a dashboard on, because the absence of the series and the absence of
the job are the same picture.
"""

from __future__ import annotations

import pathlib
from typing import TYPE_CHECKING

from prometheus_client import CollectorRegistry, Gauge, write_to_textfile

from recon.compare import Classification

if TYPE_CHECKING:  # pragma: no cover - import cycle avoided at runtime, kept for readers
    from recon.run import RunResult

__all__ = ["write_metrics"]


def write_metrics(path: pathlib.Path, result: RunResult) -> None:
    """Write the run's figures in the node_exporter textfile format.

    Gauges throughout, because each is the state of one run rather than something that accumulates.
    A counter that resets to zero on every process start is a counter whose rate is meaningless.
    """
    registry = CollectorRegistry()

    Gauge(
        "tessera_recon_last_success",
        "1 when the last reconciliation run completed. Absent means it has not run at all, which "
        "is the case a dashboard must not confuse with a clean morning.",
        registry=registry,
    ).set(1)

    Gauge(
        "tessera_recon_duration_seconds",
        "Wall-clock seconds the last reconciliation took, end to end.",
        registry=registry,
    ).set(result.duration_seconds)

    Gauge(
        "tessera_recon_position",
        "The ledger audit position the last run was cut at. A figure that does not advance between "
        "mornings means nothing posted - a quiet day, or an upstream that stopped.",
        registry=registry,
    ).set(result.position_seq)

    compared = Gauge(
        "tessera_recon_accounts_compared",
        "Accounts seen on either side.",
        ["business_date"],
        registry=registry,
    )
    compared.labels(business_date=result.business_date_ccyymmdd).set(result.accounts_compared)

    matched = Gauge(
        "tessera_recon_accounts_matched",
        "Accounts whose booked balances agreed exactly.",
        ["business_date"],
        registry=registry,
    )
    matched.labels(business_date=result.business_date_ccyymmdd).set(result.accounts_matched)

    breaks = Gauge(
        "tessera_recon_breaks",
        "Breaks by classification. Alert on VALUE_DRIFT, MISSING_ON_MASTER and MISSING_IN_LEDGER; "
        "TIMING is expected and paging on it teaches operators to ignore the report.",
        ["business_date", "classification"],
        registry=registry,
    )
    for classification in Classification:
        breaks.labels(
            business_date=result.business_date_ccyymmdd,
            classification=classification.value,
        ).set(result.breaks_by_classification.get(classification, 0))

    drift = Gauge(
        "tessera_recon_absolute_drift_minor",
        "Sum of the absolute value of every break's difference, in minor units. Absolute because "
        "equal and opposite errors on two accounts is the most alarming shape, not the least.",
        ["business_date"],
        registry=registry,
    )
    drift.labels(business_date=result.business_date_ccyymmdd).set(result.total_absolute_drift_minor)

    path.parent.mkdir(parents=True, exist_ok=True)
    # write_to_textfile writes to a temporary file and renames, so the exporter never reads a
    # half-written set of figures.
    write_to_textfile(str(path), registry)
