#!/usr/bin/env python3
"""Run ACCTPOST against purpose-built fixtures and assert the outputs.

**Expected balances are written as explicit numbers.** This harness never recomputes them by
reimplementing the match-merge in Python. "Starts at 100.00, debited 30.00, expect 70.00" is a test;
"expect whatever my Python version calculates" proves only that two implementations share a bug.

Every one of the six rejection reasons gets a scenario that produces it.

Run: python3 mainframe/cobol/test-acctpost.py
"""

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "mainframe" / "data"))

from comp3 import decode_comp3, encode_comp3  # noqa: E402

COPYBOOK = REPO / "mainframe" / "copybook"
SOURCE = REPO / "mainframe" / "cobol" / "ACCTPOST.CBL"
RUN_TS = "20260817235959"
VALUE_DATE = 20260818


def text(value, width):
    return value.encode("ascii").ljust(width, b" ")


def number(value, width):
    return str(value).encode("ascii").rjust(width, b"0")


def acctrec(ref, acct_type="LIABILITY", currency="PLN", status="OPEN", booked=0, available=None):
    available = booked if available is None else available
    record = (text(ref, 16) + text("CU0000000001", 12) + text(acct_type, 9) + text(currency, 3)
              + text(status, 7) + encode_comp3(booked) + encode_comp3(available)
              + number(20200101, 8) + number(20260101, 8) + b" " * 21)
    assert len(record) == 100
    return record


def moverec(ref, direction, amount, currency="PLN", transfer="TB202608180000000001", leg=1):
    record = (text(transfer, 20) + number(leg, 2) + text(ref, 16) + text(direction, 1)
              + text(currency, 3) + encode_comp3(amount) + number(VALUE_DATE, 8)
              + number(20260818090000, 14) + text("HARNESS FIXTURE", 35) + b" " * 13)
    assert len(record) == 120
    return record


def booked_of(master_bytes, index):
    return decode_comp3(master_bytes[index * 100 + 47 : index * 100 + 55])


def available_of(master_bytes, index):
    return decode_comp3(master_bytes[index * 100 + 55 : index * 100 + 63])


def last_move_of(master_bytes, index):
    return int(master_bytes[index * 100 + 71 : index * 100 + 79])


def rejects_of(reject_bytes):
    return [
        (reject_bytes[i * 200 + 120 : i * 200 + 124].decode(),
         reject_bytes[i * 200 + 124 : i * 200 + 164].decode().strip(),
         reject_bytes[i * 200 : i * 200 + 120])
        for i in range(len(reject_bytes) // 200)
    ]


def compile_program(binary):
    subprocess.run(
        ["cobc", "-x", "-std=ibm", "-Wall", "-I", str(COPYBOOK), "-o", str(binary), str(SOURCE)],
        check=True, capture_output=True,
    )


def run(binary, master, movements):
    """Returns (new master bytes, rejects bytes, control totals dict, return code)."""
    work = pathlib.Path(tempfile.mkdtemp())
    (work / "ACCTMAST.DAT").write_bytes(b"".join(master))
    (work / "MOVEMENT.DAT").write_bytes(b"".join(movements))

    env = dict(os.environ, ACCTPOST_RUN_TS=RUN_TS)
    result = subprocess.run([str(binary)], cwd=work, env=env, capture_output=True, text=True)

    totals = {}
    for line in result.stdout.splitlines():
        if line.startswith("ACCTPOST CTL "):
            parts = line[len("ACCTPOST CTL "):].split()
            totals[parts[0]] = parts[1] if len(parts) > 1 else True

    new_master = (work / "ACCTNEW.DAT").read_bytes()
    rejects = (work / "REJECTS.DAT").read_bytes()
    shutil.rmtree(work)
    return new_master, rejects, totals, result.returncode


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


# ------------------------------------------------------------------------------------------
# Scenarios. Each returns nothing and raises Failure on a mismatch.
# ------------------------------------------------------------------------------------------

def scenario_empty_movements_is_lossless(binary):
    """The strongest single guarantee: a pass that applies nothing changes nothing."""
    master = [acctrec("TB00000000000001", booked=100_00),
              acctrec("TB00000000000002", booked=-1),
              acctrec("TB00000000000003", booked=999999999999999)]
    new_master, rejects, totals, rc = run(binary, master, [])

    check(new_master == b"".join(master), "the new master differs from the old one")
    check(rejects == b"", "an empty movement file produced rejects")
    check(totals.get("BALANCED") is True, "control totals did not balance")
    check(rc == 0, f"return code {rc}, expected 0")


def scenario_credit_and_debit(binary):
    """A liability account: credited it grows, debited it shrinks."""
    master = [acctrec("TB00000000000001", booked=100_00)]
    movements = [moverec("TB00000000000001", "C", 30_00),
                 moverec("TB00000000000001", "D", 5_00)]
    new_master, rejects, totals, rc = run(binary, master, movements)

    # 100.00 + 30.00 - 5.00 = 125.00. Written out, not calculated.
    check(booked_of(new_master, 0) == 125_00, f"booked is {booked_of(new_master, 0)}, expected 12500")
    check(available_of(new_master, 0) == 125_00, "available did not follow booked")
    check(last_move_of(new_master, 0) == VALUE_DATE, "last movement date was not updated")
    check(totals["MOVE-APPLIED"] == "2", f"applied {totals['MOVE-APPLIED']}, expected 2")
    check(rejects == b"", "a valid movement was rejected")


def scenario_asset_signs_are_opposite(binary):
    """Cash is an ASSET: a debit increases it. Getting this backwards is the classic error."""
    master = [acctrec("TB00000000000001", acct_type="ASSET", booked=100_00)]
    movements = [moverec("TB00000000000001", "D", 40_00)]
    new_master, _, _, _ = run(binary, master, movements)

    check(booked_of(new_master, 0) == 140_00,
          f"debiting an ASSET gave {booked_of(new_master, 0)}, expected 14000")


def scenario_zero_stays_positive(binary):
    """A balance that nets to zero must carry sign nibble 0x0C, never 0x0D."""
    master = [acctrec("TB00000000000001", booked=50_00)]
    movements = [moverec("TB00000000000001", "D", 50_00)]
    new_master, _, _, _ = run(binary, master, movements)

    check(booked_of(new_master, 0) == 0, "balance did not reach zero")
    check(new_master[54] & 0x0F == 0x0C,
          f"zero carries sign nibble 0x{new_master[54] & 0x0F:X}, expected 0x0C")


def scenario_many_movements_one_write(binary):
    """Several movements for one account produce exactly one master record."""
    master = [acctrec("TB00000000000001", booked=0)]
    movements = [moverec("TB00000000000001", "C", 10_00) for _ in range(5)]
    new_master, _, totals, _ = run(binary, master, movements)

    check(len(new_master) == 100, f"{len(new_master) // 100} master records written, expected 1")
    check(booked_of(new_master, 0) == 50_00, f"booked is {booked_of(new_master, 0)}, expected 5000")
    check(totals["MASTER-WRITTEN"] == "1", "master written count is wrong")


def scenario_r001_unknown_account(binary):
    master = [acctrec("TB00000000000001", booked=100_00)]
    movements = [moverec("TB00000000000009", "C", 10_00)]
    new_master, rejects, totals, _ = run(binary, master, movements)

    reasons = rejects_of(rejects)
    check(len(reasons) == 1, f"{len(reasons)} rejects, expected 1")
    check(reasons[0][0] == "R001", f"reason {reasons[0][0]}, expected R001")
    check(reasons[0][2] == movements[0], "the movement was not carried verbatim into REJ-MOVEMENT")
    check(new_master == b"".join(master), "an unknown account altered the master")
    check(len(new_master) == 100, "a movement created an account")


def scenario_r002_account_not_open(binary):
    master = [acctrec("TB00000000000001", status="BLOCKED", booked=100_00)]
    movements = [moverec("TB00000000000001", "C", 10_00)]
    new_master, rejects, _, _ = run(binary, master, movements)

    check(rejects_of(rejects)[0][0] == "R002", "expected R002")
    check(booked_of(new_master, 0) == 100_00, "a blocked account was still updated")


def scenario_r003_currency_mismatch(binary):
    master = [acctrec("TB00000000000001", currency="EUR", booked=100_00)]
    movements = [moverec("TB00000000000001", "C", 10_00, currency="PLN")]
    _, rejects, _, _ = run(binary, master, movements)

    check(rejects_of(rejects)[0][0] == "R003", "expected R003")


def scenario_r004_unsupported_scale(binary):
    """JPY has scale 0. PIC S9(13)V99 cannot represent it, so it never reaches the master."""
    master = [acctrec("TB00000000000001", currency="JPY", booked=100_00)]
    movements = [moverec("TB00000000000001", "C", 1000, currency="JPY")]
    _, rejects, _, _ = run(binary, master, movements)

    reason = rejects_of(rejects)[0]
    check(reason[0] == "R004", f"reason {reason[0]}, expected R004")
    check(reason[1] == "CURRENCY SCALE NOT SUPPORTED", f"text was {reason[1]!r}")


def scenario_r005_would_overdraw(binary):
    master = [acctrec("TB00000000000001", booked=100_00)]
    movements = [moverec("TB00000000000001", "D", 100_01)]
    new_master, rejects, _, _ = run(binary, master, movements)

    check(rejects_of(rejects)[0][0] == "R005", "expected R005")
    check(booked_of(new_master, 0) == 100_00, "the balance moved despite the rejection")


def scenario_credits_into_an_overdrawn_account_are_allowed(binary):
    """An account can be negative from legacy state. Refusing a repayment would be absurd."""
    master = [acctrec("TB00000000000001", booked=-1_250_00)]
    movements = [moverec("TB00000000000001", "C", 250_00)]
    new_master, rejects, _, _ = run(binary, master, movements)

    check(rejects == b"", "a credit into an overdrawn account was rejected")
    check(booked_of(new_master, 0) == -1_000_00,
          f"booked is {booked_of(new_master, 0)}, expected -100000")


def scenario_r006_amount_not_positive(binary):
    master = [acctrec("TB00000000000001", booked=100_00)]
    movements = [moverec("TB00000000000001", "C", 0)]
    _, rejects, _, _ = run(binary, master, movements)

    check(rejects_of(rejects)[0][0] == "R006", "expected R006")


def scenario_totals_always_balance(binary):
    """read = applied + rejected, on a mixed run."""
    master = [acctrec("TB00000000000001", booked=100_00),
              acctrec("TB00000000000002", status="CLOSED", booked=0)]
    movements = [moverec("TB00000000000001", "C", 1_00),
                 moverec("TB00000000000002", "C", 1_00),
                 moverec("TB00000000000009", "C", 1_00)]
    _, _, totals, rc = run(binary, master, movements)

    read = int(totals["MOVE-READ"].replace(",", ""))
    applied = int(totals["MOVE-APPLIED"].replace(",", ""))
    rejected = int(totals["MOVE-REJECTED"].replace(",", ""))
    check(read == applied + rejected, f"{read} read but {applied} + {rejected}")
    check(totals.get("BALANCED") is True, "the program did not report BALANCED")
    check(rc == 0, f"return code {rc}")


SCENARIOS = [value for name, value in sorted(globals().items()) if name.startswith("scenario_")]


def main() -> int:
    binary = pathlib.Path(tempfile.mkdtemp()) / "acctpost"
    print(f"Compiling {SOURCE.relative_to(REPO)} with -std=ibm\n")
    compile_program(binary)

    failures = 0
    for scenario in SCENARIOS:
        name = scenario.__name__.replace("scenario_", "").replace("_", " ")
        try:
            scenario(binary)
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
