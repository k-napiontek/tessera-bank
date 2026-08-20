"""The cut-off - which transfers the overnight cycle had already seen when it ran.

Every document in this estate refers to "the mainframe cut-off". None of them defined it until
WP-16, and a wall clock could not: the ledger and the cycle share no clock, and a boundary drawn at
a time stops being reproducible the moment either side's moves. The cycle's *input* is a file, and
that file names every transfer it carried in ``MOV-TRANSFER-REF``. So:

    **The set of transfer references in the movement file the cycle consumed is the cut-off.**

A ledger entry whose reference is in that set must have reached the master; one whose reference is
not is expected to be absent, and is timing rather than drift. The answer is exact, derived from the
two inputs alone, and unchanged by rerunning either side. ADR 0015 records the reasoning.

**The key field only, never the file as text.** ``MOV-REFERENCE`` is a remittance reference - free
text a paying customer controls - and it may perfectly well quote a transfer reference. Scanning
the file as text would admit a movement the cycle never applied. This is the same trap ADR 0014
names for the writer, seen from the reading side, and the fixed width makes the honest answer a seek
at a 120-byte stride rather than a parse.
"""

from __future__ import annotations

import pathlib
from dataclasses import dataclass
from typing import Final

__all__ = [
    "MOVEMENT_LENGTH",
    "TRANSFER_REF_AT",
    "TRANSFER_REF_SIZE",
    "CutOff",
    "CutOffError",
    "read_cut_off",
]

#: `MOVEREC` is 120 bytes. Asserted against the contracts checker by the test, never counted here.
MOVEMENT_LENGTH: Final = 120

#: `MOV-TRANSFER-REF`, the first twenty bytes of every record.
TRANSFER_REF_AT: Final = 0
TRANSFER_REF_SIZE: Final = 20


class CutOffError(Exception):
    """The movement file is not a movement file. Refused whole rather than read in part."""


@dataclass(frozen=True, slots=True)
class CutOff:
    """What the cycle had already applied, and which file said so."""

    movement_file: str
    transfer_refs: frozenset[str]

    @property
    def transfer_ref_count(self) -> int:
        """Distinct transfers, not records. A transfer is two legs and counts once."""
        return len(self.transfer_refs)

    def admits(self, transfer_ref: str) -> bool:
        return transfer_ref in self.transfer_refs


def read_cut_off(path: pathlib.Path) -> CutOff:
    """Every distinct `MOV-TRANSFER-REF` in the movement file the cycle consumed."""
    raw = path.read_bytes()
    if len(raw) % MOVEMENT_LENGTH:
        raise CutOffError(
            f"{path} is {len(raw)} bytes, which is not a whole number of {MOVEMENT_LENGTH}-byte "
            f"MOVEREC records ({len(raw) % MOVEMENT_LENGTH} bytes over). It is not read: a cut-off "
            f"taken from a truncated file would silently admit fewer transfers than the cycle "
            f"applied, and every one of them would report as drift."
        )

    refs = set()
    for start in range(0, len(raw), MOVEMENT_LENGTH):
        at = start + TRANSFER_REF_AT
        refs.add(raw[at : at + TRANSFER_REF_SIZE].decode("ascii").strip())
    return CutOff(movement_file=path.name, transfer_refs=frozenset(refs))
