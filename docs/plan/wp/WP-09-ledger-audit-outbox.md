# WP-09 - Audit chain, outbox and observability

| | |
|---|---|
| **Ticket** | TB-1009 |
| **Branch** | `feat/TB-1009-ledger-audit-outbox` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-08 |
| **Status** | `In progress` |

## Objective

Make the ledger auditable and observable, and make its events reliably publishable. Three concerns
that all hinge on the same transaction boundary: nothing may be recorded, emitted or observed that
did not actually happen, and nothing that happened may go unrecorded.

## In scope

- **Audit chain**: append-only log of actor, action, timestamp, before and after state, correlation
  id, and a hash of the previous row - so tampering is detectable rather than merely discouraged.
- A verification routine that walks the chain and reports the first broken link.
- **Transactional outbox**: the domain event written in the same database transaction as the
  postings, plus a relay that publishes to Kafka afterwards and marks the row dispatched.
- Observability: Micrometer metrics including business metrics (transfers by outcome, posting
  latency, outbox lag), structured JSON logging with a correlation id propagated from the inbound
  request, split liveness and readiness endpoints, OpenTelemetry tracing hooks.

## Out of scope

- Any Kafka infrastructure, topic creation or broker configuration - platform repositories.
- Dashboards and alert rules - platform repositories.
- The fraud consumer on the other end - WP-13.

## Constraints

- The outbox write and the postings write are **one** database transaction. A design where the event
  can be published without the postings committing, or the reverse, is wrong regardless of how
  unlikely the window is - this is the whole reason the outbox pattern exists.
- The relay is at-least-once. Consumers must tolerate duplicates, and this must be stated in the
  AsyncAPI contract rather than assumed.
- The audit log is append-only in the schema, not merely by convention.
- **Logs must never contain personal data.** Account references and correlation ids only. This is a
  GDPR requirement, and a log line is exactly where such leaks happen.

## Tasks

Detailed 2026-08-19. The package spans three concerns and lands in **two pull requests on one
ticket**: tasks 1-8 as `feat/TB-1009-ledger-audit-outbox`, tasks 9-13 as
`feat/TB-1009-ledger-observability`. PROTOCOL asks for one pull request per package; a single change
record of this size would defeat the commit-sizing rule stated beside it, so the deviation is
recorded here rather than taken silently.

The correlation id is task 2 rather than an observability task, because `TransferPostedPayload`
marks `correlationId` required - the outbox cannot be built without it.

### Pull request A - audit chain and outbox

1. Detail this task list and correct the Verification section below. Set the package `In progress`
   in `STATUS.md`.
2. **Correlation id.** A filter in `ledger-api` that accepts `X-Correlation-Id` when it is a valid
   UUID, generates one when it is absent or malformed, puts it in the MDC, echoes it on the response
   and clears the MDC afterwards. `ProblemWriter` reads the resolved id rather than the raw header.
3. **F-21.** `Hold.transitionTo` keeps the instant it is given. Migration for the column, adapter
   reads and writes it, and the round-trip test that proved the value could not matter is replaced by
   one proving it does. Deliberately widens the branch into WP-06's aggregate; `STATUS.md` nominates
   WP-09 as the place.
4. **Audit chain.** `audit_record`, append-only by trigger, hash-chained on the previous row. The
   canonical form that is hashed is defined in `ledger-core` as pure Java; only the chaining needs a
   database. Verification walks the chain in `seq` order and names the first broken link.
5. **Audit the use cases.** Every money-moving use case appends inside the transaction it already
   runs in. Actor and correlation id arrive through a port the API implements.
6. **Outbox.** `outbox_record` written in the same transaction as the postings. A rolled-back
   transaction leaves neither.
7. **Relay.** Claims undispatched rows with `FOR UPDATE SKIP LOCKED`, publishes through a port, marks
   dispatched. A Kafka adapter keyed by `transferRef`, proved against a real broker.
8. Documentation: module READMEs, the traceability matrix, ADRs for the outbox and the audit chain,
   and a runbook for an outbox backlog.

### Pull request B - observability

9. Liveness and readiness probes that behave differently under a database outage.
10. Structured JSON logging carrying the correlation id, with a test that no log line contains
    personal data.
11. Business metrics through Micrometer: transfers by outcome, posting latency, outbox lag.
12. OpenTelemetry tracing hooks. No exporter and no collector configuration - ADR 0001.
13. Documentation and `STATUS.md`: `Done`, both pull requests, both merge SHAs, follow-ups.

## Definition of Done

- [ ] Audit chain verification detects a tampered row and names it.
- [ ] Killing the relay mid-publish loses no event and duplicates are handled idempotently.
- [ ] A rolled-back transaction leaves neither a posting nor an outbox row.
- [ ] No log line in any test output contains personal data.
- [ ] `/actuator/health/liveness` and `/readiness` behave differently under a database outage.

## Verification

`make test-services`, not `./gradlew :services:ledger-core:test` as this section originally said. The
work spans all three modules - the ports are in `ledger-core`, the adapters and migrations in
`ledger-persistence`, the filters and endpoints in `ledger-api` - so a command covering one of them
proves a third of the package. WP-07 corrected its own verification command for the same reason.

`bash contracts/validate.sh` as well: the AsyncAPI document is the contract the outbox payload has to
satisfy, and it must still validate.

Specific scenarios, each its own test: tamper with an audit row and confirm verification reports it;
force a transaction rollback and confirm no outbox row survives; stop the relay between publish and
mark-dispatched and confirm the event is republished rather than lost; scrape the metrics endpoint
and confirm business metrics are present.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-AUD-001 The audit trail is append-only and tamper-evident | hash-chained audit log |
| REQ-EVT-001 Events cannot be published without their postings committing | transactional outbox |
| REQ-OPS-002 The service exposes business-level metrics and structured logs | Micrometer, JSON logging |
| REQ-DP-002 Personal data never reaches a log | logging policy and test |
