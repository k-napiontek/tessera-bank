"""One reconciliation run: read both sides, compare, report, and say what it found.

The order is forced and worth stating. The **cut-off is read first**, because it decides what the
ledger read means; the **master next**, because it is the side that cannot change under the run; and
the **ledger last and at a position**, so that the figures can be reproduced exactly. Reading the
ledger before fixing the position would let a posting land between the two and produce a report no
rerun could reproduce.

Nothing here writes to either core. The only thing this module creates is a file of its own.
"""

from __future__ import annotations

import datetime as dt
import logging
import pathlib
import time
from dataclasses import dataclass

import psycopg

from recon.compare import Classification, compare
from recon.cutoff import read_cut_off
from recon.ledger import LedgerReader, Position
from recon.master import read_master
from recon.report import build_report, write_report

__all__ = ["RunRequest", "RunResult", "execute"]

LOG = logging.getLogger("recon")


@dataclass(frozen=True, slots=True)
class RunRequest:
    """This run: which day, which two files, and which ledger cut to reproduce."""

    business_date: dt.date
    master_path: pathlib.Path
    movement_path: pathlib.Path
    output_dir: pathlib.Path
    position_seq: int | None = None


@dataclass(frozen=True, slots=True)
class RunResult:
    """What the run found. The report is the record; this is what the run says about itself."""

    business_date_ccyymmdd: str
    position_seq: int
    accounts_compared: int
    accounts_matched: int
    breaks_by_classification: dict[Classification, int]
    total_absolute_drift_minor: int
    duration_seconds: float
    report_path: pathlib.Path

    @property
    def accounts_broken(self) -> int:
        return sum(self.breaks_by_classification.values())

    @property
    def needs_an_operator(self) -> bool:
        """True when a break is something a human has to work. Timing is not."""
        return any(
            count
            for classification, count in self.breaks_by_classification.items()
            if classification is not Classification.TIMING
        )


def execute(dsn: str, request: RunRequest, *, query_timeout_seconds: int = 60) -> RunResult:
    """Run the reconciliation and write its report. Read-only against both cores."""
    started = time.monotonic()

    cut_off = read_cut_off(request.movement_path)
    master_records = read_master(request.master_path)

    with LedgerReader(
        psycopg.connect(dsn, autocommit=True), query_timeout_seconds=query_timeout_seconds
    ) as reading:
        position: Position = reading.resolve_position(request.position_seq)
        ledger_accounts = reading.accounts_as_at(
            request.business_date, position, cut_off.transfer_refs
        )

    result = compare(master_records, ledger_accounts)
    document = build_report(
        business_date=request.business_date,
        position=position,
        cut_off=cut_off,
        master_name=request.master_path.name,
        master_record_count=len(master_records),
        result=result,
    )
    report_path = write_report(document, request.output_dir)

    by_classification = {classification: 0 for classification in Classification}
    for found in result.breaks:
        by_classification[found.classification] += 1

    run = RunResult(
        business_date_ccyymmdd=document["businessDate"],
        position_seq=position.seq,
        accounts_compared=result.totals.accounts_compared,
        accounts_matched=result.totals.accounts_matched,
        breaks_by_classification=by_classification,
        total_absolute_drift_minor=result.totals.total_absolute_drift_minor,
        duration_seconds=time.monotonic() - started,
        report_path=report_path,
    )

    LOG.info(
        "reconciliation complete",
        extra={
            "businessDate": run.business_date_ccyymmdd,
            "position": run.position_seq,
            "accountsCompared": run.accounts_compared,
            "accountsBroken": run.accounts_broken,
            "needsAnOperator": run.needs_an_operator,
            "report": str(report_path),
        },
    )
    return run
