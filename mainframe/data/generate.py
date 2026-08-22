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
import json
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


# A debit that would take a LIABILITY account below zero is rejected R005 - this core has no arranged
# overdraft. The generator respects that rather than producing rejects it did not mean to.
MIN_AMOUNT = 1_00
MAX_AMOUNT = 25_000_00


def build_movements(rng: random.Random, transfers: int, accounts: list) -> list:
    """A day of movements, sorted by account reference for the sequential match-merge.

    Each transfer produces exactly two legs - leg 01 the debit, leg 02 the credit - sharing one
    transfer reference. Stratum 0 has no Transfer record at all; the batch reconstructs one from the
    pair, which is why the pairing has to be right in the data.

    Both legs land on accounts that share one currency, and that currency is the movement's. Stratum
    0 has no cross-currency record: a movement carries one amount in one currency and lands on one
    account, so a transfer whose legs disagree is not a product this core has. Both accounts are
    OPEN, and a debit never exceeds what the debited account holds. Every one of those was drawn at
    random before WP-25a and 162 of 302 movements rejected - F-18 - so the file exercised rejection
    handling and barely touched posting.

    The two reject fixtures at the bottom are deliberate and stay: WP-04 proves R004 and R001 with
    them, and a generator with no rejects at all would exercise the reject path less than one whose
    rejects are accidental.
    """
    rows = []

    postable = [one for one in accounts if one["status"] == "OPEN"]
    by_currency = {}
    for one in postable:
        by_currency.setdefault(one["currency"], []).append(one)
    # Only currencies with two accounts can carry a transfer at all.
    currencies = sorted(code for code, pool in by_currency.items() if len(pool) >= 2)
    if not currencies:
        raise ValueError("no currency has two open accounts to move between")

    # Weighted by how many accounts hold each currency, so the mix of movements follows the mix of
    # accounts rather than being flat across currencies the master barely uses.
    weights = [len(by_currency[code]) for code in currencies]

    balances = {one["ref"]: one["booked"] for one in postable}

    def headroom(account):
        """What may be debited from this account before ACCTPOST would reject R005."""
        if account["acct_type"] != "LIABILITY":
            return MAX_AMOUNT
        return balances[account["ref"]]

    for n in range(1, transfers + 1):
        currency = rng.choices(currencies, weights=weights, k=1)[0]
        pool = by_currency[currency]

        fundable = [one for one in pool if headroom(one) >= MIN_AMOUNT]
        if len(fundable) < 1 or len(pool) < 2:
            continue
        debit = rng.choice(fundable)
        credit = rng.choice(pool)
        while credit["ref"] == debit["ref"]:
            credit = rng.choice(pool)

        amount = rng.randrange(MIN_AMOUNT, min(MAX_AMOUNT, headroom(debit)) + 1)
        reference = f"TRANSFER {BUSINESS_DATE} SEQ {n:06d}"

        for account, leg, direction in ((debit, 1, "D"), (credit, 2, "C")):
            rows.append((account["ref"], moverec(
                transfer_ref(n), leg, account["ref"], direction, currency, amount,
                int(BUSINESS_DATE), int(POSTED_TS), reference)))
            balances[account["ref"]] += effect_of(account["acct_type"], direction, amount)

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


def effect_of(acct_type: str, direction: str, amount: int) -> int:
    """ACCTPOST's COMPUTE-EFFECT, so the generator can see the balance the cycle will see."""
    if acct_type in ("ASSET", "EXPENSE"):
        return amount if direction == "D" else -amount
    return amount if direction == "C" else -amount


# ---------------------------------------------------------------------------------------------
# The volume mode: a bank day drawn by WP-20, written as stratum-0 files.
#
# workload-dataset emits a business day as NDJSON and services/ledger-loader already consumes it -
# WP-22's decision, so that neither side draws the bank's day twice. This reads the same stream and
# writes what the online day would have handed the overnight cycle.
#
# Three things the stream does not carry, and what is done about each:
#
#   * **No account currency.** The model draws a currency per transfer from a mix of up to five and
#     gives each customer two accounts, so an account has no currency of its own. Every account is
#     therefore opened in the stream's base currency and every movement drawn in another currency is
#     posted in it and COUNTED - the convention WP-21 established against the ledger and F-72
#     records, reused here rather than a second answer being invented one stratum down.
#   * **No opening balance.** Customer accounts open funded and the treasury is debited for the
#     total, which is how WP-22's loader states the same thing: every opening balance came from
#     somewhere.
#   * **No legs.** A createTransfer is one action; stratum 0 carries two records, leg 01 the debit
#     and leg 02 the credit, sharing one transfer reference.
# ---------------------------------------------------------------------------------------------

# Enough that a day's debits post without the file becoming a study of the overdraft path, and far
# inside PIC S9(13)V99. Stated as a constant because it is an assumption, not a measurement.
OPENING_BALANCE = 1_000_000_00


def accounts_from_stream(header: dict, opens: list) -> list:
    """The account master, from the stream's open records. Sorted as the match-merge requires."""
    currency = header["baseCurrency"]
    business_date = int(header["from"].replace("-", ""))

    accounts = []
    treasury_total = 0
    for record in opens:
        treasury = record.get("treasury", False)
        booked = 0 if treasury else OPENING_BALANCE
        if not treasury:
            treasury_total += OPENING_BALANCE
        accounts.append({
            "ref": record["accountRef"],
            "customer": record["customerRef"],
            "acct_type": record["accountType"],
            "currency": currency,
            "status": "OPEN",
            "booked": booked,
            "available": booked,
            "opened": business_date,
            "last_move": business_date,
        })

    # The treasury funded every one of them, so it carries the contra balance rather than zero.
    for account in accounts:
        if account["ref"] == header["treasuryAccountRef"]:
            account["booked"] = treasury_total
            account["available"] = treasury_total

    accounts.sort(key=lambda one: one["ref"])
    return accounts


def movements_from_stream(header: dict, actions: list, accounts: list) -> tuple:
    """The movement file, from the stream's actions. Returns (records, counts).

    Only createTransfer moves money between two accounts, which is the only shape MOVEREC has. Every
    other operation in the model is a read, a hold or a reversal, and each is counted rather than
    dropped silently - a file that omits without saying so is a file whose totals cannot be checked.
    """
    currency = header["baseCurrency"]
    business_date = int(header["from"].replace("-", ""))
    posted_ts = business_date * 1000000 + 91500

    known = {one["ref"]: one for one in accounts}
    balances = {one["ref"]: one["booked"] for one in accounts}

    counts = {
        "actions": len(actions),
        "transfers": 0,
        "notAMovement": 0,
        "unknownAccount": 0,
        "currencySubstituted": 0,
        "wouldOverdraw": 0,
    }

    rows = []
    for action in actions:
        if action.get("operation") != "createTransfer":
            counts["notAMovement"] += 1
            continue

        debit = known.get(action.get("accountRef"))
        credit = known.get(action.get("counterpartyRef"))
        if debit is None or credit is None:
            counts["unknownAccount"] += 1
            continue

        amount = int(action.get("amountMinor", 0))
        if amount <= 0:
            counts["notAMovement"] += 1
            continue

        if action.get("currency", currency) != currency:
            counts["currencySubstituted"] += 1

        if debit["acct_type"] == "LIABILITY" and balances[debit["ref"]] - amount < 0:
            counts["wouldOverdraw"] += 1
            continue

        counts["transfers"] += 1
        reference = f"TRANSFER {business_date} SEQ {counts['transfers']:06d}"
        transfer = transfer_ref(counts["transfers"])
        for account, leg, direction in ((debit, 1, "D"), (credit, 2, "C")):
            rows.append((account["ref"], moverec(
                transfer, leg, account["ref"], direction, currency, amount,
                business_date, posted_ts, reference)))
            balances[account["ref"]] += effect_of(account["acct_type"], direction, amount)

    rows.sort(key=lambda pair: pair[0])
    return [record for _, record in rows], counts


def read_stream(handle) -> tuple:
    """The NDJSON stream, split into its header, its open records and its actions."""
    header, opens, actions = None, [], []
    for line in handle:
        line = line.strip()
        if not line:
            continue
        record = json.loads(line)
        kind = record.get("kind")
        if kind == "population":
            header = record
        elif kind == "open":
            opens.append(record)
        elif kind == "action":
            actions.append(record)
    if header is None:
        raise ValueError("the stream carries no population header")
    return header, opens, actions


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=42, help="same seed, same bytes")
    parser.add_argument("--accounts", type=int, default=200)
    parser.add_argument("--transfers", type=int, default=150)
    parser.add_argument("--from-stream", action="store_true",
                        help="read a workload-dataset NDJSON day on stdin instead of drawing one")
    parser.add_argument("--out", type=pathlib.Path, default=OUT,
                        help="where to write, so a volume run never overwrites the fixture")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    if args.from_stream:
        header, opens, actions = read_stream(sys.stdin)
        accounts = accounts_from_stream(header, opens)
        master = [acctrec(**account) for account in accounts]
        movements, counts = movements_from_stream(header, actions, accounts)
        label = f"{header['from']} from {header['modelId']} seed {header['seed']}"
    else:
        rng = random.Random(args.seed)
        accounts = draw_accounts(rng, args.accounts)
        master = [acctrec(**account) for account in accounts]
        movements = build_movements(rng, args.transfers, accounts)
        counts, label = None, f"seed {args.seed}"

    master_path = args.out / "ACCTMAST.DAT"
    movement_path = args.out / "MOVEMENT.DAT"
    master_path.write_bytes(b"".join(master))
    movement_path.write_bytes(b"".join(movements))

    print(label)
    print(f"  {master_path}   {len(master):>8} records x {ACCTREC_LEN} = {len(master) * ACCTREC_LEN} bytes")
    print(f"  {movement_path}   {len(movements):>8} records x {MOVEREC_LEN} = {len(movements) * MOVEREC_LEN} bytes")
    if counts is not None:
        # Every action the stream offered, accounted for. A file that omits without saying so is a
        # file whose totals cannot be checked - and the substitution count is what says how much of
        # the drawn day this file actually represents.
        print(f"  actions offered      {counts['actions']:>8}")
        print(f"  transfers written    {counts['transfers']:>8}  (two legs each)")
        print(f"  not a movement       {counts['notAMovement']:>8}  reads, holds and reversals")
        print(f"  unknown account      {counts['unknownAccount']:>8}")
        print(f"  would overdraw       {counts['wouldOverdraw']:>8}")
        print(f"  currency substituted {counts['currencySubstituted']:>8}  posted in "
              f"{header['baseCurrency']} rather than the currency drawn - F-72")
    return 0


if __name__ == "__main__":
    sys.exit(main())
