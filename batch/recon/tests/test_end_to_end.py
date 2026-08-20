"""The whole spine: a ledger, an overnight cycle, and a reconciliation over both.

This is the test that decides whether the package is worth having. Everything else checks a part;
this checks that the parts describe the same bank. It seeds the PostgreSQL ledger, writes the
account master and the movement file stratum 0 expects, runs the **real** GnuCOBOL overnight cycle
through ``run-eod.sh``, and reconciles the master that cycle produced against the ledger.

Then it injects the three faults the work package names and asserts each is classified correctly.
The third - a movement posted after the cut-off - is the one that matters: if it can be made to come
out as drift, the classification is worthless and the report is one operators learn to ignore.

The fixtures are built with **stratum 0's own encoder**, ``mainframe/data/comp3.py``. That is the
authority for what a master record looks like, and building the input with it means a failure here
is a failure of the reconciliation rather than of the test's idea of the format. The decoder under
test is a different implementation, which is the whole point.
"""

from __future__ import annotations

import datetime as dt
import importlib.util
import pathlib
import subprocess

import psycopg
import pytest

from recon.compare import Classification
from recon.run import RunRequest, execute

BUSINESS_DATE = dt.date(2026, 8, 18)
CCYYMMDD = "20260818"

CASH = "TB00000000000001"
ALICE = "TB00000000000002"
BOB = "TB00000000000003"

OPENING = 1_000_00


def _comp3():
    """`mainframe/data/comp3.py`, loaded by path - stratum 0 is not an installable package."""
    repo = pathlib.Path(__file__).resolve().parents[3]
    spec = importlib.util.spec_from_file_location(
        "mf_comp3", repo / "mainframe" / "data" / "comp3.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def acctrec(ref: str, account_type: str, booked: int) -> bytes:
    """One 100-byte ACCTREC, field order from the copybook."""
    encode = _comp3().encode_comp3
    record = (
        ref.ljust(16).encode("ascii")
        + b"CU0000000001"
        + account_type.ljust(9).encode("ascii")
        + b"PLN"
        + b"OPEN   "
        + encode(booked)
        + encode(booked)
        + b"20260101"
        + CCYYMMDD.encode("ascii")
        + b" " * 21
    )
    if len(record) != 100:  # pragma: no cover - a guard on the fixture, not on the code
        raise AssertionError(f"ACCTREC is {len(record)} bytes, not 100")
    return record


def movrec(transfer_ref: str, leg: int, account_ref: str, direction: str, amount: int) -> bytes:
    """One 120-byte MOVEREC, field order from the copybook."""
    encode = _comp3().encode_comp3
    record = (
        transfer_ref.ljust(20).encode("ascii")
        + f"{leg:02d}".encode("ascii")
        + account_ref.ljust(16).encode("ascii")
        + direction.encode("ascii")
        + b"PLN"
        + encode(amount)
        + CCYYMMDD.encode("ascii")
        + (CCYYMMDD + "030000").encode("ascii")
        + b" " * 35
        + b" " * 13
    )
    if len(record) != 120:  # pragma: no cover - a guard on the fixture
        raise AssertionError(f"MOVEREC is {len(record)} bytes, not 120")
    return record


def run_the_cycle(
    repo: pathlib.Path, work: pathlib.Path, master: pathlib.Path, movements: pathlib.Path
) -> pathlib.Path:
    """The real WP-05 overnight cycle. Returns the new master it produced."""
    # S603/S607: fixed argv and no shell. `bash` is resolved from PATH deliberately - hard-coding
    # /bin/bash would break on the first machine where it lives somewhere else.
    finished = subprocess.run(  # noqa: S603
        [  # noqa: S607
            "bash",
            "mainframe/jcl/run-eod.sh",
            "--business-date",
            CCYYMMDD,
            "--master",
            str(master),
            "--movements",
            str(movements),
            "--work",
            str(work),
            "--rerun",
        ],
        cwd=repo,
        capture_output=True,
        text=True,
    )
    if finished.returncode != 0:
        raise AssertionError(f"the overnight cycle abended:\n{finished.stdout}\n{finished.stderr}")
    produced = work / CCYYMMDD / "ACCTNEW.DAT"
    if not produced.is_file():
        raise AssertionError(f"the cycle produced no new master:\n{finished.stdout}")
    rejects = work / CCYYMMDD / "REJECTS.DAT"
    if rejects.exists() and rejects.stat().st_size:
        raise AssertionError(f"the cycle rejected movements it should have applied: {rejects}")
    return produced


@pytest.fixture
def spine(dsn, ledger, repo, tmp_path):
    """Both cores holding the same two customers, and a transfer that has crossed to stratum 0."""
    ledger.open_account(CASH, account_type="ASSET")
    ledger.open_account(ALICE, account_type="LIABILITY")
    ledger.open_account(BOB, account_type="LIABILITY")

    # The opening position, before anything moves. Alice and Bob are liabilities of the bank, so a
    # credit is what puts money in them.
    for account in (ALICE, BOB):
        ledger.post_transfer(
            f"TB20260817{account[-10:]}",
            debit=CASH,
            credit=account,
            amount_minor=OPENING,
            value_date="2026-08-17",
        )

    master = tmp_path / "ACCTMAST.DAT"
    # CASH is an ASSET and it is the debited side of both openings, so it rises. Getting this
    # backwards is the mistake the first run of this test actually made, and the reconciliation
    # caught it - which is a fair demonstration that the sign convention is load-bearing.
    master.write_bytes(
        acctrec(CASH, "ASSET", 2 * OPENING)
        + acctrec(ALICE, "LIABILITY", OPENING)
        + acctrec(BOB, "LIABILITY", OPENING)
    )
    return {
        "master": master,
        "work": tmp_path / "work",
        "repo": repo,
        "dsn": dsn,
        "ledger": ledger,
        "tmp": tmp_path,
    }


def reconcile(spine, movements: pathlib.Path, master: pathlib.Path):
    return execute(
        spine["dsn"],
        RunRequest(
            business_date=BUSINESS_DATE,
            master_path=master,
            movement_path=movements,
            output_dir=spine["tmp"] / "out",
        ),
    )


def test_a_clean_night_reconciles_to_zero_breaks(spine) -> None:
    """One transfer, applied by the real cycle, agreed by both cores."""
    ref = "TB202608180000000001"
    spine["ledger"].post_transfer(
        ref, debit=ALICE, credit=BOB, amount_minor=250_00, value_date="2026-08-18"
    )
    movements = spine["tmp"] / "MOVEMENT.DAT"
    movements.write_bytes(movrec(ref, 1, ALICE, "D", 250_00) + movrec(ref, 2, BOB, "C", 250_00))

    produced = run_the_cycle(spine["repo"], spine["work"], spine["master"], movements)
    result = reconcile(spine, movements, produced)

    assert result.accounts_broken == 0, result.breaks_by_classification
    assert result.accounts_compared == 3
    assert result.accounts_matched == 3
    assert result.report_path.is_file(), "a clean run must still produce a report"


def test_a_value_discrepancy_is_detected_as_drift(spine) -> None:
    """The ledger says 250.00 moved; the movement file tells the mainframe 240.00."""
    ref = "TB202608180000000001"
    spine["ledger"].post_transfer(
        ref, debit=ALICE, credit=BOB, amount_minor=250_00, value_date="2026-08-18"
    )
    movements = spine["tmp"] / "MOVEMENT.DAT"
    movements.write_bytes(movrec(ref, 1, ALICE, "D", 240_00) + movrec(ref, 2, BOB, "C", 240_00))

    produced = run_the_cycle(spine["repo"], spine["work"], spine["master"], movements)
    result = reconcile(spine, movements, produced)

    assert result.breaks_by_classification[Classification.VALUE_DRIFT] == 2
    assert result.total_absolute_drift_minor == 2 * 10_00
    assert result.needs_an_operator


def test_an_account_missing_from_the_master_is_detected(spine) -> None:
    """An account the ledger opened and the master has never heard of."""
    spine["ledger"].open_account("TB00000000000009", account_type="LIABILITY")
    movements = spine["tmp"] / "MOVEMENT.DAT"
    movements.write_bytes(b"")

    produced = run_the_cycle(spine["repo"], spine["work"], spine["master"], movements)
    result = reconcile(spine, movements, produced)

    assert result.breaks_by_classification[Classification.MISSING_ON_MASTER] == 1
    assert result.needs_an_operator


def test_a_post_cut_off_movement_is_timing_not_drift(spine) -> None:
    """The case the package exists for.

    Two transfers are posted to the ledger for today. Only the first reaches the movement file the
    cycle consumed, so the master is short by the second - and that is expected, not drift. If this
    ever comes out as VALUE_DRIFT the report becomes one operators are trained to ignore, which is
    the failure the work package's Constraints section names explicitly.
    """
    applied = "TB202608180000000001"
    late = "TB202608180000000002"
    spine["ledger"].post_transfer(
        applied, debit=ALICE, credit=BOB, amount_minor=250_00, value_date="2026-08-18"
    )
    spine["ledger"].post_transfer(
        late, debit=ALICE, credit=BOB, amount_minor=75_00, value_date="2026-08-18"
    )

    movements = spine["tmp"] / "MOVEMENT.DAT"
    movements.write_bytes(
        movrec(applied, 1, ALICE, "D", 250_00) + movrec(applied, 2, BOB, "C", 250_00)
    )

    produced = run_the_cycle(spine["repo"], spine["work"], spine["master"], movements)
    result = reconcile(spine, movements, produced)

    assert result.breaks_by_classification[Classification.TIMING] == 2
    assert result.breaks_by_classification[Classification.VALUE_DRIFT] == 0
    assert not result.needs_an_operator, "timing is expected and must not page anybody"


def test_the_same_cut_reconciles_identically_twice(spine) -> None:
    """Deterministic and reproducible from the same two inputs, asserted on the report's bytes."""
    ref = "TB202608180000000001"
    spine["ledger"].post_transfer(
        ref, debit=ALICE, credit=BOB, amount_minor=250_00, value_date="2026-08-18"
    )
    movements = spine["tmp"] / "MOVEMENT.DAT"
    movements.write_bytes(movrec(ref, 1, ALICE, "D", 250_00) + movrec(ref, 2, BOB, "C", 250_00))
    produced = run_the_cycle(spine["repo"], spine["work"], spine["master"], movements)

    first = reconcile(spine, movements, produced)
    first_bytes = first.report_path.read_bytes()

    # A posting after the first run's position must not change what the first cut sees.
    spine["ledger"].post_transfer(
        "TB202608180000000009", debit=ALICE, credit=BOB, amount_minor=1, value_date="2026-08-18"
    )
    replayed = execute(
        spine["dsn"],
        RunRequest(
            business_date=BUSINESS_DATE,
            master_path=produced,
            movement_path=movements,
            output_dir=spine["tmp"] / "again",
            position_seq=first.position_seq,
        ),
    )
    assert replayed.report_path.read_bytes() == first_bytes


def test_the_reconciliation_wrote_nothing_to_either_core(spine) -> None:
    """Read-only against both systems, checked on the systems rather than on the intention."""
    ref = "TB202608180000000001"
    spine["ledger"].post_transfer(
        ref, debit=ALICE, credit=BOB, amount_minor=250_00, value_date="2026-08-18"
    )
    movements = spine["tmp"] / "MOVEMENT.DAT"
    movements.write_bytes(movrec(ref, 1, ALICE, "D", 250_00) + movrec(ref, 2, BOB, "C", 250_00))
    produced = run_the_cycle(spine["repo"], spine["work"], spine["master"], movements)

    before_master = produced.read_bytes()
    with psycopg.connect(spine["dsn"], autocommit=True) as connection:
        before_rows = connection.execute("SELECT count(*) FROM posting").fetchone()[0]

    reconcile(spine, movements, produced)

    with psycopg.connect(spine["dsn"], autocommit=True) as connection:
        after_rows = connection.execute("SELECT count(*) FROM posting").fetchone()[0]
    assert produced.read_bytes() == before_master, "the reconciliation modified the account master"
    assert after_rows == before_rows, "the reconciliation wrote to the ledger"
