# Runbook - what the ledger reports about itself

**Applies to:** `services/ledger-api`

Where to look when the ledger is misbehaving, and what each signal actually means. This is a map, not
an alert policy: thresholds and dashboards live in the platform repositories (ADR 0001).

## Probes

| Endpoint | Answers | What a platform does with it |
|---|---|---|
| `/actuator/health/liveness` | is the process wedged | restarts the pod |
| `/actuator/health/readiness` | can it serve a request now | takes it out of the load balancer |

**The database is in readiness and deliberately not in liveness.** Spring's default readiness group
is `readinessState` alone, which reports ready as soon as the context has started - a ledger that
cannot reach PostgreSQL would be declared ready and sent traffic it can only reject. The database
stays out of liveness because restarting a pod does not fix a database outage; it turns one outage
into a crash-loop across every instance at once, and the recovery then contends with a stampede of
reconnecting pods.

So: **readiness red across every instance, liveness green** is the signature of a database problem,
not an application one. Look at PostgreSQL first.

`show-details` is `when-authorized`, so an unauthenticated probe gets `UP` or `DOWN` and no
component names. That is intentional - a health detail names the failing component and the exception
it failed with.

## Metrics

Scrape at `/actuator/prometheus`. The business metrics, which are the ones worth alerting on:

| Metric | Tags | Reading it |
|---|---|---|
| `ledger_transfers_total` | `operation`, `outcome` | `outcome` is `posted`, `rejected`, `replayed` or `failed` |
| `ledger_posting_latency_seconds` | `operation`, `outcome` | recorded for work that happened; rejections are counted, not timed |
| `ledger_outbox_pending` | - | events written but not yet published |
| `ledger_outbox_lag_seconds` | - | age of the oldest unpublished event |

Four readings that mean specific things:

- **`outcome="rejected"` rising** is the ledger working. Refusals are business outcomes: insufficient
  funds, a closed account, a currency mismatch. A rise means callers are sending more of them, which
  is a question for the caller, not an incident here.
- **`outcome="failed"` rising** is the ledger failing - those are 5xx. It is separated from
  `rejected` precisely so the two cannot be averaged into one meaningless graph.
- **`outcome="replayed"` rising** means clients are retrying, which means they timed out. Check
  `ledger_posting_latency_seconds` before concluding the clients are at fault.
- **`ledger_outbox_lag_seconds` rising** is the one to page on, rather than `ledger_outbox_pending`.
  A backlog of ten thousand draining steadily is a busy afternoon; a backlog of one that has not
  moved in ten minutes is stuck. See [`outbox-backlog.md`](outbox-backlog.md).

## Logs

One line is one JSON object. `correlationId` is on every line of a request, so the way to read an
incident is to find one line and filter on that field - it follows the request through the gateway,
this service, and every event it produced.

`traceId` and `spanId` are there too when the request was sampled. The two identifiers are not
redundant: a trace reaches as far as W3C propagation does, which is the modern tiers, while the
correlation id also survives the SOAP call and the fixed-width record the ESB writes for the
mainframe, where no tracing exists and none is going to.

**What is never in a log line:** anything a customer supplied. No name, no address, no national
identifier, and not the remittance reference - which is free text a payer controls. `LogHygieneTest`
drives a real request carrying a marker and fails if the marker is logged, so this is a checked
property rather than a policy. If you need to see a payment's reference, read it from the ledger with
the transfer reference from the log line; do not add a log statement.

## Tracing

Sampling defaults to 10% (`LEDGER_TRACE_SAMPLE`). The sampling decision propagates, so an unsampled
request costs one header rather than a span at every tier.

There is **no exporter and no collector address in this repository**. Spans are produced and the
context propagates as W3C `traceparent`; where they are shipped is deployment configuration. If
traces are not arriving anywhere, that is a platform configuration question, not a code one.

A propagation mismatch is the failure worth knowing about: B3 and W3C do not see each other, and the
mismatch does not error - the next tier simply starts a new trace, and the symptom is "tracing is on
but nothing joins up".

## Related

- [ADR 0004](../governance/adr/0004-transactional-outbox.md), [ADR 0005](../governance/adr/0005-hash-chained-audit-trail.md)
- [`outbox-backlog.md`](outbox-backlog.md)
- [`../ways-of-working/data-classification.md`](../ways-of-working/data-classification.md)
