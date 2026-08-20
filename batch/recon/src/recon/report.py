"""The break report - `TB-RECON-BREAKS-V1`, as `contracts/recon/break-report-v1.md` defines it.

The contract is the authority and this module is its writer. Neither reads the other at run time: a
program that read its format out of the contract would agree with it by construction, and by
construction is not by check. The test parses the contract's tables and holds this output to them,
which is the same division ``batch/reporting`` uses for the regulatory extract.

**A run always writes one, including a run that finds nothing.** Absence of output is
indistinguishable from a job that never ran, and the distinction is the entire value of a control.

**The totals are checked before the document exists.** ``accountsCompared`` must equal matched plus
broken, and a report where it does not has lost an account. Refusing to write it is the only
response that does not put a wrong control total in front of an operator; a control total nothing
verifies is decoration.

**No wall clock in the body**, per ADR 0009. The run instant belongs beside the file. A generation
timestamp would make byte-identical reruns impossible by construction while looking like helpful
metadata.
"""

from __future__ import annotations

import datetime as dt
import json
import pathlib
from typing import Any, Final

from recon.compare import Break, ComparisonResult
from recon.cutoff import CutOff
from recon.ledger import Position

__all__ = ["FORMAT_ID", "ReportError", "build_report", "write_report"]

FORMAT_ID: Final = "TB-RECON-BREAKS-V1"

#: CCYYMMDD. One estate, one way of writing a business date, even across thirty years of technology.
_BUSINESS_DATE = "%Y%m%d"


class ReportError(Exception):
    """The report cannot be written, and writing it anyway would be worse than not."""


def build_report(
    *,
    business_date: dt.date,
    position: Position,
    cut_off: CutOff,
    master_name: str,
    master_record_count: int,
    result: ComparisonResult,
) -> dict[str, Any]:
    """The whole document, as a dict ready to serialise. Refuses totals that do not balance."""
    totals = result.totals
    if totals.accounts_compared != totals.accounts_matched + totals.accounts_broken:
        raise ReportError(
            f"the control totals do not balance: {totals.accounts_compared} compared, "
            f"{totals.accounts_matched} matched, {totals.accounts_broken} broken. The report has "
            f"lost an account and is not written."
        )

    return {
        "formatId": FORMAT_ID,
        "businessDate": business_date.strftime(_BUSINESS_DATE),
        "ledgerPosition": position.seq,
        "ledgerChainHash": position.chain_hash,
        "cutOff": {
            "movementFile": cut_off.movement_file,
            "transferRefCount": cut_off.transfer_ref_count,
        },
        "masterFile": {
            "name": master_name,
            "recordCount": master_record_count,
        },
        "breaks": [_render(found) for found in result.breaks],
        "totals": {
            "accountsCompared": totals.accounts_compared,
            "accountsMatched": totals.accounts_matched,
            "accountsBroken": totals.accounts_broken,
            "totalAbsoluteDriftMinor": totals.total_absolute_drift_minor,
        },
    }


def write_report(document: dict[str, Any], directory: pathlib.Path) -> pathlib.Path:
    """Write the document as `BREAKS-CCYYMMDD.json`, returning where it landed."""
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"BREAKS-{document['businessDate']}.json"
    # sort_keys is off on purpose: the contract's tables are in a reading order chosen for an
    # operator, and the writer emits them in it. Byte-identical reruns come from the document being
    # a pure function of its two inputs, not from alphabetising it.
    path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def _render(found: Break) -> dict[str, Any]:
    return {
        "accountRef": found.account_ref,
        "classification": found.classification.value,
        "currency": found.currency,
        "masterBookedMinor": found.master_booked_minor,
        "ledgerBookedMinor": found.ledger_booked_minor,
        "differenceMinor": found.difference_minor,
    }
