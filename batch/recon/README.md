# recon

**Spans strata 0 and 3** | **Built by WP-16**

Compares the COBOL account master against the PostgreSQL ledger every morning and reports drift. This is the safety net that makes strangler-fig modernisation survivable: while two systems both believe they hold the truth about a customer's money, the only defensible position is to check them against each other daily.

**Read-only against both systems, and breaks are never auto-corrected.** A reconciliation that silently fixes differences destroys the evidence of why they occurred. Breaks are investigated by humans - see the [runbook](../../docs/runbooks/reconciliation-break.md).

**Timing differences must be distinguished from genuine drift.** A movement posted after the mainframe cut-off is expected; classifying it as a break trains operators to ignore the report, which is worse than having no report.

