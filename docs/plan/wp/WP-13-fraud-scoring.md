# WP-13 - fraud-scoring

| | |
|---|---|
| **Ticket** | TB-1013 |
| **Branch** | `feat/TB-1013-fraud-scoring` |
| **Stratum** | 4 - Python 3.12, ~2025 |
| **Depends on** | WP-09 |
| **Status** | `Not started` |

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

To be detailed before execution.

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
