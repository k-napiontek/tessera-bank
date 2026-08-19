"""One reporting run: read the ledger at a position, write the files, record what was produced.

The order matters. The position is resolved **first**, inside the snapshot every subsequent query
reads from, so the run cannot report on a ledger that moved between its own queries. Everything
after that is a pure function of the rows read - which is what makes a rerun at the same position
produce the same bytes, rather than merely usually producing them.

The **manifest** is the only artefact that carries a clock. It records the business date, the
position, the chain hash, when the run happened and a SHA-256 of every file it wrote. That split is
deliberate: the reports must be diffable against yesterday's, and a generation timestamp inside one
would make that impossible while looking like helpful metadata.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import pathlib
import time
from dataclasses import dataclass

import psycopg

from reporting import extract, movement_report, position_report
from reporting.config import RunRequest, Settings
from reporting.ledger import LedgerReader, Position

__all__ = ["RunResult", "execute"]

MANIFEST_VERSION = "tessera-reporting-manifest-v1"


@dataclass(frozen=True, slots=True)
class RunResult:
    """What a run read, what it wrote, and how long it took."""

    business_date_ccyymmdd: str
    position: Position
    accounts_read: int
    movements_read: int
    records_written: dict[str, int]
    duration_seconds: float
    output_dir: pathlib.Path


def execute(settings: Settings, request: RunRequest) -> RunResult:
    """Generate every report for one business date and write them, with a manifest."""
    started = time.monotonic()
    output = pathlib.Path(settings.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    stamp = request.business_date.strftime("%Y%m%d")

    with LedgerReader(
        psycopg.connect(settings.dsn), query_timeout_seconds=settings.query_timeout_seconds
    ) as reader:
        position = reader.resolve_position(request.position)
        accounts = reader.accounts_as_at(request.business_date, position)
        movements = reader.movements_on(request.business_date, position)

    positions = position_report.PositionReport.of(request.business_date, position, accounts)
    movement = movement_report.MovementReport.of(request.business_date, position, movements)

    written = {
        f"position-{stamp}.csv": position_report.render(positions),
        f"movements-{stamp}.csv": movement_report.render(movement),
        f"regulatory-extract-{stamp}.txt": extract.render(positions, settings.institution),
    }

    files = []
    for name, content in written.items():
        path = output / name
        encoded = content.encode("ascii")
        path.write_bytes(encoded)
        files.append(
            {
                "name": name,
                "sha256": hashlib.sha256(encoded).hexdigest(),
                "bytes": len(encoded),
                "records": _records_in(content),
            }
        )

    manifest = {
        "manifestVersion": MANIFEST_VERSION,
        "businessDate": stamp,
        "position": {"seq": position.seq, "chainHash": position.chain_hash},
        "institution": settings.institution,
        # The one clock in the whole run. Aware, because an instant without a zone cannot be placed
        # in time and this one is the evidence of when the figures were cut.
        "generatedAt": dt.datetime.now(tz=dt.UTC).isoformat(),
        "accountsRead": len(accounts),
        "movementsRead": len(movements),
        "files": files,
    }
    (output / f"manifest-{stamp}.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    return RunResult(
        business_date_ccyymmdd=stamp,
        position=position,
        accounts_read=len(accounts),
        movements_read=len(movements),
        records_written={entry["name"]: entry["records"] for entry in files},
        duration_seconds=time.monotonic() - started,
        output_dir=output,
    )


def _records_in(content: str) -> int:
    """Lines of content, excluding the trailing terminator.

    Counted from what was written rather than from what the report holds, so the figure in the
    manifest describes the file a receiver will open and not the intention behind it.
    """
    return len(content.splitlines())
