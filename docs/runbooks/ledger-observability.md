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

Since WP-23 the ledger also reports on the database doing the work. Until then it counted how many
transfers posted and said nothing at all about the PostgreSQL that posted them.

| Metric | Tags | Reading it |
|---|---|---|
| `ledger_lock_chain_seconds` | - | wait for the audit chain's advisory lock - **service-wide**, one writer at a time |
| `ledger_lock_account_seconds` | - | wait for the row locks on the accounts a transaction touches |
| `ledger_db_table_size_bytes` | `table` | heap size per table, indexes excluded |
| `ledger_db_index_size_bytes` | `table` | what the index set costs |
| `ledger_db_dead_tuples` | `table` | rows superseded and not yet reclaimed |
| `ledger_db_live_tuples` | `table` | the denominator to read dead tuples against |
| `ledger_db_autovacuums` | `table` | autovacuums since the statistics were last reset |
| `ledger_db_autovacuum_age_seconds` | `table` | age of the last one. **`NaN` means never** |
| `hikaricp_connections_*` | `pool` | pool utilisation and acquire wait, from Boot's own binder |

Four readings that mean specific things:

- **`outcome="rejected"` rising** is the ledger working. Refusals are business outcomes: insufficient
  funds, a closed account, a currency mismatch. A rise means callers are sending more of them, which
  is a question for the caller, not an incident here.
- **`outcome="failed"` rising** is the ledger failing - those are 5xx. It is separated from
  `rejected` precisely so the two cannot be averaged into one meaningless graph.
- **`outcome="replayed"` rising** means the ledger is being offered keys it has seen before. It does
  **not** on its own mean clients are retrying, and it does not mean they timed out - this page said
  both until WP-24c measured otherwise. A run that re-offered a business day it had already posted
  drove the counter to 9 080 replays out of 9 080 money movements with nothing having timed out at
  all, because the keys are derived from the business date and were therefore identical. A client
  that re-sends after a timeout, a queue that redelivers, a batch replayed after an operator restarts
  it and a caller that simply repeated itself are indistinguishable in this counter. So the second
  half of the old advice is the whole of it: **check `ledger_posting_latency_seconds` before
  concluding anything about the callers.** Flat latency under rising replays means something upstream
  re-sent work, not that this ledger was slow.
- **`ledger_outbox_lag_seconds` rising** is the one to page on, rather than `ledger_outbox_pending`.
  A backlog of ten thousand draining steadily is a busy afternoon; a backlog of one that has not
  moved in ten minutes is stuck. See [`outbox-backlog.md`](outbox-backlog.md). **Checked by WP-24c
  and it holds** - with one thing this page did not say. It is a gauge, so it can only be read as a
  series: `SCN-OUTBOX-STUCK` froze the broker for the window its scenario declares and the gauge read
  0 before and 0 after, because the relay had drained by the time the closing sample was taken. An
  alert built from two points cannot see an outbox that stuck and recovered between them, which is
  the whole class of incident this signal exists for.

And three about the database signals:

- **The two lock waits are separate series on purpose, and must never be summed.** The chain lock is
  service-wide and the account locks are per account, so a single averaged figure moves for two
  unrelated reasons and cannot tell a busy corporate account from a ceiling the whole service is
  queued behind.
- **`ledger_lock_chain_seconds` climbing while throughput is flat is the ceiling, not a fault.**
  Measured: money movement peaks at about 790 postings a second on a developer machine, and past that
  point every extra caller joins a queue - latency grows, nothing fails, and adding a second ledger
  instance does not help, because the lock is in the database rather than in the JVM. The figures and
  their conditions are in [`../architecture/estate-under-load.md`](../architecture/estate-under-load.md).
- **`ledger_db_autovacuum_age_seconds` is `NaN`, never 0, for a table never autovacuumed.** Zero
  would read as "vacuumed a moment ago", which is the exact opposite of the truth.
- **`ledger_db_live_tuples` is an estimate and `ledger_db_table_size_bytes` is not.** The first is
  `n_live_tup` out of `pg_stat_user_tables`, which the statistics collector maintains and autovacuum
  corrects; the second is `pg_table_size`, which is exact. Quoting a row count to the unit from the
  first is quoting a estimate as a measurement. Checked by WP-24b, whose soak report labels the two
  differently for this reason.
- **`ledger_db_dead_tuples` means nothing without `ledger_db_autovacuums` beside it.** Dead tuples
  rising while the vacuum count does not move is a collector that has **not been asked**; dead tuples
  rising while it climbs is a collector that is running and losing. They are different incidents and
  only the second is urgent.

  Measured by WP-24b over twelve business days: `balance` accumulated dead tuples monotonically while
  `ledger_db_autovacuums` never moved off 1. That is the first case, and the arithmetic says why -
  autovacuum triggers at `autovacuum_vacuum_threshold + scale_factor x live_tuples`, which on this
  estate's defaults is `50 + 0.2 x 336 956` = **about 67 400 dead tuples**. The soak reached under a
  tenth of it. **A hot table with a large row count vacuums rarely**, because the threshold scales
  with the whole table rather than with the part being written, so "dead tuples are climbing and
  nothing is vacuuming" is the expected state for `balance` and not a fault.

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

- [`../ways-of-working/slo-catalogue.md`](../ways-of-working/slo-catalogue.md) - what each of these
  signals is expected to be, and which of them carries an objective
- [ADR 0004](../governance/adr/0004-transactional-outbox.md), [ADR 0005](../governance/adr/0005-hash-chained-audit-trail.md),
  [ADR 0012](../governance/adr/0012-slo-catalogue-boundary.md)
- [`outbox-backlog.md`](outbox-backlog.md)
- [`schema-change-under-traffic.md`](schema-change-under-traffic.md) - what a migration does to these
  signals, and why `ledger_posting_latency_seconds` stays met through one
- [`../ways-of-working/data-classification.md`](../ways-of-working/data-classification.md)
