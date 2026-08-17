# WP-08 - Ledger API

| | |
|---|---|
| **Ticket** | TB-1008 |
| **Branch** | `feat/TB-1008-ledger-api` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-07 |
| **Status** | `Not started` |

## Objective

Expose the ledger over HTTP with the guarantees a payment system genuinely requires. The defining
feature is idempotency: a client that retries a transfer after a timeout must never move money twice,
and must receive the original response rather than a new one. This is the behaviour that separates a
banking API from a CRUD API.

## In scope

- Use cases: open account, get balance, get statement, transfer funds, place and release hold,
  reverse entry.
- REST adapters implementing `contracts/openapi/`.
- **Idempotency**: `Idempotency-Key` required on every money-moving endpoint, with a
  unique-constrained store of key, request fingerprint and stored response.
- Cursor-paginated statement endpoint.
- RFC 9457 Problem Details for every error response.
- A contract test asserting the implementation matches the OpenAPI document.

## Out of scope

- Authentication and authorisation - the gateway owns those, WP-12.
- Audit chain, outbox and metrics - WP-09.
- The customer-facing UI - WP-14.

## Constraints

- The OpenAPI document is the **source of truth**. It changes before the implementation does, and the
  contract test fails the build if they drift.
- Replaying an idempotency key returns the original stored response. The same key with a **different**
  request body returns `409 Conflict` - it is a client defect, not a retry.
- Statement pagination is cursor-based, never offset-based: offsets skip and duplicate rows when data
  is being written concurrently, which on a statement is a correctness bug rather than a cosmetic one.
- No error response may leak an internal exception, a stack trace or a SQL fragment.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] Every endpoint in the OpenAPI document is implemented and contract-tested.
- [ ] Replaying a transfer with the same key moves money once and returns the original response.
- [ ] The same key with a different body returns 409.
- [ ] Every error path returns a Problem Details document.

## Verification

`./gradlew :services:ledger-core:test`, then a manual walkthrough: open two accounts, transfer,
replay the request with the identical `Idempotency-Key` and confirm the response is byte-identical
and the balance moved once; replay with a changed amount and confirm 409; read the statement across a
page boundary.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-API-001 Money-moving requests are idempotent | idempotency store |
| REQ-API-002 The implementation cannot drift from its contract | contract test |
| REQ-API-003 Errors are machine-readable and leak nothing | RFC 9457 handler |
