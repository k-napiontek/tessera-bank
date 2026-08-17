# WP-16 - Reconciliation

| | |
|---|---|
| **Ticket** | TB-1016 |
| **Branch** | `feat/TB-1016-recon` |
| **Stratum** | spans 0 and 3 |
| **Depends on** | WP-05, WP-11 |
| **Status** | `Not started` |

## Objective

Compare the COBOL account master against the PostgreSQL ledger every morning and report any drift.
This is the safety net that makes strangler-fig modernisation survivable: while two systems both
believe they hold the truth about a customer's money, the only defensible position is to check them
against each other daily and investigate every difference. Without it, the migration is a guess.

## In scope

- A job reading the COBOL master file, decoding COMP-3, and comparing balances account by account
  against the ledger.
- A break report classifying each difference: timing, missing on one side, or genuine value drift.
- Break records surfaced to `backoffice` for operators to work.
- Control totals: accounts compared, matched, broken, and total absolute drift.
- Alerting hooks and metrics so the platform repositories can act on breaks.

## Out of scope

- Automatic correction of breaks. A reconciliation that silently fixes differences destroys the
  evidence of why they occurred - breaks are investigated by humans, never auto-healed.
- The scheduler that runs it - platform repositories.

## Constraints

- Read-only against both systems. This job never writes to the ledger or the master.
- **Timing differences must be distinguished from genuine drift.** A movement posted after the
  mainframe cut-off is expected and is not a break; classifying it as one trains operators to ignore
  the report, which is worse than having no report.
- The comparison must be deterministic and reproducible from the same two inputs.
- A break report of zero breaks must still be produced and recorded - absence of output is
  indistinguishable from a failed job.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] A clean run over consistent data reports zero breaks and produces a report.
- [ ] A deliberately injected discrepancy is detected and correctly classified.
- [ ] A post-cut-off movement is classified as timing, not drift.
- [ ] Breaks appear in `backoffice`.
- [ ] Control totals balance.

## Verification

Run the full spine end to end, then reconcile and confirm zero breaks. Then inject three faults - a
value discrepancy, an account missing from the master, and a post-cut-off movement - and confirm each
is detected and classified correctly.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-REC-001 Old and new cores are reconciled every cycle | reconciliation job |
| REQ-REC-002 Timing differences are distinguished from genuine drift | break classification |
| REQ-REC-003 Breaks are investigated, never auto-corrected | read-only design |
