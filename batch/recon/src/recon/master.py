"""Reading `ACCTREC` - the COBOL account master, as stratum 0 rewrote it last night.

100 bytes, fixed, ascending by ``ACCT-REF``. The copybook is the contract and
``contracts/copybook/ACCTREC.cpy`` says so in its own header: *the definitive record of what every
account contains, read and rewritten by the overnight cycle, read by reconciliation.* This is that
reader.

**The offsets below are declared, and the test derives them from the contract and asserts they
agree** - the same division ``MovementRecord`` and ``MovementRecordTest`` use at stratum 2. Reading
the layout out of the contracts checker at run time would make this module agree with the checker by
construction and would put a subprocess in the path of a batch job.

**The whole file is read into memory and that is a deliberate difference from stratum 0.**
``ACCTPOST`` match-merges precisely because the master does not fit in a 1995 address space, and
CLAUDE.md records loading it as the mistake that passes every test and destroys the point of the
tier. Nothing in that reasoning binds a 2026 reconciliation running on a machine with gigabytes:
the constraint was the era's, not the file's. What *is* carried forward is the match-merge in
``compare`` - not because memory demands it, but because a single ordered pass over two sorted
inputs is the shape that stays correct when the estate outgrows this assumption.
"""

from __future__ import annotations

import pathlib
from dataclasses import dataclass
from typing import Final

from recon.comp3 import DecodeError
from recon.comp3 import decode as decode_comp3

__all__ = ["LAYOUT", "LENGTH", "AccountRecord", "MasterFileError", "read_master"]

#: `ACCTREC` is 100 bytes. Asserted against the contracts checker by the test, never counted here.
LENGTH: Final = 100

#: Field name -> (0-based offset, size). The copybook's own names, so a failure names the field an
#: operator can find in `ACCTREC.cpy` rather than a byte position they would have to count to.
LAYOUT: Final[dict[str, tuple[int, int]]] = {
    "ACCT-REF": (0, 16),
    "ACCT-CUST-REF": (16, 12),
    "ACCT-TYPE": (28, 9),
    "ACCT-CURRENCY": (37, 3),
    "ACCT-STATUS": (40, 7),
    "ACCT-BOOKED-BAL": (47, 8),
    "ACCT-AVAIL-BAL": (55, 8),
    "ACCT-OPENED-DATE": (63, 8),
    "ACCT-LAST-MOVE-DATE": (71, 8),
    "FILLER": (79, 21),
}


class MasterFileError(Exception):
    """The master is not a master. Refused whole rather than read in part."""


@dataclass(frozen=True, slots=True)
class AccountRecord:
    """One account as stratum 0 holds it. Balances are minor units, never floats."""

    account_ref: str
    customer_ref: str
    account_type: str
    currency: str
    status: str
    booked_minor: int
    available_minor: int
    opened_date: str
    last_move_date: str


def read_master(path: pathlib.Path) -> list[AccountRecord]:
    """Every `ACCTREC` in the file, in the order the file holds them."""
    raw = path.read_bytes()
    if len(raw) % LENGTH:
        raise MasterFileError(
            f"{path} is {len(raw)} bytes, which is not a whole number of {LENGTH}-byte ACCTREC "
            f"records ({len(raw) % LENGTH} bytes over). Something truncated it; it is not read."
        )

    records = []
    for number, start in enumerate(range(0, len(raw), LENGTH), start=1):
        records.append(_record(raw[start : start + LENGTH], path, number))
    return records


def _record(raw: bytes, path: pathlib.Path, number: int) -> AccountRecord:
    try:
        return AccountRecord(
            account_ref=_text(raw, "ACCT-REF"),
            customer_ref=_text(raw, "ACCT-CUST-REF"),
            account_type=_text(raw, "ACCT-TYPE"),
            currency=_text(raw, "ACCT-CURRENCY"),
            status=_text(raw, "ACCT-STATUS"),
            booked_minor=_packed(raw, "ACCT-BOOKED-BAL"),
            available_minor=_packed(raw, "ACCT-AVAIL-BAL"),
            opened_date=_text(raw, "ACCT-OPENED-DATE"),
            last_move_date=_text(raw, "ACCT-LAST-MOVE-DATE"),
        )
    except (DecodeError, UnicodeDecodeError) as problem:
        # The record number, because "record 147" is something an operator can seek to and a byte
        # offset is something they would have to divide.
        raise MasterFileError(f"{path} record {number}: {problem}") from problem


def _text(raw: bytes, field: str) -> str:
    at, size = LAYOUT[field]
    # ASCII rather than UTF-8: a display field on this master is single-byte by definition, and
    # decoding it as UTF-8 would silently accept a byte pair that is not one character there.
    return raw[at : at + size].decode("ascii").strip()


def _packed(raw: bytes, field: str) -> int:
    at, size = LAYOUT[field]
    try:
        return decode_comp3(raw[at : at + size])
    except DecodeError as problem:
        raise DecodeError(f"{field}: {problem}") from problem
