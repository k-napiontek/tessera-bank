# WP-04 - ACCTPOST.CBL, the match-merge

| | |
|---|---|
| **Ticket** | TB-1004 |
| **Branch** | `feat/TB-1004-acctpost` |
| **Stratum** | 0 - COBOL-85, ~1995 |
| **Depends on** | WP-03 |
| **Status** | `Not started` |

## Objective

Implement the core of the nightly batch cycle: the program that applies a day of movements to the
account master. It uses the classic **balanced-line match-merge** - a single sequential pass over
both sorted files, producing the new master and a rejects file. This is the canonical mainframe batch
algorithm, and reproducing it faithfully is the point of the whole stratum.

## In scope

- `ACCTPOST.CBL` reading the sorted movement file against the sorted account master.
- Balance application with correct packed-decimal arithmetic.
- Validation and rejection: unknown account, closed account, currency mismatch, overdraft breach.
- Control totals written at end of run: records read, applied, rejected, and value moved.
- A test harness that runs the program against known inputs and asserts the outputs.

## Out of scope

- The report - `EODREPT` is WP-05.
- JCL and the cycle runner - WP-05.
- Reconciliation against the ledger - WP-16.

## Constraints

- COBOL-85, compiled with GnuCOBOL. Fixed format, columns respected.
- Single sequential pass over each file. No random access, no in-memory table of the whole master -
  the algorithm must remain correct for a master larger than memory, which is the entire reason
  match-merge exists.
- Movements arriving for an account not present in the master go to rejects, never create an account.
- Control totals must balance: read = applied + rejected.

## Tasks

Seven tasks, roughly one commit each, test-first throughout.

### Two things settled before task 1

**1. "Overdraft breach" needs a definition, because `ACCTREC` has no overdraft field.** The In scope
above lists it as a rejection reason, but the account master carries no limit - its fields are
reference, customer, type, currency, status, booked balance, available balance, opened date and last
movement date. The 1995 core has no arranged-overdraft concept at all.

**A debit that would take a `LIABILITY` account's booked balance below zero is rejected.** That is
what overdraft breach can mean at stratum 0. Credits are never rejected on balance grounds - an
account can already be negative from fees or from legacy state, and refusing money coming in would be
absurd. The generated master genuinely contains such accounts, seeded at `-1 250.00` and `-0.01`, and
they must still accept credits.

**2. The reason codes are defined here.** [`canonical-data-model.md`](../../architecture/canonical-data-model.md)
says so explicitly - *"Codes are defined by WP-04"* - so this needs no contract change.
`REJ-REASON-CODE` is already `PIC X(04)`.

| Code | `REJ-REASON-TEXT` | Meaning |
|---|---|---|
| `R001` | `ACCOUNT NOT FOUND IN MASTER` | The movement names an account the master does not hold. Never create one. |
| `R002` | `ACCOUNT NOT OPEN` | Status is `BLOCKED` or `CLOSED`. |
| `R003` | `CURRENCY DIFFERS FROM ACCOUNT` | `MOV-CURRENCY` is not `ACCT-CURRENCY`. No conversion exists anywhere in this estate. |
| `R004` | `CURRENCY SCALE NOT SUPPORTED` | ISO 4217 scale is not 2. `PIC S9(13)V99` cannot represent it. |
| `R005` | `DEBIT EXCEEDS AVAILABLE BALANCE` | The debit would take the booked balance below zero. |
| `R006` | `AMOUNT NOT POSITIVE` | Direction carries the sign; the amount must be strictly positive. |

`R004` is where the constraint recorded in TB-1002 becomes executable. The integration tier rejects
such a movement before it reaches stratum 0, and the mainframe rejects it again on arrival - defence
in depth, because a 1995 core does not trust its feeds. WP-03 left a JPY movement in the file for
exactly this.

### 1. Skeleton and file definitions

`mainframe/cobol/ACCTPOST.CBL`: identification, environment and data divisions. Three files in,
two out - old master and movements read, new master and rejects written - plus working storage for the
control totals.

**`ORGANIZATION IS SEQUENTIAL`, never `LINE SEQUENTIAL`.** COMP-3 bytes include `0x0A` and `0x0D`; a
line-sequential file would corrupt every packed amount, and it would look like it had worked.

Compiles clean under `cobc -x -std=ibm -Wall`.
`feat(mainframe): add acctpost skeleton and file definitions [TB-1004]`

### 2. The balanced-line match-merge

The control structure, applying nothing yet: one sequential pass over both sorted files, taking the
lower key at each step, copying master records through unchanged.

**One pass, no random access, no table of the whole master.** The algorithm must stay correct for a
master larger than memory - that is the entire reason match-merge exists, and reading the master into
a table would be a plausible-looking way to destroy the point of this package.

Test: with an **empty movement file the new master is byte-identical to the old**, proved with `cmp`.
If a pass that applies nothing changes a byte, nothing built on top of it can be trusted.
`feat(mainframe): add balanced-line match-merge pass [TB-1004]`

### 3. Applying movements

COMP-3 arithmetic on booked and available balances, `MOV-DIRECTION` deciding the sign, and
`ACCT-LAST-MOVE-DATE` set to the value date of the last movement applied.

An account may receive several movements in one run - the movement file is sorted by account, and the
inner loop consumes every movement for the current key before writing the master record once.
`feat(mainframe): apply movements to the account master [TB-1004]`

### 4. Validation and rejection

All six codes, each writing a `REJREC` carrying the offending movement **verbatim in its first 120
bytes**, so it can be re-presented to a later run without being re-encoded.

Rejection is a business outcome, not an abend. A movement that cannot be applied is written to the
rejects file and the run continues.
`feat(mainframe): reject invalid movements with reason codes [TB-1004]`

### 5. Control totals

Records read, applied and rejected, and the total value moved. `read = applied + rejected` is checked
by the program itself, and a run where it does not hold ends with a non-zero status - a batch that
silently loses a record is worse than one that fails.

Displayed in a stable, greppable format so the harness and WP-05's report can both read it.
`feat(mainframe): add control totals to acctpost [TB-1004]`

### 6. The test harness

`mainframe/cobol/test-acctpost.py` - purpose-built fixture files per scenario, running the compiled
program and comparing the produced master and rejects byte for byte.

**Expected balances are written as explicit numbers.** The harness never recomputes them by
reimplementing the algorithm in Python: a harness that recomputes proves only that two
implementations share a bug. "Starts at 100.00, debited 30.00, expect 70.00" is a test; "expect
whatever my Python version calculates" is not.

Every one of the six reason codes gets a fixture that produces it.
`test(mainframe): add acctpost harness with edge-case fixtures [TB-1004]`

### 7. The full run, and documentation

Run against the complete WP-03 synthetic files, feed the **new master back through**
`mainframe/data/check-records.py`, and record the control totals. That last step is the real prize:
the file the COBOL program wrote is checked against the same contract as the file Python generated.

Then `mainframe/README.md`, `mainframe/cobol/README.md` and the traceability matrix.
`docs(mainframe): record WP-04 requirements and run evidence [TB-1004]`

## Definition of Done

- [ ] `ACCTPOST` compiles without warnings under GnuCOBOL.
- [ ] Given a known master and movement file, the new master matches the expected output byte for
      byte.
- [ ] Every rejection reason is exercised by at least one test case.
- [ ] Control totals balance on every run.

## Verification

Run the program against the WP-03 synthetic files plus purpose-built edge-case fixtures, then compare
the produced master and rejects files against expected outputs with `cmp`. Assert the control totals
in the run log.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-MF-003 Movements are applied to the master in a single sequential pass | `ACCTPOST.CBL` |
| REQ-MF-004 Invalid movements are rejected with a reason, never silently dropped | reject handling |
| REQ-MF-005 Every batch run produces balancing control totals | control totals |
