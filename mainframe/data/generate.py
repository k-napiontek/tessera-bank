#!/usr/bin/env python3
"""Generate the synthetic account master and movement files.

Deterministic for a given seed: the same seed produces byte-identical files, so every downstream test
is reproducible and a diff between two runs means a real change rather than a reshuffle.

**All data here is synthetic and must remain so.** There are no names, no addresses and no
identifiers of any kind - the records carry account and customer *references* from the canonical
patterns and nothing else. There is nothing in these files that could relate to a person, because
there is nothing about people in them at all.

Layouts come from docs/architecture/canonical-data-model.md by way of contracts/copybook/. This
writes bytes; check-records.py reads them back and asserts they match.

Usage:
    python3 mainframe/data/generate.py --seed 42
"""

import argparse
import pathlib
import random
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from comp3 import encode_comp3  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
OUT = REPO / "mainframe" / "data" / "out"

ACCTREC_LEN = 100
MOVEREC_LEN = 120

BUSINESS_DATE = "20260817"
POSTED_TS = "20260817091500"

# Stratum 0 carries scale-2 currencies only: PIC S9(13)V99 hard-codes two decimals. See the canonical
# data model section 2, and the reject fixture at the bottom of this file.
MASTER_CURRENCIES = ["PLN", "PLN", "PLN", "EUR", "USD"]

ACCOUNT_TYPES = ["LIABILITY", "LIABILITY", "LIABILITY", "ASSET", "REVENUE"]


def text(value: str, width: int) -> bytes:
    """Fixed-width alphanumeric: left-justified, space-padded, never null-padded."""
    encoded = value.encode("ascii")
    if len(encoded) > width:
        raise ValueError(f"{value!r} does not fit in PIC X({width})")
    return encoded.ljust(width, b" ")


def number(value: int, width: int) -> bytes:
    """Fixed-width display numeric: zero-padded on the left."""
    encoded = str(value).encode("ascii")
    if len(encoded) > width:
        raise ValueError(f"{value} does not fit in PIC 9({width})")
    return encoded.rjust(width, b"0")


def account_ref(n: int) -> str:
    return f"TB{n:014d}"


def customer_ref(n: int) -> str:
    return f"CU{n:010d}"


def transfer_ref(n: int) -> str:
    return f"TB{BUSINESS_DATE}{n:010d}"


def acctrec(ref, customer, acct_type, currency, status, booked, available, opened, last_move) -> bytes:
    """One 100-byte ACCTREC. Field order is the copybook's; see contracts/copybook/column-map.md."""
    record = (
        text(ref, 16)
        + text(customer, 12)
        + text(acct_type, 9)
        + text(currency, 3)
        + text(status, 7)
        + encode_comp3(booked)
        + encode_comp3(available)
        + number(opened, 8)
        + number(last_move, 8)
        + b" " * 21
    )
    assert len(record) == ACCTREC_LEN, f"ACCTREC is {len(record)} bytes, expected {ACCTREC_LEN}"
    return record


def moverec(transfer, leg, account, direction, currency, amount, value_date, posted, reference) -> bytes:
    """One 120-byte MOVEREC."""
    record = (
        text(transfer, 20)
        + number(leg, 2)
        + text(account, 16)
        + text(direction, 1)
        + text(currency, 3)
        + encode_comp3(amount)
        + number(value_date, 8)
        + number(posted, 14)
        + text(reference, 35)
        + b" " * 13
    )
    assert len(record) == MOVEREC_LEN, f"MOVEREC is {len(record)} bytes, expected {MOVEREC_LEN}"
    return record


def build_master(rng: random.Random, count: int) -> list:
    """The account master, sorted ascending by account reference as the match-merge requires."""
    return [acctrec(**account) for account in draw_accounts(rng, count)]


def draw_accounts(rng: random.Random, count: int) -> list:
    """The accounts as data, before they become bytes.

    Kept separate from the encoding because build_movements has to see what currency and status an
    account carries. A movement drawn without regard to either rejects, correctly, and a file of such
    movements measures the reject path rather than the posting path - F-18, open since WP-05.

    The awkward balances are deliberate and fixed rather than random. A generator that only emits
    comfortable numbers tests nothing, and these are the cases that break encoders:

      * zero, which must carry the positive sign nibble;
      * the maximum representable value, every nibble a 9;
      * a negative balance, on an account whose overdraft policy permits it.
    """
    accounts = []

    def account(ref, customer, acct_type, currency, status, booked, available, opened, last_move):
        return {
            "ref": ref,
            "customer": customer,
            "acct_type": acct_type,
            "currency": currency,
            "status": status,
            "booked": booked,
            "available": available,
            "opened": opened,
            "last_move": last_move,
        }

    # The bank's own cash account: an ASSET, and the counterparty to every customer movement.
    accounts.append(account(
        "TB00000000000001", "CU0000000001", "ASSET", "PLN", "OPEN",
        50_000_000_00, 50_000_000_00, 20100101, int(BUSINESS_DATE)))

    fixtures = [
        # (ref number, booked minor units, note)
        (2, 0),                    # zero - sign nibble must be 0x0C
        (3, 999999999999999),      # maximum representable - 9 999 999 999 999.99
        (4, -1_250_00),            # negative, on an arranged overdraft
        (5, -1),                   # one minor unit negative, the smallest overdrawn case
        (6, 1),                    # one minor unit positive
    ]
    for n, booked in fixtures:
        accounts.append(account(
            account_ref(n), customer_ref(n), "LIABILITY", "PLN", "OPEN",
            booked, booked, 20180301 + n, int(BUSINESS_DATE)))

    for n in range(7, count + 1):
        booked = rng.randrange(0, 500_000_00)
        held = rng.randrange(0, 25_000_00) if rng.random() < 0.25 else 0
        accounts.append(account(
            account_ref(n),
            customer_ref(n),
            rng.choice(ACCOUNT_TYPES),
            rng.choice(MASTER_CURRENCIES),
            rng.choice(["OPEN", "OPEN", "OPEN", "OPEN", "BLOCKED"]),
            booked,
            booked - held,
            20150101 + rng.randrange(0, 90000),
            int(BUSINESS_DATE)))

    accounts.sort(key=lambda one: one["ref"])
    return accounts


def build_movements(rng: random.Random, transfers: int, accounts: int) -> list:
    """A day of movements, sorted by account reference for the sequential match-merge.

    Each transfer produces exactly two legs - leg 01 the debit, leg 02 the credit - sharing one
    transfer reference. Stratum 0 has no Transfer record at all; the batch reconstructs one from the
    pair, which is why the pairing has to be right in the data.
    """
    rows = []

    for n in range(1, transfers + 1):
        debit = account_ref(rng.randrange(2, accounts + 1))
        credit = account_ref(rng.randrange(2, accounts + 1))
        while credit == debit:
            credit = account_ref(rng.randrange(2, accounts + 1))

        amount = rng.randrange(1_00, 25_000_00)
        reference = f"TRANSFER {BUSINESS_DATE} SEQ {n:06d}"

        rows.append((debit, moverec(transfer_ref(n), 1, debit, "D", "PLN", amount,
                                    int(BUSINESS_DATE), int(POSTED_TS), reference)))
        rows.append((credit, moverec(transfer_ref(n), 2, credit, "C", "PLN", amount,
                                     int(BUSINESS_DATE), int(POSTED_TS), reference)))

    # The reject fixture. JPY has an ISO 4217 scale of 0, which PIC S9(13)V99 cannot represent - the
    # implied decimal would misstate it a hundredfold. The integration tier rejects such a movement
    # before it ever reaches stratum 0; this one exists so WP-04 can prove the mainframe rejects it
    # too. A 1995 core does not trust its feeds.
    reject_account = account_ref(2)
    rows.append((reject_account, moverec(
        transfer_ref(transfers + 1), 1, reject_account, "D", "JPY", 1000,
        int(BUSINESS_DATE), int(POSTED_TS), "REJECT FIXTURE UNSUPPORTED SCALE")))

    # An unknown account, so WP-04 has an unmatched-movement case as well.
    orphan = account_ref(999999)
    rows.append((orphan, moverec(
        transfer_ref(transfers + 2), 1, orphan, "D", "PLN", 500_00,
        int(BUSINESS_DATE), int(POSTED_TS), "REJECT FIXTURE UNKNOWN ACCOUNT")))

    rows.sort(key=lambda pair: pair[0])
    return [record for _, record in rows]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=42, help="same seed, same bytes")
    parser.add_argument("--accounts", type=int, default=200)
    parser.add_argument("--transfers", type=int, default=150)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    OUT.mkdir(parents=True, exist_ok=True)

    master = build_master(rng, args.accounts)
    movements = build_movements(rng, args.transfers, args.accounts)

    master_path = OUT / "ACCTMAST.DAT"
    movement_path = OUT / "MOVEMENT.DAT"
    master_path.write_bytes(b"".join(master))
    movement_path.write_bytes(b"".join(movements))

    print(f"seed {args.seed}")
    print(f"  {master_path.relative_to(REPO)}   {len(master):>4} records x {ACCTREC_LEN} = {len(master) * ACCTREC_LEN} bytes")
    print(f"  {movement_path.relative_to(REPO)}   {len(movements):>4} records x {MOVEREC_LEN} = {len(movements) * MOVEREC_LEN} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
