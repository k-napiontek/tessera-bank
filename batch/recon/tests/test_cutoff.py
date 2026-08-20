"""The cut-off: which transfers the overnight cycle had already seen.

The movement file is the answer, per ADR 0015. These tests hold the reader to the copybook's own
offsets - taken from the contracts checker, not counted - and to the one property that makes the
whole classification work: a reference in this file means the master should hold that movement.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

import pytest

from recon.cutoff import MOVEMENT_LENGTH, CutOffError, read_cut_off

CHECKER = "contracts/check-copybook-offsets.py"


def movement(transfer_ref: str, account_ref: str = "TB00000000000001") -> bytes:
    """One MOVEREC, filled just enough to be a whole record."""
    record = bytearray(b" " * MOVEMENT_LENGTH)
    record[0:20] = transfer_ref.ljust(20).encode("ascii")
    record[20:22] = b"01"
    record[22:38] = account_ref.ljust(16).encode("ascii")
    return bytes(record)


def test_the_transfer_reference_is_where_the_copybook_says(repo: pathlib.Path) -> None:
    from recon.cutoff import TRANSFER_REF_AT, TRANSFER_REF_SIZE

    finished = subprocess.run(  # noqa: S603 - fixed argv, no shell
        [sys.executable, CHECKER, "--json", "MOVEREC"],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    )
    view = json.loads(finished.stdout)
    field = next(f for f in view["fields"] if f["name"] == "MOV-TRANSFER-REF")
    assert view["length"] == MOVEMENT_LENGTH
    assert TRANSFER_REF_AT == field["start"] - 1
    assert TRANSFER_REF_SIZE == field["size"]


def test_the_references_come_back_deduplicated(tmp_path: pathlib.Path) -> None:
    """A transfer is two legs. The cut-off is a set of transfers, not a count of records."""
    path = tmp_path / "MOVEMENT.DAT"
    path.write_bytes(movement("TB202608180000000001") + movement("TB202608180000000001"))
    cut_off = read_cut_off(path)
    assert cut_off.transfer_refs == frozenset({"TB202608180000000001"})
    assert cut_off.transfer_ref_count == 1


def test_an_empty_movement_file_is_a_cut_off_that_admits_nothing(tmp_path: pathlib.Path) -> None:
    """Meaningful rather than a default: the cycle applied nothing, so everything is timing."""
    path = tmp_path / "MOVEMENT.DAT"
    path.write_bytes(b"")
    cut_off = read_cut_off(path)
    assert cut_off.transfer_refs == frozenset()
    assert cut_off.transfer_ref_count == 0


def test_a_file_that_is_not_whole_records_is_refused(tmp_path: pathlib.Path) -> None:
    path = tmp_path / "MOVEMENT.DAT"
    path.write_bytes(movement("TB202608180000000001")[:-3])
    with pytest.raises(CutOffError, match="not a whole number"):
        read_cut_off(path)


def test_the_file_name_is_carried_for_the_report(tmp_path: pathlib.Path) -> None:
    path = tmp_path / "MOVEMENT.DAT"
    path.write_bytes(movement("TB202608180000000001"))
    assert read_cut_off(path).movement_file == "MOVEMENT.DAT"


def test_it_reads_the_key_field_only_never_the_file_as_text(tmp_path: pathlib.Path) -> None:
    """A remittance reference is free text and may perfectly well quote a transfer reference.

    The same trap ADR 0014 names for the writer, seen from the reading side: a scan over the file as
    text would pick this up as a second transfer and admit a movement the cycle never applied.
    """
    record = bytearray(movement("TB202608180000000001"))
    record[72:107] = b"PAYMENT FOR TB202608180000000099   "
    path = tmp_path / "MOVEMENT.DAT"
    path.write_bytes(bytes(record))
    assert read_cut_off(path).transfer_refs == frozenset({"TB202608180000000001"})
