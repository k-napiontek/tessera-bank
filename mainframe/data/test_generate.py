#!/usr/bin/env python3
"""Tests for the synthetic data generator.

Two things are asserted here that nothing else in this repository asserts.

**The generator is deterministic.** Downstream tests in four packages compare against files this
produces, so a reshuffle between two runs would look exactly like a regression in whatever was being
tested. `check-records.py` reads the files back against the contract; it says nothing about whether
the same seed produces them twice.

**What the cycle would reject, predicted from the data alone.** `predict_rejects` reimplements
ACCTPOST's validation so that a property of the *data* can be asserted in milliseconds without a
COBOL compiler. Before WP-25a it returned 162 rejects out of 302 movements - 111 `R003`, 48 `R002`,
and one each of `R001`, `R004` and `R005` - which was exactly what the real cycle wrote to
REJECTS.DAT. F-18 was the 111 and the 48, and those two classes are asserted to zero below.

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
    drawn = generate.draw_accounts(rng, accounts)
    master = [generate.acctrec(**account) for account in drawn]
    movements = generate.build_movements(rng, transfers, drawn)
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
            "8d52759a14e7de091bc53922d7f7082e496e157ec0e6b86eb753fe00e0fab2c7",
        )


class MovementsLandWhereTheyCanPost(unittest.TestCase):
    """F-18. Every test in this class fails against the generator as it stood before WP-25a."""

    def setUp(self):
        self.master, self.movements = build()
        self.rejects = predict_rejects(self.master, self.movements)
        self.fixtures = [
            index
            for index, record in enumerate(self.movements)
            if record[MOV_REFERENCE].decode().startswith("REJECT FIXTURE")
        ]

    def test_the_status_defect_is_gone(self):
        """R002: movements were drawn without regard to whether the account was open."""
        self.assertEqual([i for i, r in self.rejects.items() if r == "R002"], [])

    def test_the_currency_defect_is_gone(self):
        """R003: every movement was PLN while the master drew five currencies. 111 of them."""
        self.assertEqual([i for i, r in self.rejects.items() if r == "R003"], [])

    def test_both_reject_fixtures_survive(self):
        """WP-04 proves R001 and R004 with these two. A generator without them tests less."""
        self.assertEqual(
            {self.rejects.get(index) for index in self.fixtures}, {"R001", "R004"}
        )

    def test_the_majority_of_movements_post(self):
        """WP-25's Definition of Done, as a number. It was 140 of 302 - 46% - before this."""
        posted = len(self.movements) - len(self.rejects)
        self.assertGreater(
            posted / len(self.movements),
            0.95,
            f"{posted} of {len(self.movements)} posted",
        )

    def test_every_master_currency_is_actually_posted(self):
        """Multi-currency posting was never exercised at all while every movement was PLN."""
        posted = {
            record[MOV_CURRENCY].decode()
            for index, record in enumerate(self.movements)
            if index not in self.rejects
        }
        self.assertEqual(posted, set(generate.MASTER_CURRENCIES))

    def test_both_legs_of_a_transfer_share_one_currency(self):
        """Stratum 0 has no cross-currency record: the batch reconstructs a transfer from the pair."""
        by_transfer = {}
        for index, record in enumerate(self.movements):
            if index in self.fixtures:
                continue
            by_transfer.setdefault(record[MOV_TRANSFER].decode(), set()).add(
                record[MOV_CURRENCY].decode()
            )
        for transfer, currencies in by_transfer.items():
            self.assertEqual(len(currencies), 1, f"{transfer} has legs in {currencies}")

    def test_no_movement_lands_on_a_blocked_account(self):
        blocked = {
            record[ACCT_REF].decode()
            for record in self.master
            if record[ACCT_STATUS].decode().strip() != "OPEN"
        }
        self.assertTrue(blocked, "the master must still contain blocked accounts")
        landed = {
            record[MOV_ACCOUNT].decode()
            for index, record in enumerate(self.movements)
            if index not in self.fixtures
        }
        self.assertEqual(landed & blocked, set())


class TheAwkwardBalancesStay(unittest.TestCase):
    """The fixed fixtures in build_master are what check-records.py proves the sign nibble with."""

    def test_zero_maximum_and_negative_are_all_present(self):
        master, _ = build()
        booked = {decode_comp3(record[ACCT_BOOKED]) for record in master}
        for value in (0, 999999999999999, -125000, -1, 1):
            self.assertIn(value, booked)


def a_stream(actions, opens=None, base="PLN"):
    """A minimal workload-dataset day, as the three record kinds the writer reads."""
    header = {
        "kind": "population",
        "modelId": "TB-WORKLOAD-DAY-V1",
        "seed": 42,
        "from": "2026-03-02",
        "to": "2026-03-02",
        "baseCurrency": base,
        "treasuryAccountRef": "TB000000000001JK",
        "treasuryCustomerRef": "CU0000001000",
    }
    if opens is None:
        opens = [
            {"kind": "open", "customerRef": "CU0000001000", "accountRef": "TB000000000001JK",
             "accountType": "ASSET", "treasury": True},
            {"kind": "open", "customerRef": "CU0000000001", "accountRef": "TB00000000000001",
             "accountType": "LIABILITY", "cohort": "retail"},
            {"kind": "open", "customerRef": "CU0000000002", "accountRef": "TB00000000000002",
             "accountType": "LIABILITY", "cohort": "retail"},
        ]
    return header, opens, actions


def transfer(account, counterparty, amount, currency="PLN"):
    return {
        "kind": "action", "date": "2026-03-02", "operation": "createTransfer",
        "accountRef": account, "counterpartyRef": counterparty,
        "amountMinor": amount, "currency": currency,
    }


class TheVolumeWriter(unittest.TestCase):
    """The WP-20 stream, written as stratum-0 files. WP-25a's task 3."""

    def write(self, header, opens, actions):
        accounts = generate.accounts_from_stream(header, opens)
        master = [generate.acctrec(**account) for account in accounts]
        movements, counts = generate.movements_from_stream(header, actions, accounts)
        return master, movements, counts, accounts

    def test_a_transfer_becomes_two_legs_sharing_one_reference(self):
        master, movements, counts, _ = self.write(
            *a_stream([transfer("TB00000000000001", "TB00000000000002", 5_00)])
        )
        self.assertEqual(counts["transfers"], 1)
        self.assertEqual(len(movements), 2)
        references = {record[MOV_TRANSFER].decode() for record in movements}
        self.assertEqual(len(references), 1)
        self.assertEqual(
            {record[MOV_DIRECTION].decode() for record in movements}, {"D", "C"}
        )

    def test_the_records_are_the_contract_length(self):
        master, movements, _, _ = self.write(
            *a_stream([transfer("TB00000000000001", "TB00000000000002", 5_00)])
        )
        self.assertTrue(all(len(record) == 100 for record in master))
        self.assertTrue(all(len(record) == 120 for record in movements))

    def test_every_account_opens_in_the_base_currency_and_substitutions_are_counted(self):
        """F-72's convention, one stratum down. The count is what says how much of the day this is."""
        _, movements, counts, accounts = self.write(
            *a_stream([
                transfer("TB00000000000001", "TB00000000000002", 5_00, currency="EUR"),
                transfer("TB00000000000002", "TB00000000000001", 7_00, currency="PLN"),
            ])
        )
        self.assertEqual({one["currency"] for one in accounts}, {"PLN"})
        self.assertEqual({record[MOV_CURRENCY].decode() for record in movements}, {"PLN"})
        self.assertEqual(counts["currencySubstituted"], 1)

    def test_the_treasury_carries_what_it_funded(self):
        """Every opening balance came from somewhere, which is how WP-22's loader states it too."""
        _, _, _, accounts = self.write(*a_stream([]))
        treasury = next(one for one in accounts if one["ref"] == "TB000000000001JK")
        customers = [one for one in accounts if one["ref"] != "TB000000000001JK"]
        self.assertEqual(treasury["booked"], sum(one["booked"] for one in customers))

    def test_everything_that_is_not_a_movement_is_counted_rather_than_dropped(self):
        actions = [
            transfer("TB00000000000001", "TB00000000000002", 5_00),
            {"kind": "action", "operation": "getBalance", "accountRef": "TB00000000000001"},
            {"kind": "action", "operation": "placeHold", "accountRef": "TB00000000000001"},
        ]
        _, _, counts, _ = self.write(*a_stream(actions))
        self.assertEqual(counts["actions"], 3)
        self.assertEqual(counts["transfers"], 1)
        self.assertEqual(counts["notAMovement"], 2)

    def test_a_debit_that_would_overdraw_is_refused_rather_than_written(self):
        """ACCTPOST would reject it R005. A file of those measures the overdraft path."""
        actions = [transfer("TB00000000000001", "TB00000000000002", generate.OPENING_BALANCE + 1)]
        _, movements, counts, _ = self.write(*a_stream(actions))
        self.assertEqual(movements, [])
        self.assertEqual(counts["wouldOverdraw"], 1)

    def test_a_transfer_to_an_account_the_stream_never_opened_is_counted(self):
        actions = [transfer("TB00000000000001", "TB00000000000999", 5_00)]
        _, movements, counts, _ = self.write(*a_stream(actions))
        self.assertEqual(movements, [])
        self.assertEqual(counts["unknownAccount"], 1)

    def test_the_master_is_sorted_as_the_match_merge_requires(self):
        master, _, _, _ = self.write(*a_stream([]))
        refs = [record[ACCT_REF] for record in master]
        self.assertEqual(refs, sorted(refs))

    def test_the_movements_are_sorted_by_account_reference(self):
        _, movements, _, _ = self.write(
            *a_stream([
                transfer("TB00000000000002", "TB00000000000001", 5_00),
                transfer("TB00000000000001", "TB00000000000002", 7_00),
            ])
        )
        refs = [record[MOV_ACCOUNT] for record in movements]
        self.assertEqual(refs, sorted(refs))

    def test_a_stream_with_no_header_is_refused(self):
        import io as _io
        with self.assertRaises(ValueError):
            generate.read_stream(_io.StringIO('{"kind":"open","accountRef":"X"}\n'))

    def test_nothing_the_writer_produces_would_be_rejected(self):
        """The predictor over the whole file: the point of the mode is that the cycle posts it."""
        master, movements, _, _ = self.write(
            *a_stream([
                transfer("TB00000000000001", "TB00000000000002", 5_00),
                transfer("TB00000000000002", "TB00000000000001", 7_00, currency="USD"),
            ])
        )
        self.assertEqual(predict_rejects(master, movements), {})


if __name__ == "__main__":
    unittest.main(verbosity=2)
