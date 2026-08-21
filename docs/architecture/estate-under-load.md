# The estate under load - what it actually does

**Produced by [WP-23](../plan/wp/WP-23-slo-baseline.md)** | Companion to
[`query-plans-at-volume.md`](query-plans-at-volume.md)

Two measurements and what they mean. The first answers **F-27**, open since WP-09: how much money
this ledger can move, and what stops it. The second is the estate's **recorded normal** - a bank day
driven against a production-shaped database, kept so that a later run is a diff rather than a memory.

Every figure here comes from an artefact committed in [`workload/baselines/`](../../workload/baselines/),
and every artefact names the conditions it was taken under.

> **These were taken on a developer machine** - Darwin arm64, 10 cores, Go 1.25, PostgreSQL 16 in
> Docker, a Hikari pool of 16 per ledger. What they establish is a **shape**: where throughput stops
> rising, and what stops it. The shape is a property of the design. The absolute numbers are a
> property of the laptop, and quoting them as capacity would be exactly the mistake this document
> exists to prevent.

---

## 1. The audit chain is the ceiling, and a second instance does not move it

[ADR 0005](../governance/adr/0005-hash-chained-audit-trail.md) chose a hash-chained audit trail and
stated the cost up front: `JdbcAuditLog` takes `pg_advisory_xact_lock` before reading the last hash
and holds it until the transaction commits, so appends cannot interleave. That is what makes sequence
order and chain order the same order. It is also a hard throughput ceiling - **one writer at a time,
service-wide** - and F-27 said the redesign was "worth revisiting only with a measured number, not a
hunch".

`workload-ceiling` walks a concurrency ladder against the ledger directly, each worker holding two
accounts of its own so that what saturates is the chain lock rather than row contention.

### One ledger instance

| Workers | Postings/s | Mean latency | Chain wait | Account wait |
|---:|---:|---:|---:|---:|
| 1 | 309.4 | 3.23 ms | 0.105 ms | 0.230 ms |
| 2 | 600.4 | 3.33 ms | 0.131 ms | 0.243 ms |
| 4 | 765.0 | 5.23 ms | 1.206 ms | 0.289 ms |
| **8** | **786.3** | 10.17 ms | 6.290 ms | 0.281 ms |
| 16 | 749.1 | 21.34 ms | 17.327 ms | 0.289 ms |
| 32 | 744.0 | 42.94 ms | 17.503 ms | 0.291 ms |
| 64 | 723.0 | 88.12 ms | 17.983 ms | 0.296 ms |

Throughput doubles from one worker to two, is within a few per cent of its peak by four, and **stops
there**. From 8 workers to 64 the rate does not rise at all and mean latency grows by a factor of
nine - 10 ms to 88 ms. That is the signature of a single serialisation point: past saturation, every
worker added joins a queue rather than doing work, so the only thing that grows is the wait.

**The two lock timers are what make this readable, and they are why WP-23 separated them.** The chain
wait climbs from 0.1 ms to 18 ms and then flattens - it *is* the queue. The account wait sits at
0.23-0.30 ms at every level and never moves, because each worker owns its accounts and nothing
contends for them. One averaged "lock wait" would have moved for both reasons and answered neither
question.

### Two ledger instances on one PostgreSQL

| Workers | Postings/s | Mean latency | Chain wait | Account wait |
|---:|---:|---:|---:|---:|
| 1 | 294.3 | 3.40 ms | 0.106 ms | 0.237 ms |
| 2 | 510.2 | 3.92 ms | 0.204 ms | 0.260 ms |
| **4** | **751.6** | 5.32 ms | 1.229 ms | 0.288 ms |
| 8 | 735.0 | 10.88 ms | 6.757 ms | 0.293 ms |
| 16 | 741.4 | 21.56 ms | 17.503 ms | 0.286 ms |
| 32 | 725.7 | 44.02 ms | 39.660 ms | 0.324 ms |
| 64 | 723.7 | 88.11 ms | 40.064 ms | 0.297 ms |

**The ceiling does not move.** 751.6 against 786.3 is within the run-to-run variation of the single
instance, and the honest reading of the pair is *no improvement whatsoever*. At 32 workers the chain
wait has **doubled** - 17.5 ms to 39.7 ms - which is what a queue does when twice as many transactions
join it and the thing they are queueing for did not get faster.

This is the answer, and it is worth stating plainly because it is easy to get wrong: **the advisory
lock lives in the database, not in the JVM.** Two ledger processes are two clients of the same lock.
Horizontally scaling the application tier buys read capacity and availability, and buys exactly
nothing for money movement.

### What this does and does not license

It does **not** license a redesign. F-27 asked for a number; this is the number, and the alternatives
still cost what ADR 0005 says they cost - a per-subject chain makes deleting a subject's whole history
undetectable, and a periodically-signed checkpoint needs key management this repository deliberately
does not have. What has changed is that the trade is now priced.

It does establish three things a design conversation needs:

- The ceiling is **per estate**, not per instance, and no amount of application scaling moves it.
- It is reached at **single-digit concurrency**. There is no wide operating band to tune inside.
- Beyond it the failure is **latency, not errors**: not one request failed at any level. A system
  past this point degrades silently, which is the mode that reaches customers before it reaches a
  dashboard.

---

## 2. The recorded normal

[`workload/baselines/baseline-report.txt`](../../workload/baselines/baseline-report.txt) is a
compressed bank day driven through the gateway against a ledger loaded by WP-22's loader - 40 001
accounts and 799 565 rows over a fortnight of business dates, dataset digest `747f4177`, chain head
`d0c59134`.

```
scheduled 34 328, sent 34 323, elapsed 45.0s      (about 760 requests a second)
money movement   posted 9 132
reads            posted 25 191
latency, from the intended send time
  mean 4ms   p50 5ms   p95 10ms   p99 100ms   max 174ms
peak in flight 110, schedule lag max 19ms
reconciliation against the ledger's own count: posted 9 132 / 9 132, ok
```

Both computable ledger objectives and both gateway objectives were **met**, with the error budget
untouched. The reconciliation is the part worth keeping: the driver's count of what it posted and the
ledger's own `ledger_transfers_total` agree exactly, so the two independent accounts of the run
describe the same events.

**The schedule lag stayed at 19 ms**, which is what says the latency figures describe the estate
rather than the driver. A run whose scheduler is falling behind is measuring itself
([ADR 0016](../governance/adr/0016-the-workload-model-is-open.md)).

### Three things the baseline could not measure, recorded rather than omitted

The report prints `nothing happened` and `no figure` against them rather than leaving them out,
because a baseline that quietly drops what it could not exercise will be quoted as though it had.

- **`fraud-scoring` was not running.** It consumes from Kafka and the fixture boots no broker.
- **`reporting` and `recon` did not run.** Both are batch jobs whose metrics go to a node_exporter
  textfile, and neither executes during a nine-hour compressed window.
- **`SLO-LEDGER-OUTBOX-FRESHNESS` was outside its objective** - lag 140 s at the start and 185 s at
  the end, against a 60 s target. **That is the fixture, not the ledger.** With no broker to publish
  to, the relay cannot drain and the oldest unpublished event can only age. It is left in the report
  because suppressing a signal that a fixture happened to break is how a control stops being one.

### Two objectives no snapshot pair can answer

`SLO-LEDGER-OUTBOX-FRESHNESS`, `SLO-LEDGER-POOL-HEADROOM`, `SLO-REPORTING-RUN-DURATION` and
`SLO-RECON-CONTROL-RUNS` are stated over a **proportion of a measurement window**. Two scrapes are
two points, and `workload-report` refuses to produce a figure from them - it prints the two points it
has and says which objective needed the window. Answering them properly needs a metric store scraping
over time, which is a platform concern ([ADR 0012](../governance/adr/0012-slo-catalogue-boundary.md)),
not a gap in the catalogue.

---

## 3. What changed because of these numbers

- **F-69 is closed.** `workload-plan` warned above 2 000 requests a second, a round number named in
  its own comment as standing in for a figure nobody had. It is now **800**, which is where the two
  measurements above agree, and the note it prints cites the baselines rather than asserting a limit.
- **The ledger's latency timer now publishes a bucket at 500 ms.** A Micrometer `Timer` publishes
  count, sum and max and no buckets, so `SLO-LEDGER-POSTING-LATENCY` - "the proportion answered
  within half a second" - was a claim nobody could compute from a scrape. `CatalogueScrapeTest` now
  fails if the configured bucket and the catalogued threshold drift apart.
- **F-71 is closed.** A first `releaseHold` was counted as a replay by the ledger's own metric and by
  the driver, so a baseline recorded before fixing it would have carried a replay rate that was
  wrong in both accounts at once - and they agreed, so the reconciliation looked perfect.

## Reproducing

```bash
bash workload/scripts/ceiling.sh --levels 1,2,4,8,16,32,64 --duration 10s
bash workload/scripts/baseline.sh --customers 20000 --from 2026-06-01 --to 2026-06-15
```

Both need Docker, a JDK 17 and Go. The second takes several minutes, most of it the load.
