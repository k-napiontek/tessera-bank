"""The break report, validated against the contract rather than against its own idea of the format.

The tables in ``contracts/recon/break-report-v1.md`` are parsed here at test time and the produced
document is held to them: every field the contract names is present, every field present is named by
the contract, and every type is what the contract says. A writer checked against a transcription of
the format agrees with the transcription.

``contracts/check-break-report.py`` makes the other half of the claim - that the document is a
coherent format. Two different statements, deliberately kept apart.
"""

from __future__ import annotations

import datetime as dt
import json
import pathlib
import re

import pytest

from recon.compare import Break, Classification, ComparisonResult, Totals, compare
from recon.cutoff import CutOff
from recon.ledger import GENESIS_HASH, LedgerAccount, Position
from recon.master import AccountRecord
from recon.report import ReportError, build_report, write_report

BUSINESS_DATE = dt.date(2026, 8, 18)
POSITION = Position(seq=4711, chain_hash="a" * 64)
CUT_OFF = CutOff(movement_file="MOVEMENT.DAT", transfer_refs=frozenset({"TB202608180000000001"}))

SECTION = re.compile(r"^##\s+(.+?)\s*$")
FIELD_ROW = re.compile(r"^\|\s*`([A-Za-z0-9_]+)`\s*\|\s*`([A-Za-z0-9?]+)`\s*\|")
TABLES = {
    "Top level": "top",
    "`cutOff`": "cutOff",
    "`masterFile`": "masterFile",
    "A break": "break",
    "`totals`": "totals",
}


@pytest.fixture(scope="module")
def contract(repo: pathlib.Path) -> dict[str, dict[str, str]]:
    """The contract's field tables: table -> {field: type}."""
    text = (repo / "contracts" / "recon" / "break-report-v1.md").read_text(encoding="utf-8")
    tables: dict[str, dict[str, str]] = {}
    section = None
    for line in text.splitlines():
        heading = SECTION.match(line)
        if heading:
            section = heading.group(1)
            continue
        if section in TABLES:
            row = FIELD_ROW.match(line)
            if row:
                tables.setdefault(TABLES[section], {})[row.group(1)] = row.group(2)
    return tables


def master(ref: str, booked: int) -> AccountRecord:
    return AccountRecord(
        account_ref=ref,
        customer_ref="CU0000000001",
        account_type="LIABILITY",
        currency="PLN",
        status="OPEN",
        booked_minor=booked,
        available_minor=booked,
        opened_date="20260101",
        last_move_date="20260818",
    )


def led(ref: str, booked: int, expected: int | None = None) -> LedgerAccount:
    return LedgerAccount(
        account_ref=ref,
        account_type="LIABILITY",
        currency="PLN",
        status="OPEN",
        booked_minor=booked,
        expected_minor=booked if expected is None else expected,
    )


def report_of(masters: list[AccountRecord], ledgers: list[LedgerAccount]) -> dict:
    return build_report(
        business_date=BUSINESS_DATE,
        position=POSITION,
        cut_off=CUT_OFF,
        master_name="ACCTNEW.DAT",
        master_record_count=len(masters),
        result=compare(masters, ledgers),
    )


def check_types(document: dict, table: dict[str, str], contract: dict) -> None:
    """Every field present, no field extra, every value of the declared type."""
    assert set(document) == set(table), f"document fields {sorted(document)} vs {sorted(table)}"
    for field, kind in table.items():
        value = document[field]
        if kind == "integer":
            assert isinstance(value, int) and not isinstance(value, bool), f"{field}={value!r}"
        elif kind == "integer?":
            assert value is None or (isinstance(value, int) and not isinstance(value, bool))
        elif kind == "string":
            assert isinstance(value, str)
        elif kind == "date8":
            assert isinstance(value, str) and re.fullmatch(r"\d{8}", value), f"{field}={value!r}"
        elif kind == "hex64":
            assert isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)
        elif kind == "object":
            assert isinstance(value, dict)
        elif kind == "array":
            assert isinstance(value, list)
        else:  # pragma: no cover - a type the contract added and this test has not learned
            raise AssertionError(f"unknown contract type {kind!r} for {field}")


def test_the_document_matches_the_contract_at_every_level(contract) -> None:
    document = report_of([master("TB00000000000001", -10_000)], [led("TB00000000000001", -9_000)])
    check_types(document, contract["top"], contract)
    check_types(document["cutOff"], contract["cutOff"], contract)
    check_types(document["masterFile"], contract["masterFile"], contract)
    check_types(document["totals"], contract["totals"], contract)
    for found in document["breaks"]:
        check_types(found, contract["break"], contract)


def test_a_run_that_finds_nothing_still_produces_a_report(contract) -> None:
    """Absence of output is indistinguishable from a job that never ran."""
    document = report_of([master("TB00000000000001", -10_000)], [led("TB00000000000001", -10_000)])
    assert document["breaks"] == []
    assert document["totals"]["accountsBroken"] == 0
    assert document["totals"]["totalAbsoluteDriftMinor"] == 0
    check_types(document, contract["top"], contract)


def test_the_format_id_is_the_contracts(contract) -> None:
    assert report_of([], [])["formatId"] == "TB-RECON-BREAKS-V1"


def test_the_business_date_is_ccyymmdd() -> None:
    """One estate, one way of writing a business date, even across thirty years of technology."""
    assert report_of([], [])["businessDate"] == "20260818"


def test_the_position_and_chain_hash_are_carried() -> None:
    document = report_of([], [])
    assert document["ledgerPosition"] == 4711
    assert document["ledgerChainHash"] == "a" * 64


def test_no_wall_clock_appears_anywhere_in_the_body() -> None:
    """A timestamp in the body makes byte-identical reruns impossible - ADR 0009."""
    rendered = json.dumps(report_of([master("TB00000000000001", 1)], []))
    assert not re.search(r"\d{4}-\d{2}-\d{2}T\d{2}:", rendered)
    assert "generatedAt" not in rendered


def test_the_cut_off_records_how_many_transfers_it_admitted() -> None:
    """So a reader can tell a report cut against an empty movement file from a real night's work."""
    document = report_of([], [])
    assert document["cutOff"]["movementFile"] == "MOVEMENT.DAT"
    assert document["cutOff"]["transferRefCount"] == 1


def test_a_missing_side_carries_null_rather_than_the_other_sides_figure() -> None:
    document = report_of([master("TB00000000000001", 700)], [])
    (found,) = document["breaks"]
    assert found["classification"] == "MISSING_IN_LEDGER"
    assert found["ledgerBookedMinor"] is None
    assert found["differenceMinor"] is None


def test_breaks_are_ascending_by_account_reference() -> None:
    masters = [master(f"TB0000000000000{n}", n) for n in (1, 2, 3)]
    document = report_of(masters, [])
    refs = [found["accountRef"] for found in document["breaks"]]
    assert refs == sorted(refs)


def test_totals_that_do_not_balance_are_refused() -> None:
    """A control total that is not checked against anything is decoration."""
    broken = ComparisonResult(
        breaks=[
            Break(
                account_ref="TB00000000000001",
                classification=Classification.VALUE_DRIFT,
                currency="PLN",
                master_booked_minor=1,
                ledger_booked_minor=2,
            )
        ],
        totals=Totals(
            accounts_compared=5,
            accounts_matched=1,
            accounts_broken=1,
            total_absolute_drift_minor=1,
        ),
    )
    with pytest.raises(ReportError, match="do not balance"):
        build_report(
            business_date=BUSINESS_DATE,
            position=POSITION,
            cut_off=CUT_OFF,
            master_name="ACCTNEW.DAT",
            master_record_count=1,
            result=broken,
        )


def test_the_written_file_is_the_document(tmp_path: pathlib.Path) -> None:
    document = report_of([master("TB00000000000001", -10_000)], [led("TB00000000000001", -9_000)])
    path = write_report(document, tmp_path)
    assert json.loads(path.read_text(encoding="utf-8")) == document
    assert path.name == "BREAKS-20260818.json"


def test_two_runs_of_the_same_cut_write_identical_bytes(tmp_path: pathlib.Path) -> None:
    """Reproducible from the same two inputs is a Constraint, so it is asserted on the bytes."""
    document = report_of([master("TB00000000000001", -10_000)], [led("TB00000000000001", -9_000)])
    first = write_report(document, tmp_path / "one")
    second = write_report(document, tmp_path / "two")
    assert first.read_bytes() == second.read_bytes()


def test_a_genesis_ledger_still_reports() -> None:
    """A ledger nothing has happened in is a valid cut, not an error."""
    document = build_report(
        business_date=BUSINESS_DATE,
        position=Position(seq=0, chain_hash=GENESIS_HASH),
        cut_off=CutOff(movement_file="MOVEMENT.DAT", transfer_refs=frozenset()),
        master_name="ACCTNEW.DAT",
        master_record_count=0,
        result=compare([], []),
    )
    assert document["ledgerPosition"] == 0
    assert document["totals"]["accountsCompared"] == 0
