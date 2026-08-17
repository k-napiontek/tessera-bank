# WP-05 - EODREPT.CBL, JCL and the cycle runner

| | |
|---|---|
| **Ticket** | TB-1005 |
| **Branch** | `feat/TB-1005-eodrept` |
| **Stratum** | 0 - COBOL-85, ~1995 |
| **Depends on** | WP-04 |
| **Status** | `Not started` |

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

To be detailed before execution.

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
