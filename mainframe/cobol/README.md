# COBOL programs

**Stratum 0** | **Built by WP-04, WP-05**

`ACCTPOST.CBL` applies the day's movements to the account master using the classic balanced-line match-merge. `EODREPT.CBL` produces the end-of-day report with control breaks, page headers and totals. These two are the canonical mainframe batch patterns.

Compiled with GnuCOBOL. Fixed format, 80 columns. See the parent [README](../README.md).

## `ACCTPOST` - the balanced-line match-merge

**Built by WP-04.** One sequential pass over two sorted files, producing a new master and a rejects
file.

```bash
cobc -x -std=ibm -Wall -I mainframe/copybook -o /tmp/acctpost mainframe/cobol/ACCTPOST.CBL
python3 mainframe/cobol/test-acctpost.py     # 13 scenarios, all six reason codes
```

| File | Record | Direction |
|---|---|---|
| `ACCTMAST.DAT` | `ACCTREC`, 100 bytes | in, ascending by `ACCT-REF` |
| `MOVEMENT.DAT` | `MOVEREC`, 120 bytes | in, ascending by `MOV-ACCT-REF` |
| `ACCTNEW.DAT` | `ACCTREC` | out, the new master |
| `REJECTS.DAT` | `REJREC`, 200 bytes | out |

### Rejection reasons

Rejection is a business outcome, not an abend: the movement is written to the rejects file, carried
**verbatim in the first 120 bytes** so it can be re-presented to a later run without re-encoding, and
the run continues.

| Code | Meaning |
|---|---|
| `R001` | Account not found in the master. A movement never creates an account. |
| `R002` | Account is not `OPEN` |
| `R003` | Movement currency differs from the account currency |
| `R004` | ISO 4217 scale is not 2 - `PIC S9(13)V99` cannot represent it |
| `R005` | Debit would take the booked balance below zero |
| `R006` | Amount is not strictly positive |

Checks run in that order deliberately. If the currency-mismatch test came first, `R004` would be
unreachable for any account not already in the offending currency - and a currency this tier cannot
represent at all is the more fundamental problem.

`R005` is what "overdraft" means here: the master carries no limit field, because this core has no
arranged-overdraft concept. Credits are never refused on balance grounds - an account can already be
negative from legacy state, and refusing a repayment would be absurd.

### Three things worth knowing before changing it

**`ORGANIZATION IS SEQUENTIAL`, never `LINE SEQUENTIAL`.** A COMP-3 amount can contain `0x0A` or
`0x0D`. Line sequential would corrupt every packed field, and it would look as though it had worked.

**The master is never read into memory.** Match-merge exists because the master does not fit. A
version that loads it into a table would pass every test here and destroy the point of the program.

**The run timestamp comes from `ACCTPOST_RUN_TS` when set**, falling back to the clock. A batch job
takes its business date from the job, not the wall clock, and it makes the rejects file reproducible
so the harness can compare it byte for byte.

### Two bugs this program had, and how they were caught

Recorded because both are the kind that look fine in review.

**Reading ahead into the same record area.** The first version stashed the master record, read the
next one, then restored the copy - which clobbered the stash on the following iteration and wrote
record 1 twice. Caught by the empty-movement test: a pass that applies nothing must produce a
byte-identical master, and it did not.

**Intermediate fields without `V99`.** `WS-EFFECT` was `PIC S9(15) COMP-3` while the money it held is
`PIC S9(13)V99`. Every amount was silently truncated to whole units, so a debit of `100.01` against a
balance of `100.00` came out as exactly zero and the overdraft rejection never fired. Caught by the
`R005` scenario.

