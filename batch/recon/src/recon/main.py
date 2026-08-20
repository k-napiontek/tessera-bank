"""The command line. One run, one exit code, and an exit code that means something.

**A break is not a failure of this job.** The reconciliation succeeded: it compared two systems and
found they disagree. Exiting non-zero would make every scheduler treat a working control as a broken
job, and the first response to a red job is to rerun it - which here would produce the same breaks
and waste the morning. So breaks leave the exit code at 0 and are reported in the file and the
metrics, which is where a control's findings belong.

Exit 1 is for the job genuinely not running: a file that is not a file, a ledger that cannot be
read, a position this database has never held.
"""

from __future__ import annotations

import argparse
import datetime as dt
import logging
import os
import pathlib
import sys
from collections.abc import Sequence

from recon.cutoff import CutOffError
from recon.master import MasterFileError
from recon.observability import write_metrics
from recon.report import ReportError
from recon.run import RunRequest, execute

__all__ = ["main"]

LOG = logging.getLogger("recon")


def main(argv: Sequence[str] | None = None) -> int:
    parsed = _parse(argv if argv is not None else sys.argv[1:])
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    dsn = os.environ.get("RECON_LEDGER_DSN")
    if not dsn:
        print(
            "RECON_LEDGER_DSN is not set; the reconciliation has no ledger to read", file=sys.stderr
        )
        return 1

    request = RunRequest(
        business_date=parsed.business_date,
        master_path=parsed.master,
        movement_path=parsed.movements,
        output_dir=parsed.output,
        position_seq=parsed.position,
    )

    try:
        result = execute(dsn, request)
    except (CutOffError, MasterFileError, ReportError, LookupError) as problem:
        print(f"the reconciliation did not run: {problem}", file=sys.stderr)
        return 1

    if parsed.metrics is not None:
        write_metrics(parsed.metrics, result)

    print(
        f"{result.accounts_compared} compared, {result.accounts_matched} matched, "
        f"{result.accounts_broken} broken -> {result.report_path}"
    )
    return 0


def _parse(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="recon",
        description="Reconcile the COBOL account master against the PostgreSQL ledger.",
    )
    parser.add_argument(
        "--business-date",
        required=True,
        type=_business_date,
        help="CCYYMMDD, the day being reconciled",
    )
    parser.add_argument(
        "--master", required=True, type=pathlib.Path, help="the account master the cycle produced"
    )
    parser.add_argument(
        "--movements",
        required=True,
        type=pathlib.Path,
        help="the movement file the cycle consumed - this is the cut-off, see ADR 0015",
    )
    parser.add_argument(
        "--output", required=True, type=pathlib.Path, help="where the break report is written"
    )
    parser.add_argument(
        "--metrics", type=pathlib.Path, default=None, help="node_exporter textfile to write"
    )
    parser.add_argument(
        "--position",
        type=int,
        default=None,
        help="an audit_record.seq to reproduce an earlier run's cut; omit for the current one",
    )
    return parser.parse_args(argv)


def _business_date(value: str) -> dt.date:
    try:
        return dt.datetime.strptime(value, "%Y%m%d").replace(tzinfo=dt.UTC).date()
    except ValueError as problem:
        raise argparse.ArgumentTypeError(
            f"{value!r} is not a CCYYMMDD business date - the form the JCL runner takes"
        ) from problem
