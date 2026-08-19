# WP-13 - fraud-scoring

| | |
|---|---|
| **Ticket** | TB-1013 |
| **Branch** | `feat/TB-1013-fraud-scoring` |
| **Stratum** | 4 - Python 3.12, ~2025 |
| **Depends on** | WP-09 |
| **Status** | `In progress` |

## Objective

Score transfers for fraud risk asynchronously, off the Kafka event stream, and publish a decision.
Asynchronous by design: scoring must never sit in the money-movement path, because a slow or
unavailable model must not be able to stop customers moving their own money.

## In scope

- Kafka consumer for the transfer-posted event and producer for the fraud-decision event, both per
  `contracts/asyncapi/`.
- A rule-based scoring engine with explainable outputs - each decision states which rules fired.
- Configurable thresholds separating allow, review and block.
- Model and rule versioning, so a decision can be reproduced later from its recorded version.
- Structured logging and metrics, including score distribution and decision latency.

## Out of scope

- Any machine-learning model training or inference. A rule engine is honest here; a model would need
  data that does not and must not exist.
- Blocking or reversing transfers - this service publishes a decision, it does not act on it.
- Case management for reviewed transactions.

## Constraints

- Python 3.12 with `uv` for dependency management.
- **Decisions must be explainable.** A score with no reason attached is unusable in a regulated
  context, where a customer can demand to know why a payment was flagged.
- Every decision records the rule-set version that produced it. Reproducibility is a model risk
  management requirement, not a nicety.
- Consumption is at-least-once; scoring the same event twice must produce the same decision.
- No personal data in logs. Account references and correlation ids only.

## Tasks

Detailed 2026-08-19. Three decisions were taken with the repository owner before any code was
written, because each changes what gets built:

- **Determinism is the duplicate control, not a seen-set.** Scoring is a pure function of one event,
  so a redelivered event produces a byte-identical decision, and the decision topic is keyed by
  `transferRef` so a compacted view collapses them. The Definition of Done's "exactly one decision
  event" therefore holds as **exactly one distinct decision**, and the README says which of the two
  it is rather than letting a reader assume the stronger one. Making it literally true needs a
  durable seen-set, which is a store ADR 0001 forbids this repository from describing.
- **A real broker in the test suite.** One integration test starts Kafka through Testcontainers,
  publishes a genuine `TransferPosted` and asserts the `FraudDecision` that comes back validates
  against the AsyncAPI schema. The precedent is WP-09's `KafkaOutboxContractTest`: an adapter proved
  only against a double is verified by construction rather than by use.
- **`confluent-kafka`** as the client - the librdkafka binding, which is what this industry actually
  runs, and which exposes the delivery acknowledgement that commit-after-publish depends on.

**No rule may depend on anything but the event it is scoring.** A velocity rule - "five transfers
from this account in ten minutes" - would be the most useful rule here and it is deliberately absent:
it depends on what else this consumer has seen and in what order, so a replayed event would score
differently and the Definition of Done's reproducibility requirement would be false. Behavioural
rules need a feature store this service does not have, and claiming reproducibility while shipping a
stateful rule would be worse than shipping neither.

1. Detail this task list, record the decisions above, and set the package `In progress` in
   `STATUS.md`.
2. **Module skeleton and configuration.** `edge/fraud-scoring/` with `pyproject.toml` pinned to
   `>=3.12,<3.13` under `uv`, a `src/` layout, and settings read from the environment and validated
   at boot: brokers, topics, consumer group, thresholds, log level. An unparseable setting fails the
   boot rather than defaulting silently, and every problem is reported at once - the same rule the
   gateway follows, for the same reason.
3. **The event model and its contract test.** Frozen dataclasses for `TransferPosted` and
   `FraudDecision`, parsed from JSON, with a test that validates both against the schemas in
   `contracts/asyncapi/ledger-events.yaml`. An event that does not match the contract is refused
   rather than half-understood, and a decision that does not match is never published.
4. **The rule engine.** A rule is a pure function of one event: no clock, no randomness, no lookup.
   The engine sums weights into a score clamped to the contract's 0-1000, maps it to
   `ALLOW`/`REVIEW`/`BLOCK` by the configured thresholds, and returns the codes that fired. Scoring
   the same event twice produces identical output, asserted rather than assumed.
5. **Rule set version 1.** Rules built only from what the event carries: a high amount, a
   round-number amount, an amount just below a reporting threshold, a transfer that reverses another,
   a debit and credit on the same account - which the ledger forbids, so its appearance is itself the
   signal - and an out-of-hours posting derived from the event's own `postedAt` rather than from the
   wall clock. Versioned, and the version is published as `modelVersion`.
6. **The consumer.** Offsets are committed **after** the decision is published, never before. The
   ordering is the guarantee, exactly as in the outbox relay: commit first and a crash between the
   two loses a decision with nothing to show that it happened.
7. **The producer.** Keyed by `transferRef` so a decision co-partitions with the transfer it
   describes, with the broker's acknowledgement awaited before the offset moves.
8. **Observability.** JSON logs carrying the event's correlation id and never the remittance
   `reference` - the ledger deliberately keeps that field out of its audit rows, and this service must
   not reintroduce it. Prometheus metrics: decisions by outcome, a score histogram, scoring latency
   and deserialisation failures.
9. **The real-broker test.** Testcontainers Kafka, a genuine event in and a schema-valid decision
   out.
10. **Toolchain and documentation.** `ruff` as the tier's linter, Makefile targets beside the Go
    ones, the component README, the traceability rows for REQ-FRD-001, 002 and 003, and an ADR for
    the reproducibility decision.
11. **Verification and landing.** `uv run pytest`, then the live checks the package names: transfers
    complete with this service stopped, the backlog is consumed on restart, and a replayed event
    produces an identical decision. Real output into the pull request, then `STATUS.md`.

## Definition of Done

- [ ] Every transfer event produces exactly one decision event.
- [ ] Each decision names the rules that fired and the rule-set version.
- [ ] Rescoring a replayed event produces an identical decision.
- [ ] Ledger throughput is unaffected when this service is stopped entirely.

## Verification

`uv run pytest`. Then, with the service stopped, confirm transfers still complete normally; restart
it and confirm the backlog is consumed and decisions published. Replay an event and confirm the
decision is identical.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-FRD-001 Scoring never blocks money movement | asynchronous consumption |
| REQ-FRD-002 Every decision is explainable | rule attribution |
| REQ-FRD-003 Decisions are reproducible from their recorded version | rule-set versioning |
