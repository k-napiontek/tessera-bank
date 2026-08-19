# fraud-scoring

**Stratum 4** | **Python 3.12 under uv** | **Built by WP-13**

Scores every posted transfer for fraud risk, off the Kafka event stream, and publishes a decision.

**Asynchronous by design.** Scoring is never in the money-movement path: a slow model, a stopped consumer or a broker outage must not be able to stop a customer moving their own money. Stop this service entirely and transfers still post - the only thing that stops is the estate hearing what it thinks of them.

**It decides, it does not act.** A `BLOCK` is published as an opinion. Reversing a transfer happens through the ledger's own reversal path, with its own audit trail; nothing here touches money.

## The pipeline

```
tessera.ledger.transfer-posted.v1  ->  score  ->  tessera.fraud.decision.v1
```

Both topics and both payloads are defined in [`contracts/asyncapi/ledger-events.yaml`](../../contracts/asyncapi/ledger-events.yaml). The tests validate against that document rather than against what this code believes it says.

## What is guaranteed, and what is not

**Publish, then commit.** The offset moves only after the broker has acknowledged the decision. Reversed, a crash between the two loses a decision permanently and leaves nothing behind to say so; this way a crash produces the decision twice. At-least-once, deliberately - the same trade the ledger's outbox relay makes.

**Exactly one *distinct* decision per transfer, not exactly one message.** Scoring is a pure function, so a redelivered event produces a byte-identical decision, and the topic is keyed by `transferRef` so a compacted view holds one per transfer. Making "exactly one message" literally true needs a durable seen-set, which is a store this repository is not allowed to describe how to deploy ([ADR 0001](../../docs/governance/adr/0001-source-only-repository.md)). The README says which of the two you get rather than letting the Definition of Done's wording imply the stronger one.

**A message that cannot be read is skipped**, counted in `tessera_fraud_malformed_total`, and its offset committed. Halting instead would mean one malformed message ends scoring for the whole bank, on every restart, for ever.

## Reproducibility

REQ-FRD-003 says a decision must be reproducible from its recorded version, and two things make that true.

**Every rule is a pure function of one event.** No clock, no randomness, no lookup, no memory of what came before. The out-of-hours rule reads the event's own `postedAt`, so a message scored today and replayed next year reach the same answer. A test parses `rules.py` and fails if it imports `datetime`, `time`, `random`, `os` or anything else that could make one event score two ways.

**This is why there is no velocity rule.** "Five transfers from this account in ten minutes" would be the most useful rule here, and it depends on what this consumer has already seen and in what order - so a replay would score differently and the reproducibility claim would quietly become false. Behavioural rules need a feature store this service does not have. See [ADR 0008](../../docs/governance/adr/0008-fraud-rules-are-pure-functions.md).

**The recorded version covers the parameters, not just the code.** `modelVersion` is the catalogue version plus a digest of the thresholds in force - `rules-2026.08.1+99fe6d36`. A catalogue version alone would be reproducible only if nobody had touched the configuration since, which is exactly the assumption model risk management exists to forbid.

`decidedAt` is deliberately outside that guarantee. When scoring happened is a fact about the run, not about the transfer, and freezing it to make two payloads byte-identical would be the service lying about when it did its work.

## Rule set `rules-2026.08.1`

| Code | Weight | Fires when |
|---|---|---|
| `AMT_STRC` | 350 | The amount sits just below the reporting threshold - the shape of a payment sized to avoid a report |
| `AMT_HIGH` | 300 | At or above the high-value threshold |
| `AMT_RND` | 100 | Large *and* exactly round. Rent and salaries are round, so the floor matters |
| `REVERSAL` | 150 | The transfer reverses an earlier one |
| `SELFPAY` | 500 | Debit and credit name the same account, which the ledger forbids outright |
| `OFFHOURS` | 100 | Posted outside business hours, by the event's own timestamp |
| `LEGMISM` | 400 | The two postings do not match the transfer they belong to |

Below the review threshold the decision is `ALLOW`, at or above it `REVIEW`, at or above the block threshold `BLOCK`. The weights are coarse on purpose: this is a rule engine standing in for a model, and three significant figures of risk would be a claim nobody can support. What they have to get right is the ordering.

`SELFPAY` alone reaches `REVIEW` rather than `BLOCK`, and that is the intended answer. The ledger cannot produce such a transfer, so what is suspect is the *event*, not necessarily the money - blocking would trigger a reversal of a posting that may be perfectly good.

## Configuration

Read from the environment at boot. A setting that is present but unparseable fails the boot; it never falls back to a default. Every problem is reported at once.

| Variable | Default | Meaning |
|---|---|---|
| `TB_FRAUD_BROKERS` | **required** | Kafka bootstrap servers. |
| `TB_FRAUD_TRANSFER_TOPIC` | `tessera.ledger.transfer-posted.v1` | Where transfers are read from. |
| `TB_FRAUD_DECISION_TOPIC` | `tessera.fraud.decision.v1` | Where decisions are published. |
| `TB_FRAUD_GROUP_ID` | `fraud-scoring` | Consumer group. |
| `TB_FRAUD_REVIEW_THRESHOLD` | `400` | At or above this score, `REVIEW`. |
| `TB_FRAUD_BLOCK_THRESHOLD` | `750` | At or above this score, `BLOCK`. |
| `TB_FRAUD_HIGH_AMOUNT_MINOR` | `10000000` | 100 000.00, in minor units. |
| `TB_FRAUD_REPORTING_THRESHOLD_MINOR` | `1000000` | 10 000.00 - the limit structuring stays under. |
| `TB_FRAUD_METRICS_PORT` | `9100` | Where the scrape endpoint listens. |
| `TB_FRAUD_LOG_LEVEL` | `info` | `debug`, `info`, `warning` or `error`. |
| `TB_FRAUD_POLL_SECONDS` | `1.0` | How long one poll waits for a message. |

The four thresholds are part of `modelVersion`, so changing any of them changes what every subsequent decision says produced it. A review threshold at or above the block threshold makes `REVIEW` unreachable and is refused at boot.

## Observability

JSON logs on stdout, carrying the correlation id that arrived on the event - so one customer request can be followed from the gateway, through the ledger, to the decision taken about it here.

**The remittance reference is never logged.** It is the one field a paying customer controls and the one the ledger deliberately keeps out of its audit rows; a log store is read by more people than the ledger is. The formatter drops it, and anything named like a credential, whatever a caller passes.

| Metric | Answers |
|---|---|
| `tessera_fraud_decisions_total{decision}` | Is anything being scored, and what is it deciding? |
| `tessera_fraud_score` | A histogram. A rule set drifting towards a threshold shows here long before the outcomes change. |
| `tessera_fraud_scoring_seconds` | Is it keeping up? |
| `tessera_fraud_malformed_total` | Is something publishing messages this cannot read? |
| `tessera_fraud_publish_failures_total` | Is the broker refusing decisions? |

## Building and testing

```bash
make test-fraud    # uv run pytest, including one test against a real Kafka
make lint-edge     # ruff check and ruff format --check
make build-edge    # resolves against the lock file
```

Or directly, from this directory:

```bash
uv run pytest
```

**A running Docker daemon is required**, because four of the tests start a real Kafka through Testcontainers. They are not marked optional and not skipped when Docker is absent: a suite that quietly skips its only real-broker test reports success for a service nobody has proved works.

## Running it

```bash
TB_FRAUD_BROKERS=localhost:9092 uv run fraud-scoring
```

## Dependencies

| Module | Why |
|---|---|
| `confluent-kafka` | The librdkafka binding, which is what this industry runs. It exposes the delivery acknowledgement that publish-before-commit depends on. |
| `prometheus-client` | The exposition format and its collectors. |

Development only: `pytest`, `jsonschema` and `PyYAML` for the contract tests, `testcontainers` for the broker, `ruff` for the linting.
