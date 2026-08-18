#!/usr/bin/env python3
"""Run the end-of-day cycle and assert what it produced.

The cycle is four steps and a shell script, so the assertions are about the things an operator and
an auditor care about: that it completes, that running it twice on the same inputs produces the
same bytes, that a failing step stops it dead, and that the report reconciles against the run.

Run: python3 mainframe/jcl/test-eod-cycle.py
"""

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "mainframe" / "data"))

from comp3 import encode_comp3  # noqa: E402

RUNNER = REPO / "mainframe" / "jcl" / "run-eod.sh"
BUS_DATE = "20260818"


def text(value, width):
    return value.encode("ascii").ljust(width, b" ")


def number(value, width):
    return str(value).encode("ascii").rjust(width, b"0")


def acctrec(ref, acct_type="LIABILITY", currency="PLN", status="OPEN", booked=0):
    record = (text(ref, 16) + text("CU0000000001", 12) + text(acct_type, 9) + text(currency, 3)
              + text(status, 7) + encode_comp3(booked) + encode_comp3(booked)
              + number(20200101, 8) + number(20260101, 8) + b" " * 21)
    assert len(record) == 100
    return record


def moverec(ref, direction, amount, currency="PLN", transfer="TB202608180000000001", leg=1):
    record = (text(transfer, 20) + number(leg, 2) + text(ref, 16) + text(direction, 1)
              + text(currency, 3) + encode_comp3(amount) + number(20260818, 8)
              + number(20260818090000, 14) + text("CYCLE FIXTURE", 35) + b" " * 13)
    assert len(record) == 120
    return record


def fixture(work, master=None, movements=None):
    """Writes a master and a movement file, returns their paths."""
    master = [acctrec("TB00000000000001", booked=100_00),
              acctrec("TB00000000000002", currency="EUR", booked=250_00),
              acctrec("TB00000000000003", status="CLOSED", booked=0)] if master is None else master
    movements = [moverec("TB00000000000001", "C", 30_00),
                 moverec("TB00000000000002", "D", 50_00, currency="EUR"),
                 moverec("TB00000000000003", "C", 10_00),
                 moverec("TB00000000000009", "C", 10_00)] if movements is None else movements

    master_path = work / "ACCTMAST.DAT"
    movement_path = work / "MOVEMENT.DAT"
    master_path.write_bytes(b"".join(master))
    movement_path.write_bytes(b"".join(movements))
    return master_path, movement_path


def run_cycle(work, master, movements, *extra):
    """Runs the cycle. Returns (return code, combined output, work directory for the run)."""
    out = work / "eod"
    result = subprocess.run(
        ["bash", str(RUNNER), "--business-date", BUS_DATE, "--master", str(master),
         "--movements", str(movements), "--work", str(out), *extra],
        capture_output=True, text=True, env=dict(os.environ),
    )
    return result.returncode, result.stdout + result.stderr, out / BUS_DATE


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


def booked_of(master_bytes, index):
    from comp3 import decode_comp3
    return decode_comp3(master_bytes[index * 100 + 47 : index * 100 + 55])


# ------------------------------------------------------------------------------------------
# Scenarios
# ------------------------------------------------------------------------------------------

def scenario_full_cycle_completes_from_a_clean_state(binary_work):
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)
    rc, output, run_dir = run_cycle(work, master, movements)

    check(rc == 0, f"return code {rc}, expected 0\\n{output}")
    for name in ["MOVEMENT.DAT", "ACCTNEW.DAT", "REJECTS.DAT", "ACCTRPT.DAT", "EODREPT.TXT"]:
        check((run_dir / name).exists(), f"{name} was not produced\\n{output}")

    new_master = (run_dir / "ACCTNEW.DAT").read_bytes()
    # 100.00 credited 30.00 = 130.00. Written out, not recomputed.
    check(booked_of(new_master, 0) == 130_00,
          f"account 1 booked is {booked_of(new_master, 0)}, expected 13000")
    shutil.rmtree(work)


def scenario_the_four_steps_run_in_order(binary_work):
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)
    _, output, _ = run_cycle(work, master, movements)

    positions = [output.find(step) for step in ["STEP010", "STEP020", "STEP030", "STEP040"]]
    check(all(position >= 0 for position in positions), f"a step never ran:\\n{output}")
    check(positions == sorted(positions), f"the steps ran out of order:\\n{output}")
    shutil.rmtree(work)


def scenario_two_runs_produce_identical_outputs(binary_work):
    """Idempotence. The run timestamp comes from the business date, never the wall clock."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)

    rc_one, output_one, run_dir = run_cycle(work, master, movements)
    first = {name: (run_dir / name).read_bytes()
             for name in ["ACCTNEW.DAT", "REJECTS.DAT", "EODREPT.TXT"]}
    rc_two, output_two, _ = run_cycle(work, master, movements)

    check(rc_one == 0 and rc_two == 0, f"a run failed: {rc_one}, {rc_two}\\n{output_two}")
    for name, before in first.items():
        after = (run_dir / name).read_bytes()
        check(before == after, f"{name} differs between two runs of the same inputs")
    shutil.rmtree(work)


def scenario_unsorted_movements_are_sorted_before_posting(binary_work):
    """STEP010 exists to guarantee the sequence, not to trust the feed."""
    work = pathlib.Path(tempfile.mkdtemp())
    ordered = [moverec("TB00000000000001", "C", 10_00),
               moverec("TB00000000000002", "C", 20_00, currency="EUR"),
               moverec("TB00000000000001", "C", 5_00)]
    master, movements = fixture(work, movements=list(reversed(ordered)))
    rc, output, run_dir = run_cycle(work, master, movements)

    check(rc == 0, f"return code {rc}\\n{output}")
    new_master = (run_dir / "ACCTNEW.DAT").read_bytes()
    # 100.00 + 10.00 + 5.00 = 115.00, whatever order the movements arrived in.
    check(booked_of(new_master, 0) == 115_00,
          f"account 1 booked is {booked_of(new_master, 0)}, expected 11500 - was it sorted?")
    shutil.rmtree(work)


def scenario_a_failing_step_aborts_the_cycle(binary_work):
    """No step runs after a failed one, and the cycle exits non-zero. This is what COND does."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)
    movements.write_bytes(movements.read_bytes()[:-7])          # a partial trailing record

    rc, output, run_dir = run_cycle(work, master, movements)

    check(rc != 0, f"a ragged movement file returned {rc}, expected non-zero\\n{output}")
    check("STEP010" in output and "ABEND" in output, f"the abend names no step:\\n{output}")
    check(not (run_dir / "EODREPT.TXT").exists(),
          "the cycle carried on and produced a report after a failed step")
    shutil.rmtree(work)


def scenario_a_missing_input_is_refused_before_any_step(binary_work):
    work = pathlib.Path(tempfile.mkdtemp())
    _, movements = fixture(work)

    rc, output, _ = run_cycle(work, work / "ABSENT.DAT", movements)

    check(rc != 0, f"a missing master returned {rc}, expected non-zero")
    check("ABSENT.DAT" in output, f"the message does not name the missing file:\\n{output}")
    shutil.rmtree(work)


def scenario_the_report_reconciles_against_acctpost(binary_work):
    """One reject in the fixture: the movement for an account the master does not hold."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)
    rc, output, run_dir = run_cycle(work, master, movements)

    report = (run_dir / "EODREPT.TXT").read_text()
    check("*** IN BALANCE" in report,
          f"the report did not reconcile against ACCTPOST\\n{output}")
    total = [line for line in report.splitlines() if "TOTAL REJECTED" in line][0]
    check(total.strip().endswith("2"), f"reject total is {total!r}, expected 2")
    check(rc == 0, f"return code {rc}")
    shutil.rmtree(work)


SCENARIOS = [value for name, value in sorted(globals().items()) if name.startswith("scenario_")]


def main() -> int:
    check(RUNNER.exists(), f"{RUNNER} does not exist")

    failures = 0
    for scenario in SCENARIOS:
        name = scenario.__name__.replace("scenario_", "").replace("_", " ")
        try:
            scenario(None)
            print(f"  PASS  {name}")
        except Failure as error:
            failures += 1
            print(f"  FAIL  {name}\n        {error}")
        except Exception as error:  # noqa: BLE001 - one broken scenario must not hide the rest
            failures += 1
            print(f"  FAIL  {name}\n        unexpected {type(error).__name__}: {error}")

    print()
    if failures:
        print(f"{failures} of {len(SCENARIOS)} scenarios failed")
        return 1
    print(f"OK    {len(SCENARIOS)} scenarios passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
