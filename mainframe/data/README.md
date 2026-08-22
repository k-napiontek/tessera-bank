# Batch data files

**Stratum 0** | **Built by WP-03**

Synthetic account master and movement files in fixed-width format with COMP-3 amounts, plus the generator that produces them. Deterministic for a given seed, so tests are reproducible.

**All data here is synthetic and must remain so.** No name, address or identifier may resemble a real person. Generated output (`out/`) is gitignored - regenerate it, never commit it.

## Contents

| File | Purpose |
|---|---|
| `comp3.py` | COMP-3 packed decimal, encode and decode |
| `test_comp3.py` | The canonical model's worked examples, asserted as literal bytes |
| `generate.py` | The account master and movement generator, in two modes |
| `test_generate.py` | Determinism, and what the cycle would reject, predicted from the data |
| `check-records.py` | Every generated field against `contracts/copybook/column-map.md` |
| `out/` | Generated output. Gitignored - regenerate it, never commit it. |

```bash
python3 mainframe/data/generate.py --seed 42
python3 mainframe/data/check-records.py
python3 mainframe/data/test_generate.py
```

## Two modes

**The fixture**, drawn here, is what every downstream test in four packages compares against. It is
small, deliberate and byte-identical for a given seed.

**A volume day**, read from `workload-dataset` on stdin, is what WP-25a drives the overnight cycle
with - the same NDJSON pipe `services/ledger-loader` consumes, so neither side draws the bank's day
twice:

```bash
go -C workload run ./cmd/workload-dataset --model ../contracts/workload/tessera-day-v1.json \
    --from 2026-03-02 --to 2026-03-02 --seed 42 --scale 0.02 --customers 200000 \
  | python3 mainframe/data/generate.py --from-stream --out /tmp/day
```

`--out` exists so a volume run never overwrites the committed fixture. Every action the stream
offered is accounted for on the run's own output - transfers written, reads and holds that are not
movements, unknown accounts, debits that would have overdrawn, and how many movements were posted in
the base currency rather than the one the model drew. A file that omits without saying so is a file
whose totals cannot be checked.

The stream carries a currency on every action and none on any account, so every account opens in the
base currency and the substitutions are **counted**. That is the convention F-72 records WP-21
establishing against the ledger, reused here rather than a second answer being invented.

## Determinism

The same seed produces byte-identical files. Verified by running twice and comparing with `cmp`, not
assumed - a generator that reshuffles between runs makes every downstream test flaky and every diff
unreadable.

## A movement lands on an account that can accept it

Until WP-25a the movements were all `PLN` while the master drew five currencies, and they were drawn
without regard to account status - so **162 of 302 rejected** and the cycle's happy path was barely
exercised on real data. Every rejection was correct; what was wrong was what the file exercised.
Both legs of a transfer now share the currency of the accounts they land on, movements land only on
`OPEN` accounts, and an amount is drawn against the debited account's running balance so the file
carries no debit the cycle was always going to refuse. **300 of 302 post.**

The two that remain are deliberate and must stay: an unknown account (`R001`) and a JPY amount whose
ISO 4217 scale of 0 `PIC S9(13)V99` cannot represent (`R004`). WP-04 proves the mainframe's own
validation with them, and a generator with no rejects at all would exercise the reject path less
than one whose rejects are accidental.

## The awkward cases are deliberate

A generator that emits only comfortable numbers tests nothing. The master carries a balance of zero
(sign nibble `0x0C`), the maximum representable value (`0x99` seven times, then `0x9C`), and negative
balances including one of a single minor unit. `check-records.py` fails if any of those disappears,
because a sign nibble nothing exercises is a sign nibble nobody has checked.

The movement file carries two reject fixtures for WP-04: one in JPY, whose ISO 4217 scale of 0 the
`PIC S9(13)V99` field cannot represent, and one against an account that does not exist.

## Why the tooling is Python

The COBOL-85 rule governs `.CBL` and `.CPY` files. This is tooling that produces stratum 0 data, not
stratum 0 source, and writing it in COBOL would make it harder to read without making it more
authentic. Standard library only, so it runs from a clean checkout.

