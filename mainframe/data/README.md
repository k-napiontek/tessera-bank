# Batch data files

**Stratum 0** | **Built by WP-03**

Synthetic account master and movement files in fixed-width format with COMP-3 amounts, plus the generator that produces them. Deterministic for a given seed, so tests are reproducible.

**All data here is synthetic and must remain so.** No name, address or identifier may resemble a real person. Generated output (`out/`) is gitignored - regenerate it, never commit it.

## Contents

| File | Purpose |
|---|---|
| `comp3.py` | COMP-3 packed decimal, encode and decode |
| `test_comp3.py` | The canonical model's worked examples, asserted as literal bytes |
| `generate.py` | The account master and movement generator |
| `check-records.py` | Every generated field against `contracts/copybook/column-map.md` |
| `out/` | Generated output. Gitignored - regenerate it, never commit it. |

```bash
python3 mainframe/data/generate.py --seed 42
python3 mainframe/data/check-records.py
```

## Determinism

The same seed produces byte-identical files. Verified by running twice and comparing with `cmp`, not
assumed - a generator that reshuffles between runs makes every downstream test flaky and every diff
unreadable.

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

