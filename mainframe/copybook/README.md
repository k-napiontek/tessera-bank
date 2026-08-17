# Copybooks

**Stratum 0** | **Built by WP-03**

Fixed-width record layouts, included by every COBOL program so a field offset is defined exactly once: `ACCTREC.CPY` (account master), `MOVEREC.CPY` (movement), `REJREC.CPY` (reject).

These must stay identical to [`contracts/copybook/`](../../contracts/copybook/). If a layout needs to change, the contract changes first.

## How "identical" is enforced

`check-identity.py` asserts that this directory and [`contracts/copybook/`](../../contracts/copybook/)
hold the same set of `.CPY` files and that every one is **byte-identical** - in both directions, so a
file that exists here and not in the contract is a failure too.

Byte-identical rather than equivalent is deliberate. In a fixed-format language a difference in
trailing spaces is a difference in what the compiler sees, and "equivalent" is exactly the judgement
that lets a layout drift.

The contract is the source. To change a layout, change `contracts/copybook/` first, then copy.

## Contents

| File | Purpose |
|---|---|
| `ACCTREC.CPY`, `MOVEREC.CPY`, `REJREC.CPY` | The record layouts, copied from the contract |
| `CPYCHK.CBL` | A compile harness, not an application program. It declares the records and stops. |
| `compile-check.sh` | Runs `cobc -fsyntax-only -std=ibm` over the harness |
| `check-identity.py` | The byte-identity assertion |

`CPYCHK.CBL` exists because column arithmetic cannot prove a picture clause compiles. A layout that
adds up and that `cobc` rejects is still broken - and the first run of this harness proved the point
by rejecting `COMP-3` under `-std=cobol85`.

