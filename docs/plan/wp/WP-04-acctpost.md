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

To be detailed before execution.

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
