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

---

## `EODREPT` - the control-break report

**Built by WP-05.** One sequential pass over the master in report sequence, page-broken for a line
printer at 132 columns.

```bash
cobc -x -std=ibm -Wall -I mainframe/copybook -o /tmp/eodrept mainframe/cobol/EODREPT.CBL
python3 mainframe/cobol/test-eodrept.py      # 18 scenarios
```

| File | Record | Direction |
|---|---|---|
| `ACCTRPT.DAT` | `ACCTREC`, 100 bytes | in, ascending by currency then `ACCT-REF` |
| `REJECTS.DAT` | `REJREC`, 200 bytes | in, for the reject recap |
| `EODREPT.TXT` | 132-column print lines | out |

The input must arrive in currency order. `STEP030` of the cycle sorts it; the report never sorts and
never holds the master, for the same reason `ACCTPOST` does not.

### What is on the page

A page is 60 lines, six of them header, leaving **54 detail lines**. Each currency starts on a fresh
page and closes with its own subtotal. Two recap pages follow: the currency recap with the grand
total, and the reject recap with the reconciliation.

```
  *** CURRENCY TOTAL  PLN            112 ACCOUNTS          1,234,567.89          1,234,567.89

  *** GRAND TOTAL             200 ACCOUNTS
        NO CROSS-CURRENCY AMOUNT IS PRINTED.  SEE THE CURRENCY RECAP ABOVE.
```

### There is no cross-currency total, deliberately

The work package asks for "a grand total". Adding 100 PLN to 100 EUR produces a number that means
nothing and that no auditor would accept, so the grand total counts **accounts**, the money figures
stay per currency, and the report says so in print. Printing a summed figure would be the report
equivalent of WP-04's `V99` truncation: plausible-looking and simply wrong.

### The report reconciles against the run that produced it

The reject recap counts `REJECTS.DAT` by reason code and prints the count beside the figure
`ACCTPOST` reported, which the job supplies in `EODREPT_CTL_REJECTED`:

- **absent** - the line reads `NOT SUPPLIED` and the report says `*** NOT RECONCILED`. A report that
  prints a reconciliation it did not perform is worse than one that prints none.
- **equal** - `*** IN BALANCE`.
- **different** - `*** OUT OF BALANCE` and the program ends `RC=12`, which stops the cycle.

The reason **text** comes from the reject record itself, so the six codes stay owned by `ACCTPOST`.
A second copy of them in this program is a second copy to drift.

### Three things worth knowing before changing it

**The print file is `LINE SEQUENTIAL`, and it is the only file in this tier that should be.** The
rule that COMP-3 bytes forbid line sequential is about *data* files. A printed report holds no
packed field, and a line printer produces lines.

**At a page break the form feed replaces the newline.** `WRITE AFTER ADVANCING PAGE` emits `0x0C`
instead of the line terminator, which is what a printer expects. Split the report on form feeds
before measuring line lengths - stripping them first splices the last line of one page onto the
first of the next and reports a 241-column line that does not exist.

**The money columns are fifteen digits wide though a balance holds thirteen.** A currency subtotal is
a sum of balances and needs the extra room, and it prints with the same picture under the same
columns. A narrower total field would drop high-order digits in silence.

### The bug this program had

**Arithmetic inside an `IF` condition.** `IF WS-BODY-LINES + 2 > WS-BODY-LIMIT` compiles and works,
and `cobc -Wall` warns that the precision of the result may change under `arithmetic-osvs`. It went
unnoticed because the test harness captures the compiler's output. The harness now fails on any
compiler output at all, which is the only way a warning stays visible in a suite that prints thirty
lines of `PASS`.

