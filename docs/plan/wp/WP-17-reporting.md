# WP-17 - reporting

| | |
|---|---|
| **Ticket** | TB-1017 |
| **Branch** | `feat/TB-1017-reporting` |
| **Stratum** | 4 - Python 3.12, ~2025 |
| **Depends on** | WP-09 |
| **Status** | `Not started` |

## Objective

Produce the regulatory and management reports a bank must generate from its ledger: daily position,
movement summaries, and the data extracts that regulatory submissions are built from. Batch, not
real-time, because that is what reporting genuinely is.

## In scope

- Daily position report per account and per currency, from the ledger.
- Movement summary with control totals reconciling to the ledger.
- A regulatory extract in a defined, versioned format.
- Report versioning and reproducibility: rerunning for a past date produces the same output.
- Metrics on run duration and record counts.

## Out of scope

- Submission to any actual regulator or external system.
- A BI tool, dashboard or visualisation layer.
- Real-time reporting - this tier is deliberately batch.

## Constraints

- Python 3.12 with `uv`.
- **Reports must be reproducible.** Rerunning for a historical date must produce identical output;
  reports built on mutable state are not auditable, and auditability is the entire purpose.
- Every report carries the ledger position it was generated from, so a figure can always be traced
  back to the postings behind it.
- Reports contain account references, never personal data - joining to customer identity happens
  downstream, outside this repository.
- Control totals on every report must reconcile to the ledger independently.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] Each report generates and its control totals reconcile to the ledger.
- [ ] Rerunning for a past date produces byte-identical output.
- [ ] The regulatory extract validates against its declared format.
- [ ] No personal data appears in any output.

## Verification

`uv run pytest`. Then generate reports for a seeded date, confirm totals reconcile to a direct ledger
query, rerun and confirm identical output, and grep the output for anything resembling personal data.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-REP-001 Regulatory reports are generated from the ledger | reporting jobs |
| REQ-REP-002 Reports are reproducible for historical dates | versioned, position-stamped runs |
| REQ-REP-003 Report totals reconcile independently to the ledger | control totals |
