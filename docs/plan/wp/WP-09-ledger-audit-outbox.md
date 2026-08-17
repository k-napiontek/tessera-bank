# WP-09 - Audit chain, outbox and observability

| | |
|---|---|
| **Ticket** | TB-1009 |
| **Branch** | `feat/TB-1009-ledger-audit-outbox` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-08 |
| **Status** | `Not started` |

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

To be detailed before execution.

## Definition of Done

- [ ] Audit chain verification detects a tampered row and names it.
- [ ] Killing the relay mid-publish loses no event and duplicates are handled idempotently.
- [ ] A rolled-back transaction leaves neither a posting nor an outbox row.
- [ ] No log line in any test output contains personal data.
- [ ] `/actuator/health/liveness` and `/readiness` behave differently under a database outage.

## Verification

`./gradlew :services:ledger-core:test`. Specific scenarios: tamper with an audit row and confirm
verification reports it; force a transaction rollback and confirm no outbox row survives; stop the
relay between publish and mark-dispatched and confirm the event is republished rather than lost;
scrape the metrics endpoint and confirm business metrics are present.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-AUD-001 The audit trail is append-only and tamper-evident | hash-chained audit log |
| REQ-EVT-001 Events cannot be published without their postings committing | transactional outbox |
| REQ-OPS-002 The service exposes business-level metrics and structured logs | Micrometer, JSON logging |
| REQ-DP-002 Personal data never reaches a log | logging policy and test |
