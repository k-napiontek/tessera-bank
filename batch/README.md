# batch - scheduled processing

**Cross-cutting** | **Built by WP-16, WP-17**

The jobs that run on a schedule rather than in response to a request. Banks have a substantial batch estate, and this is the modern half of it - the other half is the COBOL end-of-day cycle in [`mainframe/`](../mainframe/).

## Contents

| Directory | Stack | Holds |
|---|---|---|
| `recon/` | - | Reconciliation between the COBOL master and the PostgreSQL ledger |
| `reporting/` | Python 3.12 | Regulatory and management reporting |

