# OpenAPI contracts

**~2023** | **Built by WP-02**

The ledger-core REST API: accounts, balances, statements, transfers, holds and reversals. Errors follow RFC 9457 Problem Details.

**The source of truth for the API.** A contract test in `services/ledger-core` fails the build if the implementation drifts from this document.

**Source:** [`canonical-data-model.md`](../../docs/architecture/canonical-data-model.md). Every schema traces to a concept defined there.

`Idempotency-Key` is required on every money-moving operation. Money is always an integer count
of minor units plus an ISO 4217 code - the document contains no floating-point type anywhere.
