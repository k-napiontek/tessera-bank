# WP-05 - EODREPT.CBL, JCL and the cycle runner

| | |
|---|---|
| **Ticket** | TB-1005 |
| **Branch** | `feat/TB-1005-eodrept` |
| **Stratum** | 0 - COBOL-85, ~1995 |
| **Depends on** | WP-04 |
| **Status** | `Done` |

## Objective

Complete the mainframe tier: the end-of-day report, the JCL that defines the nightly job graph, and a
shell runner that executes the same graph locally under GnuCOBOL. After this package the overnight
cycle can be run end to end on a laptop, which is what every later integration package depends on.

## In scope

- `EODREPT.CBL` - the end-of-day report, using **control breaks** with page headers, per-currency
  subtotals and a grand total. The second canonical COBOL pattern after match-merge.
- `EODCYCLE.JCL` - the authentic job graph: SORT, then ACCTPOST, then EODREPT, with DD statements.
- `run-eod.sh` - reproduces the same graph locally, since GnuCOBOL cannot execute JCL.
- A runbook for the cycle in `docs/runbooks/eod-cycle.md`, written the way an operations team reads
  one: what it does, when it runs, what each step produces, and what to do when a step fails.

## Out of scope

- Any scheduler configuration - Control-M or equivalent belongs to the platform repositories.
- Reconciliation - WP-16.

## Constraints

- The JCL must be authentic 1990s style even though it will never execute here. It is documentation
  of the real-world artefact and a reader should recognise it as such; `run-eod.sh` is the executable
  equivalent and the two must describe the same graph.
- The report is fixed-width, 132 columns, page-broken - as it would be for a line printer.
- The runner must be idempotent: running the cycle twice on the same inputs produces the same
  outputs, and never applies the same movement file twice.

## Tasks

Eight tasks, roughly one commit each, test-first throughout.

### Four things settled before task 1

**1. The report needs its input in currency order, so the cycle gains a second SORT step.**
`ACCTPOST` writes the new master in account-reference order, because that is the order the
match-merge consumes it in. A control break on currency against a file sorted by account reference
produces a subtotal every time the currency changes - which, in reference order, is constantly. The
figures look like subtotals and are nonsense.

The job graph is therefore four steps, not three:

| Step | Program | In | Out |
|---|---|---|---|
| `STEP010` | `SORT` | `MOVEMENT.DAT` as delivered | `MOVESORT.DAT`, ascending by `MOV-ACCT-REF` |
| `STEP020` | `ACCTPOST` | `MOVESORT.DAT`, `ACCTMAST.DAT` | `ACCTNEW.DAT`, `REJECTS.DAT` |
| `STEP030` | `SORT` | `ACCTNEW.DAT` | `ACCTRPT.DAT`, ascending by currency then reference |
| `STEP040` | `EODREPT` | `ACCTRPT.DAT`, `REJECTS.DAT` | `EODREPT.TXT` |

This is how DFSORT is actually used - a report sequence is a sort step, not something the report
program arranges for itself - and it keeps `EODREPT` a single sequential pass like every other
program in this tier. The **In scope** section above says "SORT, then ACCTPOST, then EODREPT",
written before the ordering problem was visible; the graph here supersedes it.

**2. There is no cross-currency grand total, because PLN, EUR and USD do not add.**
The **In scope** section asks for "per-currency subtotals and a grand total". Per-currency subtotals
are money. A grand total across currencies is not: adding 100 PLN to 100 EUR yields a number that
means nothing and that no auditor would accept. Summing them would be the report equivalent of the
`V99` truncation in WP-04 - a plausible-looking figure that is simply wrong.

So the grand total is a **record count**, the per-currency figures are repeated in a closing recap,
and the report says so in print:

```
      *** GRAND TOTAL                          200 ACCOUNTS
          NO CROSS-CURRENCY AMOUNT IS PRINTED.  SEE THE CURRENCY RECAP ABOVE.
```

**3. `EODREPT` reads the rejects file as well as the master.** REQ-MF-007 asks for an auditable
report with balancing totals. A report that lists only surviving accounts cannot be reconciled
against the run that produced it. Reading `REJECTS.DAT` and printing counts by reason code puts both
halves of `ACCTPOST`'s control totals on the face of the report, where an operator reads them.

**4. Reason codes are not redefined here.** The six codes are WP-04's, in `ACCTPOST.CBL` and
documented in `mainframe/cobol/README.md`. `EODREPT` counts them; it does not own them, and it must
not invent a seventh.

### 1. Skeleton, page headers and pagination

`mainframe/cobol/EODREPT.CBL`: identification, environment and data divisions. Input `ACCTRPT.DAT`
(`ACCTREC`, `ORGANIZATION IS SEQUENTIAL`), output `EODREPT.TXT` at 132 columns.

**The report file is `LINE SEQUENTIAL`, and it is the one file in this tier that should be.** The
rule that `COMP-3` bytes forbid line sequential is about *data* files. A printed report contains no
packed fields, and a line printer produces lines.

Page depth 60, of which 54 are detail. Header block on every page: institution, report id, business
date, page number, column headings. `WRITE AFTER ADVANCING PAGE` for the form feed.

Business date and run timestamp come from `EODREPT_BUS_DATE` and `EODREPT_RUN_TS` via `ACCEPT FROM
ENVIRONMENT`, falling back to the clock - the idiom `ACCTPOST` already uses, and what lets two runs
of the cycle produce byte-identical output.

Test: a 55-account fixture puts **54 detail lines on page 1 and 1 on page 2**, and page 2 carries a
full header. Off-by-one at a page boundary is the classic defect of this program shape.
`feat(mainframe): add eodrept skeleton and page headers [TB-1005]`

### 2. Control breaks and per-currency subtotals

Break on `ACCT-CURRENCY`: on change, print the subtotal - accounts, booked total, available total -
then start the next currency on a fresh page.

Accumulators are `PIC S9(15)V99 COMP-3`: wider than an account balance because they hold a sum of
them, and still scale 2. A subtotal field without `V99` truncates exactly the way WP-04's
`WS-EFFECT` did, and the report would look entirely plausible.

Tests, with expected figures written out as explicit numbers: three currencies produce three
subtotals; a currency holding a single account still breaks; **the final currency's subtotal prints
at end of file**, with no following record to trigger the break. That last one is the defect this
pattern always has.
`feat(mainframe): break the report on currency with subtotals [TB-1005]`

### 3. Currency recap, reject recap, and the balance check

Closing pages. The currency recap repeats one line per currency, then the account-count grand total
and the printed statement that no cross-currency amount is shown.

The reject recap reads `REJECTS.DAT` sequentially and counts by reason code into a six-entry table.
`ACCTPOST`'s own rejected count arrives in `EODREPT_CTL_REJECTED`.

- absent: print `NOT SUPPLIED`. Never print agreement that was not checked.
- present and equal: print `IN BALANCE`.
- present and different: print `*** OUT OF BALANCE` and **return 12**.

A report that cannot be reconciled against the run that produced it is decoration, and one that
claims a reconciliation it did not perform is worse.
`feat(mainframe): add currency recap and reject recap pages [TB-1005]`

### 4. The fixed-length record sort

`mainframe/jcl/sortrec.py`, standing in for DFSORT. Unix `sort` is line-based and a `COMP-3` amount
can contain `0x0A`, so sorting these files with `sort` would silently destroy them.

```
sortrec.py --record-length 120 --key 22:38 IN OUT              # movements by MOV-ACCT-REF
sortrec.py --record-length 100 --key 37:40 --key 0:16 IN OUT   # master by currency, then reference
```

Its docstring must state that **DFSORT is an external sort and this is not** - it holds the file in
memory. Nobody should read this tool as evidence that the local cycle handles a master larger than
memory; only the COBOL does that, and only because it never sorts.

Rejects an input whose length is not a multiple of the record length rather than silently dropping
the tail. Python 3 standard library, for the reason `mainframe/data/README.md` already gives: this
is tooling that moves stratum 0 data, not stratum 0 source.

Tests: sorts an unsorted fixture, is stable on equal keys, refuses a ragged file, and leaves
`COMP-3` bytes untouched.
`feat(mainframe): add fixed-length record sort for the cycle [TB-1005]`

### 5. The cycle runner

`mainframe/jcl/run-eod.sh`, executing the four-step graph. Work directory
`mainframe/data/out/eod/<business-date>/`, seeded from the input master on every run - which is what
makes the cycle idempotent rather than merely repeatable.

Return codes are checked the way JCL checks `COND`: the first non-zero step aborts the cycle with
`EOD ABEND STEPnnn RC=n` and the runner exits non-zero. No step runs after a failed one.

Tests: the cycle completes from a clean state; **run twice, and every output file is byte-identical**
under `cmp`; a deliberately unsorted movement file still produces the correct master, which proves
`STEP010` does something; a missing master aborts at `STEP020` with a non-zero exit.
`feat(mainframe): add the local end-of-day cycle runner [TB-1005]`

### 6. The double-apply guard

On success the runner writes `MOVEMENT.APPLIED`, holding the movement file's SHA-256 and the business
date. A rerun with the same movement file for the same date refuses, exit 8, unless `--rerun` is
given.

Applying a day's movements twice doubles every posting in the bank. It is the failure this cycle must
not have, and "the operator will notice" is not a control.

Tests: the second run refuses and changes nothing; `--rerun` overrides it and reproduces identical
output; a different movement file for the same date is allowed.
`feat(mainframe): guard the cycle against applying a file twice [TB-1005]`

### 7. `EODCYCLE.JCL` and the graph parity check

`mainframe/jcl/EODCYCLE.JCL` in authentic 1990s JCL: `JOB` card with accounting information and
`MSGCLASS`, one `EXEC PGM=` per step, `SORT` control statements inline in `SYSIN`, `DD` statements
carrying `DSN`, `DISP` and `DCB=(RECFM=FB,LRECL=...)`, and `IF (STEPnnn.RC = 0) THEN` conditional
execution.

It never runs here. It is the artefact a reader must recognise, and the Definition of Done requires
it to describe the same graph as the runner - so a check enforces that rather than a comment
promising it. The harness extracts `//STEPnnn EXEC PGM=x` from the JCL and the step list from
`run-eod.sh`, and fails on any divergence in step names, order or programs. Two files that must agree
and are only asked to agree by a sentence will diverge.
`feat(mainframe): add eodcycle jcl with graph parity check [TB-1005]`

### 8. The runbook, the full run, and documentation

Run the cycle against the complete WP-03 synthetic files, feed the produced master back through
`mainframe/data/check-records.py`, and record the control totals and the report's own totals
alongside each other.

Then `docs/runbooks/eod-cycle.md` - the stub is replaced, written for an operator at 03:00 and not
for an engineer at a desk: purpose and schedule, the four steps with their inputs and outputs, what
success looks like and which totals to check, a failure mode per step with its diagnostic and its
action, restart and recovery including when `--rerun` is legitimate, where rejects land and who works
them, and the escalation path.

Finally `mainframe/jcl/README.md`, `mainframe/cobol/README.md`, `mainframe/README.md`, the
traceability matrix, and the `test-mainframe` target in the `Makefile`.
`docs(mainframe): write the eod runbook and record run evidence [TB-1005]`

## Definition of Done

- [ ] `EODREPT` compiles and produces a correctly paginated report with balancing totals.
- [ ] `run-eod.sh` executes the full cycle from a clean state and exits non-zero on any step failure.
- [ ] The JCL and the shell runner describe the same step graph.
- [ ] `docs/runbooks/eod-cycle.md` is complete and matches actual behaviour.

## Verification

Run `run-eod.sh` against the WP-03 data and confirm: the master is updated, the report totals equal
the control totals from `ACCTPOST`, pagination is correct at a page boundary, and a deliberately
failing step aborts the cycle with a non-zero exit code.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-MF-006 The end-of-day cycle is runnable and reproducible | `run-eod.sh` |
| REQ-MF-007 The cycle produces an auditable report with balancing totals | `EODREPT.CBL` |
| REQ-OPS-001 Every scheduled process has a runbook | `docs/runbooks/eod-cycle.md` |
