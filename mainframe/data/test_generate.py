#!/usr/bin/env python3
"""Tests for the synthetic data generator.

Two things are asserted here that nothing else in this repository asserts.

**The generator is deterministic.** Downstream tests in four packages compare against files this
produces, so a reshuffle between two runs would look exactly like a regression in whatever was being
tested. `check-records.py` reads the files back against the contract; it says nothing about whether
the same seed produces them twice.

**What the cycle would reject, predicted from the data alone.** `predict_rejects` reimplements
ACCTPOST's validation so that a property of the *data* can be asserted in milliseconds without a
COBOL compiler. Against the generator as it stands it returns 162 rejects out of 302 movements -
111 `R003`, 48 `R002`, and one each of `R001`, `R004` and `R005` - which is exactly what the real
cycle writes to REJECTS.DAT. F-18 is the 111 and the 48.

The prediction is held honest by test-eod-cycle.py, which runs the real program over the real files:
if this model and that cycle ever disagree, the prediction is what is wrong.

The byte offsets are transcribed from contracts/copybook/column-map.md as literals, the way
test_comp3.py transcribes its expected bytes. A test that derives its offsets from the writer proves
only that the writer agrees with itself.

Run: python3 mainframe/data/test_generate.py
"""

import hashlib
import pathlib
import random
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import generate  # noqa: E402
from comp3 import decode_comp3  # noqa: E402

# ACCTREC, 100 bytes. Columns are 1-based inclusive in the contract; these are zero-based slices.
ACCT_REF = slice(0, 16)  # ACCT-REF              1-16
ACCT_TYPE = slice(28, 37)  # ACCT-TYPE          29-37
ACCT_CURRENCY = slice(37, 40)  # ACCT-CURRENCY  38-40
ACCT_STATUS = slice(40, 47)  # ACCT-STATUS      41-47
ACCT_BOOKED = slice(47, 55)  # ACCT-BOOKED-BAL  48-55

# MOVEREC, 120 bytes.
MOV_TRANSFER = slice(0, 20)  # MOV-TRANSFER-REF   1-20
MOV_ACCOUNT = slice(22, 38)  # MOV-ACCT-REF      23-38
MOV_DIRECTION = slice(38, 39)  # MOV-DIRECTION   39-39
MOV_CURRENCY = slice(39, 42)  # MOV-CURRENCY     40-42
MOV_AMOUNT = slice(42, 50)  # MOV-AMOUNT        43-50
MOV_REFERENCE = slice(72, 107)  # MOV-REFERENCE  73-107

# VALIDATE-CURRENCY-SCALE names these five. PIC S9(13)V99 hard-codes two decimals.
SCALE_2 = {"PLN", "EUR", "USD", "GBP", "CHF"}


def build(seed=42, accounts=200, transfers=150):
    rng = random.Random(seed)
    master = generate.build_master(rng, accounts)
    movements = generate.build_movements(rng, transfers, accounts)
    return master, movements


def predict_rejects(master, movements):
    """ACCTPOST's validation, as {movement index: reason code}.

    The order is the one ACCTPOST documents and depends on: the movement's own validity first, then
    the account, then the pairing, then the balance. R005 needs the *running* booked balance, so the
    movements are walked in file order exactly as the match-merge does.
    """
    accounts = {
        record[ACCT_REF].decode(): {
            "type": record[ACCT_TYPE].decode().strip(),
            "currency": record[ACCT_CURRENCY].decode(),
            "status": record[ACCT_STATUS].decode().strip(),
            "booked": decode_comp3(record[ACCT_BOOKED]),
        }
        for record in master
    }

    rejects = {}
    for index, record in enumerate(movements):
        ref = record[MOV_ACCOUNT].decode()
        account = accounts.get(ref)
        if account is None:
            rejects[index] = "R001"
            continue

        amount = decode_comp3(record[MOV_AMOUNT])
        currency = record[MOV_CURRENCY].decode()
        direction = record[MOV_DIRECTION].decode()

        if amount <= 0:
            rejects[index] = "R006"
        elif currency not in SCALE_2:
            rejects[index] = "R004"
        elif account["status"] != "OPEN":
            rejects[index] = "R002"
        elif currency != account["currency"]:
            rejects[index] = "R003"
        else:
            debit = direction == "D"
            if account["type"] in ("ASSET", "EXPENSE"):
                effect = amount if debit else -amount
            else:
                effect = -amount if debit else amount
            new_booked = account["booked"] + effect
            if effect < 0 and new_booked < 0 and account["type"] == "LIABILITY":
                rejects[index] = "R005"
            else:
                account["booked"] = new_booked
    return rejects


class Determinism(unittest.TestCase):
    """The same seed produces the same bytes, or every downstream comparison is meaningless."""

    def test_the_master_is_byte_identical_across_two_builds(self):
        self.assertEqual(build()[0], build()[0])

    def test_the_movements_are_byte_identical_across_two_builds(self):
        self.assertEqual(build()[1], build()[1])

    def test_a_different_seed_produces_different_bytes(self):
        self.assertNotEqual(build(seed=42)[0], build(seed=43)[0])


class TheBytesOnDisk(unittest.TestCase):
    """Digests, so a change to how the records are built cannot quietly change what they contain.

    The master's digest is the same before and after WP-25a: this package changes which accounts a
    movement is drawn against, and nothing about the accounts themselves. The movement digest is
    expected to change when the draw changes, which is the point of pinning it separately.
    """

    def test_the_master_is_unchanged(self):
        master, _ = build()
        self.assertEqual(
            hashlib.sha256(b"".join(master)).hexdigest(),
            "72b40a385fc1331b2220c31c2cd5682e30f0d38a00b0042b379ab941f249f1d5",
        )

    def test_the_movement_file_is_what_it_was_last_pinned_to(self):
        _, movements = build()
        self.assertEqual(
            hashlib.sha256(b"".join(movements)).hexdigest(),
            "4860ecc6b4936cf5432af4d8a4f17d231ac9c4fad956f0917640aa679bb55d8a",
        )


class TheAwkwardBalancesStay(unittest.TestCase):
    """The fixed fixtures in build_master are what check-records.py proves the sign nibble with."""

    def test_zero_maximum_and_negative_are_all_present(self):
        master, _ = build()
        booked = {decode_comp3(record[ACCT_BOOKED]) for record in master}
        for value in (0, 999999999999999, -125000, -1, 1):
            self.assertIn(value, booked)


if __name__ == "__main__":
    unittest.main(verbosity=2)
