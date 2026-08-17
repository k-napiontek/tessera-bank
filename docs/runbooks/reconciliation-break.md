# Runbook: reconciliation break

> **STUB.** Outline only. Filled by **WP-16**.

What to do when the morning reconciliation reports a difference between the COBOL account master and the PostgreSQL ledger. Breaks are investigated by humans and never auto-corrected.

## Planned contents

- Reading the break report and its classifications
- Timing difference vs. genuine drift - how to tell, and why the distinction matters
- Investigation path per break type: value drift, missing on one side, duplicate
- Evidence to gather before taking any action
- Escalation thresholds by value and by count
- Why breaks are never corrected automatically, and what to do instead
- Recording the outcome and feeding it into incident management
