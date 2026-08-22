# The estate under load - what it actually does

**Produced by [WP-23](../plan/wp/WP-23-slo-baseline.md), extended by
[WP-24a and WP-24c](../plan/wp/WP-24-failure-injection.md)** | Companion to
[`query-plans-at-volume.md`](query-plans-at-volume.md)

Three measurements and what they mean. The first answers **F-27**, open since WP-09: how much money
this ledger can move, and what stops it. The second is the estate's **recorded normal** - a bank day
driven against a production-shaped database, kept so that a later run is a diff rather than a memory.
The third is what the estate looks like when it is degraded on purpose: seven conditions, each
declaring in advance what it would move and what it would not, and each judged against that
declaration rather than described afterwards.

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

[`workload/baselines/spine-only/report.txt`](../../workload/baselines/spine-only/report.txt) is a
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

## 3. The estate under load with a broker behind it

**Produced by WP-24a.** `spine-only/` above is two of the five components in the SLO catalogue.
This one is three, against a ledger an order of magnitude larger, and every figure comes from
[`workload/baselines/with-broker/`](../../workload/baselines/with-broker/).

> Same developer machine, same day, same dials: Friday 2026-08-21, seed 42, scale 0.002, 720x
> compression, branch hours. What differs is the estate - Kafka, `edge/fraud-scoring` and a
> controllable hop are in it now - and the ledger underneath: **300 001 accounts and 14 491 832
> rows**, loaded in 454 s, against `spine-only/`'s 40 001 and 799 565. Both captures state their own
> conditions, which is the only reason they can be set beside each other at all.

| | `spine-only/` (WP-23) | `with-broker/` (WP-24a) |
|---|---|---|
| Requests offered | 34 323 | 34 323 |
| Money movements | 9 132 | 9 132 |
| `SLO-LEDGER-MOVEMENT-SUCCESS` | met, 1.00000 | met, 1.00000 |
| `SLO-GATEWAY-AVAILABILITY` | met, 1.00000 | met, 1.00000 |
| `fraud-scoring` | `nothing happened` | **met over 23 588 events** |
| `ledger_outbox_lag_seconds` | 140 s then 185 s, **missed** | 54 s then **0** |

### The outbox objective was the fixture, and now it is not

`SLO-LEDGER-OUTBOX-FRESHNESS` had been outside its 60-second target in every capture this repository
holds, and **F-77** guessed correctly that the reason was a fixture with no broker to publish to.
With one, the relay clears. That is the broker half of F-77 closed, and `fraud-scoring`'s two
objectives have figures for the first time.

What replaced the missing signal is a measured number rather than an absence. After a 45-second day
the relay **published the run's last 12 888 events in 1 minute 14 seconds** - it was still working
when the day had been over for a minute.

**That is the compression dial, and it is worth understanding before quoting any of these figures.**
The relay ships at most `LEDGER_OUTBOX_BATCH` rows every `LEDGER_OUTBOX_INTERVAL_MS` - 100 every
500 ms by default - and **both are fixed in wall clock**. `--scale` and `--compress` move the demand
and neither moves the relay, so a day replayed at 720x hands it money movements roughly seven hundred
times faster than the bank ever would. The 54-second lag in the opening scrape is the same effect
from seeding. In real time the bank offers the relay about 0.28 postings a second against a ceiling
of 200, and never approaches it. **F-84.**

### What this does and does not license

It licenses one sentence: *with a broker in the fixture, the modern spine meets every objective it
can be judged on at 34 323 requests over a compressed nine-hour day, against a production-shaped
ledger.* It licenses nothing about the two batch components, which still print `nothing happened` -
`reporting` and `recon` do not run inside a compressed window, and that is the half of F-77 this
package does not close.

It also licenses nothing about the estate under stress, because nothing here was stressed. That is
WP-24c.

---

## 4. Four defects, found by running the fixture rather than by reading it

WP-24a's fixture is new code, and its first real runs found four defects in it. Every one of them
would have produced **a plausible measurement of something other than the estate**, which is the
failure mode this repository keeps a trap list about, and each cost a full re-capture cycle. They are
recorded here because the next person to extend a fixture will meet the same shapes.

**A working relay reported as broken.** The drain check read `ledger_outbox_pending` and compared it
against the string `"0"`. A Prometheus gauge is a float by specification, so the scrape says `0.0`
and the check failed on an estate that had drained perfectly. A control that fires on a healthy
system is worse than no control, because the next person turns it off.

**A metric name that matched nothing.** The same check parsed the exposition with `$1 == name`, and a
Micrometer series carries its labels in the first field - `ledger_outbox_pending{application=
"ledger-api",} 0.0`. It reported `?` for every reading. Worth noticing that the two defects above
were in the *check*, not in the thing checked: the first run failed the estate, the second reported
nothing at all, and neither said anything true.

**A consumer that subscribed before its topic existed.** `edge/fraud-scoring` subscribes as it
starts, and auto-creation makes a topic when a *producer* first sends. On a run whose accounts are
already open - so nothing is relayed during seeding - the scorer subscribed to a topic that did not
exist, cached `UNKNOWN_TOPIC_OR_PART`, and did not look again until librdkafka's metadata refresh
five minutes later. The run is forty-five seconds long. Because a `prometheus_client` counter has no
series until its first increment, its metrics were **absent rather than zero**, and the report said
`nothing happened` about a component that was running the whole time. The first capture worked only
because seeding there did relay events in the first seconds of the scorer's life - a race, and a
fixture whose measurement depends on one gives a different answer on a faster machine. `estate-up.sh`
now creates both topics before anything subscribes.

**A signal that reached a wrapper.** The injector suspended the ledger by sending `SIGSTOP` to the
process group of the pid `estate-up.sh` backgrounded. `gradlew bootRun` forks the application into a
JVM owned by the **Gradle daemon**, which is in a process group of its own: measured directly,
launcher pgid **23780** against ledger JVM pgid **9371**. The signal suspended a shell wrapper, the
ledger carried on answering every request, and the injector - whose `kill` had succeeded - reported
the condition as applied. A signature captured then would have been a healthy estate filed under the
name of a degraded one. **F-73 is the same trap one level shallower**, where it cost a teardown
rather than a measurement. Components are now addressed by the pid holding the port they serve, which
is what defines the component here.

---

## 5. The seven signatures

Seven conditions were injected against the recorded normal above, one run each, under
[`contracts/workload/tessera-scenarios-v1.json`](../../contracts/workload/tessera-scenarios-v1.json)
1.1.0. Every capture is committed under
[`workload/baselines/signatures/`](../../workload/baselines/signatures/) with the scrapes it was
judged from, and every one of them declares in advance which objectives it expects to move and which
must stay flat - the flat list being the half a hand-written write-up always omits.

| Condition | Objective it moved | Where it ended | Budget | What stayed flat |
|---|---|---|---|---|
| `SCN-SLOW-DEPENDENCY` | `SLO-GATEWAY-LATENCY` | 0.88993 against 0.99 | 11.01x | availability, movement success, posting latency |
| `SCN-POOL-EXHAUSTION` | `SLO-GATEWAY-LATENCY` | 0.94292 against 0.99 | 5.71x | movement success, posting latency |
| `SCN-LEDGER-OUTAGE` | `SLO-GATEWAY-LATENCY` | 0.95230 against 0.99 | 4.77x | availability, movement success, posting latency |
| `SCN-OUTBOX-STUCK` | none observable | lag 0 at both ends | - | availability, movement success, posting latency |
| `SCN-CONSUMER-LAG` | declared to move nothing | - | - | both fraud objectives, movement success |
| `SCN-LIMITER-STORM` | declared to move nothing | - | - | availability, latency, movement success |
| `SCN-CLOCK-SKEW` | not injectable | - | - | the two batch objectives |

Not one verdict reads `CONTRADICTED`. Every line is `as declared` or `inconclusive`, and the four
inconclusive ones are named below rather than counted as successes.

### Every condition arrives at the edge as latency and never as failure

**`SLO-GATEWAY-AVAILABILITY` is 1.00000 in all seven runs.** Not one request failed, in any
condition - including the one that suspends the ledger process outright. Three separate conditions
moved an objective and all three moved **the same one**, the gateway's latency, from met to missed.

That is the most useful thing this sweep produced, and it is not what the estate's own documentation
predicted. A suspended process is not a refused connection: it holds its listening socket, the kernel
goes on accepting into the backlog, and every request in the window is answered late rather than not
at all. So a ledger that is gone in every sense a customer would recognise produces **no 502s, no
5xx and a perfect availability figure**, while its own success rate is perfect for a second reason -
a ratio computed from a component's counters cannot fall while the component is not counting.
[`edge-refusing-requests.md`](../runbooks/edge-refusing-requests.md) said a 502 means the ledger is
down; the converse does not hold, and it now says so.

### The two signals the runbook sent you to are the two that stayed flat

`SCN-POOL-EXHAUSTION` drains the ledger's connection pool from the inside - writers block on a lock,
each holding a pooled connection - and both of the ledger's own signals for it read normal:

- `SLO-LEDGER-POSTING-LATENCY` came out at **0.99398 against a 0.99 target**. Met. Real degradation,
  0.60x of its error budget, and inside the line.
- `hikaricp_connections_pending` read **0 before and 0 after**.
- `SLO-GATEWAY-LATENCY` **missed at 0.94292, 5.71x its budget.**

The ledger times the posting it performed, not the wait for a connection to perform it in, and three
quarters of the day's requests are reads that queue behind the same exhausted pool without posting
anything. The edge sees all of it and the ledger sees none of it. This is **F-83** seen twice, in two
independent sweeps that agree to three decimal places, and it is why the declaration was revised to
name the objective that actually moves.

### Four verdicts are `inconclusive`, and that is a property of the fixture

- `SLO-LEDGER-OUTBOX-FRESHNESS` and `SLO-LEDGER-POOL-HEADROOM` are **gauges**. A run brackets itself
  with two scrapes, so a condition applied and reverted between them leaves both readings identical -
  `SCN-OUTBOX-STUCK` froze the broker for its whole declared window and the lag gauge read 0 at both
  ends, because the relay had drained by the closing sample. The report used to call that
  `CONTRADICTED`, which asserted that a run unable to answer a question had proved the answer wrong.
  It now reads `inconclusive`, and closing it properly means sampling those gauges during the run -
  **F-82**.
- `SLO-RECON-CONTROL-RUNS` and `SLO-REPORTING-RUN-DURATION` belong to batch jobs that do not execute
  inside a compressed nine-hour window - **F-77**'s remaining half.

### The compressed hold is long enough, which settles F-81

A scenario states its window in business minutes and the injector divides by the compression, so
`SCN-LEDGER-OUTAGE`'s declared thirty minutes is **2.5 seconds** of wall clock, while the delay a
slow dependency adds is deliberately not compressed. That asymmetry looked like a defect and the
measurement says it is not: 2.5 compressed seconds of a stopped ledger cost 4.77x an error budget,
and 2.5 of a locked table cost 5.71x. **The hold stays business time.** It is the property that lets
one scenario be compared across two dial settings, and what limited these signatures was the scrape
bracket rather than the length of the window.

### What was not injected

`SCN-CLOCK-SKEW` reports itself uninjected with its reason rather than pretending. PostgreSQL stamps
the value date from its own `now()`, and moving a container's clock needs `SYS_TIME` on a shared
kernel or a faketime shim in the image - both changes to the estate rather than to the fixture, which
WP-24's Constraint refuses. **F-85**, recorded as a finding about testability.

### F-86: the scorer was never broken

WP-24a left seven captures uncommitted because `edge/fraud-scoring` reported
`tessera_fraud_scoring_seconds_count 0.0` in every one of them while the same fixture driven on its
own consumed correctly. It was a correct reading of a run in which nothing happened. `signatures.sh`
pins its seven business dates and `TB_KEEP_DATA=1` keeps `idempotency_record`, so the **second** sweep
against a ledger replayed every request instead of posting it: the ledger's own counter recorded
9 080 replays and 0 postings, no journal entry was written, no outbox row followed, nothing reached
the broker and the scorer scored nothing. The surviving evidence came from that second sweep.

Nothing was wrong with the consumer, the broker or the injector. What was wrong is that **a sweep is
single-use against a given ledger and did not say so.** `workload-run --require-postings` now refuses
to finish a run the ledger answered entirely out of its idempotency store, and `signatures.sh` passes
it. The scorer scored 8 411 events in six of the seven runs here and 8 409 in the seventh.

## 6. What changed because of these numbers

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

bash workload/scripts/baseline.sh --out-name spine-only \
  --customers 20000 --from 2026-06-01 --to 2026-06-15 --date 2026-08-21

bash workload/scripts/baseline.sh --out-name with-broker \
  --customers 150000 --from 2025-09-01 --to 2026-08-21 --date 2026-08-21

bash workload/scripts/signatures.sh --baseline with-broker
```

All three need Docker, a JDK 17 and Go; the last also needs uv, for the scorer. `--date` is pinned
because it has to be: left to the fixture it is today, and today has a weekday multiplier - a Friday
is 1.2 and a Saturday 0.45, so the same command run at the weekend produces a third of the demand and
a diff nobody should read as a regression. The `with-broker` capture takes about twelve minutes, most
of it the load.

`signatures.sh` runs against the database `baseline.sh` leaves behind, and it is **single-use against
it**: its seven business dates are pinned, so a second sweep over the same ledger replays instead of
posting and measures the replay path. Load the ledger again between sweeps. The driver refuses such a
run rather than reporting it, which is what section 5 is about.
