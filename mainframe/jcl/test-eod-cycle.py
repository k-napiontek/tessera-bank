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
JCL = REPO / "mainframe" / "jcl" / "EODCYCLE.JCL"
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

    check(rc == 0, f"return code {rc}, expected 0\n{output}")
    for name in ["MOVEMENT.DAT", "ACCTNEW.DAT", "REJECTS.DAT", "ACCTRPT.DAT", "EODREPT.TXT"]:
        check((run_dir / name).exists(), f"{name} was not produced\n{output}")

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
    check(all(position >= 0 for position in positions), f"a step never ran:\n{output}")
    check(positions == sorted(positions), f"the steps ran out of order:\n{output}")
    shutil.rmtree(work)


def scenario_two_runs_produce_identical_outputs(binary_work):
    """Idempotence. The run timestamp comes from the business date, never the wall clock."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)

    rc_one, output_one, run_dir = run_cycle(work, master, movements)
    first = {name: (run_dir / name).read_bytes()
             for name in ["ACCTNEW.DAT", "REJECTS.DAT", "EODREPT.TXT"]}
    rc_two, output_two, _ = run_cycle(work, master, movements, "--rerun")

    check(rc_one == 0 and rc_two == 0, f"a run failed: {rc_one}, {rc_two}\n{output_two}")
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

    check(rc == 0, f"return code {rc}\n{output}")
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

    check(rc != 0, f"a ragged movement file returned {rc}, expected non-zero\n{output}")
    check("STEP010" in output and "ABEND" in output, f"the abend names no step:\n{output}")
    check(not (run_dir / "EODREPT.TXT").exists(),
          "the cycle carried on and produced a report after a failed step")
    shutil.rmtree(work)


def scenario_a_missing_input_is_refused_before_any_step(binary_work):
    work = pathlib.Path(tempfile.mkdtemp())
    _, movements = fixture(work)

    rc, output, _ = run_cycle(work, work / "ABSENT.DAT", movements)

    check(rc != 0, f"a missing master returned {rc}, expected non-zero")
    check("ABSENT.DAT" in output, f"the message does not name the missing file:\n{output}")
    shutil.rmtree(work)


def scenario_the_report_reconciles_against_acctpost(binary_work):
    """One reject in the fixture: the movement for an account the master does not hold."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)
    rc, output, run_dir = run_cycle(work, master, movements)

    report = (run_dir / "EODREPT.TXT").read_text()
    check("*** IN BALANCE" in report,
          f"the report did not reconcile against ACCTPOST\n{output}")
    total = [line for line in report.splitlines() if "TOTAL REJECTED" in line][0]
    check(total.strip().endswith("2"), f"reject total is {total!r}, expected 2")
    check(rc == 0, f"return code {rc}")
    shutil.rmtree(work)


def scenario_the_same_movement_file_is_refused_twice(binary_work):
    """Applying a day twice doubles every posting in the bank. It must be refused, not detected."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)

    rc_one, _, run_dir = run_cycle(work, master, movements)
    check(rc_one == 0, f"the first run failed with {rc_one}")
    before = (run_dir / "ACCTNEW.DAT").read_bytes()

    rc_two, output, _ = run_cycle(work, master, movements)

    check(rc_two == 8, f"the second run returned {rc_two}, expected 8")
    check("already applied" in output, f"the refusal explains nothing:\n{output}")
    check((run_dir / "ACCTNEW.DAT").read_bytes() == before,
          "the refused run still changed the master")
    shutil.rmtree(work)


def scenario_rerun_applies_it_again_deliberately(binary_work):
    """The guard is a control, not a wall. An operator who means it can say so."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)

    run_cycle(work, master, movements)
    rc, output, run_dir = run_cycle(work, master, movements, "--rerun")

    check(rc == 0, f"--rerun returned {rc}, expected 0\n{output}")
    check((run_dir / "EODREPT.TXT").exists(), "--rerun produced no report")
    shutil.rmtree(work)


def scenario_a_different_movement_file_is_allowed_the_same_day(binary_work):
    """A corrected file re-presented on the same business date is normal operations."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)
    run_cycle(work, master, movements)

    movements.write_bytes(b"".join([moverec("TB00000000000001", "C", 1_00)]))
    rc, output, _ = run_cycle(work, master, movements)

    check(rc == 0, f"a different movement file returned {rc}, expected 0\n{output}")
    shutil.rmtree(work)


def scenario_the_marker_records_what_was_applied(binary_work):
    """An operator at 03:00 needs to see which file was posted, not just that one was."""
    work = pathlib.Path(tempfile.mkdtemp())
    master, movements = fixture(work)
    _, _, run_dir = run_cycle(work, master, movements)

    marker = (run_dir / "MOVEMENT.APPLIED").read_text()
    expected = subprocess.run(["shasum", "-a", "256", str(movements)],
                              capture_output=True, text=True).stdout.split()[0]
    check(expected in marker, f"the marker holds no checksum of the applied file:\n{marker}")
    check(BUS_DATE in marker, f"the marker does not name the business date:\n{marker}")


def scenario_the_jcl_and_the_runner_describe_the_same_graph(binary_work):
    """Two files that must agree, and are only asked to agree by a sentence, will diverge.

    The JCL never executes here, so nothing else can catch it drifting from the script that does.
    """
    import re

    declared = re.findall(r"^//(STEP\d+)\s+EXEC\s+PGM=([A-Z0-9]+)", JCL.read_text(), re.MULTILINE)
    result = subprocess.run(["bash", str(RUNNER), "--steps"], capture_output=True, text=True)
    executed = [tuple(line.split()) for line in result.stdout.split("\n") if line.strip()]

    check(declared, "no EXEC PGM= steps were found in EODCYCLE.JCL")
    check(declared == executed,
          f"the JCL and the runner disagree\n  JCL    {declared}\n  runner {executed}")


def scenario_the_jcl_declares_a_dd_for_every_file_the_cycle_touches(binary_work):
    """A job graph with no DD statements documents nothing an operator can act on.

    Datasets are checked by DD name, not by the local filename. On z/OS the program knows the DD
    name and the JCL binds it to a catalogued DSN; the two naming worlds are supposed to differ,
    and asserting that TESSERA.ACCT.MASTER is spelled ACCTMAST.DAT would be asserting the
    opposite of how the tier works.
    """
    import re

    jcl = JCL.read_text()
    declared = set(re.findall(r"^//(\S+)\s+DD\s", jcl, re.MULTILINE))

    for dd_name in ["SORTIN", "SORTOUT", "ACCTMAST", "MOVEMENT", "ACCTNEW",
                    "REJECTS", "ACCTRPT", "EODREPT", "STEPLIB", "SYSOUT"]:
        check(dd_name in declared, f"the JCL declares no {dd_name} DD statement")

    # The record lengths are the copybooks', and a DCB that disagrees with them is a production
    # abend at 03:00 that no test here would otherwise catch.
    check("LRECL=100" in jcl, "no 100-byte ACCTREC dataset is declared")
    check("LRECL=120" in jcl, "no 120-byte MOVEREC dataset is declared")
    check("LRECL=200" in jcl, "no 200-byte REJREC dataset is declared")
    check("RECFM=FB" in jcl, "the JCL declares no fixed-block record format")


def scenario_the_jcl_sorts_on_the_same_fields_as_the_runner(binary_work):
    """DFSORT counts columns from 1; sortrec.py counts bytes from 0. An off-by-one here sorts on
    the wrong field and every downstream figure is quietly wrong."""
    import re

    jcl = JCL.read_text()
    check("SORT FIELDS=(23,16,CH,A)" in jcl,
          "STEP010 does not sort on MOV-ACCT-REF at column 23 for 16 - the runner uses bytes 22:38")
    check("SORT FIELDS=(38,3,CH,A,1,16,CH,A)" in jcl,
          "STEP030 does not sort on currency then reference - the runner uses 37:40 then 0:16")

    runner = (REPO / "mainframe" / "jcl" / "run-eod.sh").read_text()
    check("--key 22:38" in runner, "the runner no longer sorts movements on 22:38")
    check("--key 37:40 --key 0:16" in runner, "the runner no longer sorts the master on 37:40, 0:16")


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
