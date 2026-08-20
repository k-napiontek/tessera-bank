"""The account master reader, held to the copybook and to a file stratum 0 wrote.

The layout is **not counted by hand here**. It comes from
``contracts/check-copybook-offsets.py --json ACCTREC``, which parses the copybook and computes the
offsets - the same view ``MovementRecordTest`` uses at stratum 2, and for the same reason. A reader
whose offsets were transcribed agrees with the transcription, not with the contract.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

import pytest

from recon.master import LENGTH, AccountRecord, MasterFileError, read_master

CHECKER = "contracts/check-copybook-offsets.py"


@pytest.fixture(scope="module")
def copybook(repo: pathlib.Path) -> dict[str, object]:
    """ACCTREC as the contracts checker computes it, parsed from its --json view."""
    finished = subprocess.run(  # noqa: S603 - fixed argv, no shell
        [sys.executable, CHECKER, "--json", "ACCTREC"],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(finished.stdout)


def test_the_record_length_is_the_copybooks(copybook: dict[str, object]) -> None:
    assert copybook["length"] == LENGTH


def test_every_offset_comes_from_the_contract(copybook: dict[str, object]) -> None:
    """Fails naming the field if a copybook field is moved or resized."""
    from recon.master import LAYOUT

    fields = {field["name"]: field for field in copybook["fields"]}  # type: ignore[union-attr]
    assert set(fields) == set(LAYOUT), (
        "the reader and the copybook disagree about which fields exist"
    )
    for name, (at, size) in LAYOUT.items():
        field = fields[name]
        assert at == field["start"] - 1, f"{name} starts at {field['start']}, not {at + 1}"
        assert size == field["size"], f"{name} is {field['size']} bytes, not {size}"


def test_the_seeded_edge_cases_read_back(master_file: pathlib.Path) -> None:
    """The balances WP-03 seeds for exactly this: zero, the maximum, and the awkward negatives."""
    by_ref = {record.account_ref: record for record in read_master(master_file)}

    assert by_ref["TB00000000000002"].booked_minor == 0
    assert by_ref["TB00000000000003"].booked_minor == 999_999_999_999_999
    assert by_ref["TB00000000000004"].booked_minor == -125_000
    assert by_ref["TB00000000000005"].booked_minor == -1
    assert by_ref["TB00000000000006"].booked_minor == 1


def test_the_text_fields_are_trimmed_not_padded(master_file: pathlib.Path) -> None:
    record = next(iter(read_master(master_file)))
    assert record.account_ref == record.account_ref.strip()
    assert record.currency == "PLN"
    assert record.status == "OPEN"


def test_the_master_is_ascending_by_account_reference(master_file: pathlib.Path) -> None:
    """The match-merge in task 5 depends on this, and the copybook promises it."""
    refs = [record.account_ref for record in read_master(master_file)]
    assert refs == sorted(refs)
    assert len(refs) == len(set(refs))


def test_a_file_that_is_not_whole_records_is_refused(tmp_path: pathlib.Path) -> None:
    """Refused outright rather than partially read - something else corrupted it."""
    truncated = tmp_path / "ACCTMAST.DAT"
    truncated.write_bytes(b"\x00" * (LENGTH + 7))
    with pytest.raises(MasterFileError, match="not a whole number"):
        read_master(truncated)


def test_an_empty_master_is_not_an_error(tmp_path: pathlib.Path) -> None:
    """A bank with no accounts is absurd; a file with no records is still a valid file."""
    empty = tmp_path / "ACCTMAST.DAT"
    empty.write_bytes(b"")
    assert read_master(empty) == []


def test_a_record_is_hashable_and_comparable(master_file: pathlib.Path) -> None:
    records = read_master(master_file)
    assert isinstance(records[0], AccountRecord)
    assert records[0] == records[0]
