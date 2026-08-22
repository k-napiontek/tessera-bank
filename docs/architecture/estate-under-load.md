# The estate under load - what it actually does

**Produced by [WP-23](../plan/wp/WP-23-slo-baseline.md), extended by
[WP-24a, WP-24b and WP-24c](../plan/wp/WP-24-failure-injection.md)** | Companion to
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

## 6. A migration under traffic costs what the keyword says it costs, and nothing else

The exercise `master-plan.md` names as a reason this repository exists. The same index is built twice
against a live ledger, part way through a compressed bank day, with requests in flight - once with
`CREATE INDEX` and once with `CREATE INDEX CONCURRENTLY`. Everything else is held identical, so the
difference between the two captures is a consequence of that one keyword and of nothing else.

> Both runs: 338 052 accounts, **6 616 226 postings**, 3 804 955 audit rows. Scale 0.002, 720x over
> branch hours, seed 42, the migration applied 15 s into a 45 s day. `CREATE INDEX
> posting_exercise_ix ON posting (currency, amount_minor)` - deliberately **not** the index F-24 asks
> for, and dropped again afterwards. darwin arm64, 10 cores. Captured by WP-24b in
> [`migration/blocking/`](../../workload/baselines/migration/blocking/) and
> [`migration/concurrent/`](../../workload/baselines/migration/concurrent/).

| | `CREATE INDEX` | `CREATE INDEX CONCURRENTLY` |
|---|---:|---:|
| The whole Flyway invocation | 7.794 s | 8.847 s |
| Its own lock, held for | **2.25 s** `ShareLock` | **3.5 s** `ShareUpdateExclusiveLock` |
| Queued on `posting` while held | **`RowExclusiveLock`** | **nothing** |
| Backends waiting on a lock, peak | **16** | 6 |
| Requests served in the window | 4 756 | 7 601 |
| `SLO-GATEWAY-AVAILABILITY` | 1.00000 **met** | 1.00000 **met** |
| `SLO-GATEWAY-LATENCY` | **0.85744 missed** | 1.00000 **met** |
| Run latency, mean | **251 ms** | 5 ms |
| Run latency, p95 | **2.5 s** | 25 ms |
| Run latency, max | 2.835 s | 146 ms |
| Peak requests in flight | **2 255** | 72 |
| Money movements posted | 9 132 | 9 132 |

Both runs posted **exactly 9 132** money movements and reconciled exactly against the ledger's own
count. The same demand, the same ledger, the same day. One of them cost the customer nothing.

### The safer migration is the slower one, and that is the entire lesson

`CREATE INDEX CONCURRENTLY` took **56% longer** to do the same work - 3.5 s of lock against 2.25 s -
and it is the one to reach for. It makes two passes over the table and waits for every transaction
that could see it, which is precisely what buys it a lock nothing has to queue behind. A team
optimising for how long the migration takes would pick the wrong one, confidently, and the number
they optimised would be the number that does not matter.

### The pool is not what failed; it is what filled

**Sixteen backends were waiting on a lock in every one of the nine samples the `ShareLock` was
held** - not fifteen, not seventeen, and not a number that varied. `LEDGER_DB_POOL` defaults to 16.
The lock did not exhaust the pool by leaking connections; it stopped every connection that touched
`posting` from finishing, and the pool filled behind it in under 250 ms and stayed exactly full until
the lock was released.

That is visible in [`locks.txt`](../../workload/baselines/migration/blocking/locks.txt) as a step
function - `0`, `2`, `3`, `7`, then **`16` nine times running**, then `10` - and it is why the lock
samples are committed rather than summarised. A mean over that window would read as eight.

### The ledger stayed met while the bank was blocked, for the third time

The whole-day objectives, out of each capture's own `report.txt`:

| Objective, over the whole day | `CREATE INDEX` | `CREATE INDEX CONCURRENTLY` |
|---|---:|---:|
| `SLO-LEDGER-MOVEMENT-SUCCESS` | met, 1.00000 | met, 1.00000 |
| `SLO-LEDGER-POSTING-LATENCY` | **met**, 0.99321, 0.68x budget | met, 1.00000, untouched |
| `SLO-GATEWAY-AVAILABILITY` | met, 1.00000 | met, 1.00000 |
| `SLO-GATEWAY-LATENCY` | **missed, 0.88996, 11.0x budget** | met, 1.00000, untouched |

**The ledger's own latency objective was met while every writer in the bank was queued behind a table
lock**, and it was met with a third of its error budget spent. That is the same mechanism F-83
recorded for `SCN-POOL-EXHAUSTION` and the runbook now carries: `ledger_posting_latency_seconds`
times the posting the ledger performed, not the wait to get a connection to perform it in. A posting
that never started is not a slow posting - it is not a posting at all, and a ratio computed from a
component's own counters cannot fall while the component is not counting.

Two independent conditions, two packages apart, producing the same misleading signal is no longer a
coincidence. **A ledger-side latency objective is not a detector for anything that blocks the ledger
from being asked.**

### The customer sees latency, again, and never an error

`SLO-GATEWAY-AVAILABILITY` is **1.00000 in both runs**. Not one request failed while every writer in
the bank was queued behind a table lock. This is the ninth and tenth independent confirmation of what
WP-24c's seven signatures found: *every condition arrives at the edge as latency and never as
failure*. A migration that caused no 5xx is not evidence that it caused nothing, and any runbook step
of the form "check for errors after the migration" is a step that will pass while the bank is
unusable.

What it cost instead: `SLO-GATEWAY-LATENCY` at 0.85744 against a 0.99 target **over the migration
window**, which is 14.3x its error budget - and 0.88996, **11.0x the budget, over the whole day**, a
day in which the lock was held for 5% of the wall clock. The concurrent run spent none of it.

### A 2.25-second lock is a 45-second event

The blocking run's *whole-day* mean latency is **251 ms against the concurrent run's 5 ms** - fifty
times worse - and its peak in-flight count is **2 255 against 72**. The lock lasted 5% of the day and
moved the day's average by a factor of fifty, because an open-model driver goes on offering requests
at the rate the model says while nothing is being answered. The queue it built took the rest of the
day to drain, which is why p95 is 2.5 s in a run whose median request took 5 ms.

This is the shape an operator should expect and the reason a maintenance window is not the same
exercise: the cost of a lock is not its duration, it is its duration multiplied by the arrival rate.

### What this does and does not license

It licenses the claim that on **this** estate, at this volume, a plain `CREATE INDEX` over six and a
half million rows costs about two and a quarter seconds of blocked writes and fourteen error budgets,
and the concurrent form costs nothing measurable. It does not license a duration for any other
statement, any other table or any other machine - the lock modes in the table above transfer, and the
seconds do not.

It also does not license *"migrations are safe if you use CONCURRENTLY"*. `CREATE INDEX CONCURRENTLY`
is one statement of many, it can fail and leave an invalid index behind, and it cannot run inside a
transaction - which is where the next section's trap comes from.

### The trap that hangs rather than fails

**`CREATE INDEX CONCURRENTLY` under Flyway never returns unless
`flyway.postgresql.transactional.lock=false` is set.** Flyway holds its schema-history lock on a
second connection, in an open transaction, for the length of the migration; `CREATE INDEX
CONCURRENTLY` waits for every transaction that could see the table to finish, including that one.
Neither ever gives up. There is no error and no timeout, and the estate goes on serving normally
throughout - measured here, with the statement `active` on `wait_event_type = Lock` and Flyway's own
session `idle in transaction` beside it, until it was killed.

**The setting that looks like the fix is not the fix.** Flyway 9 already detects that the statement
cannot run in a transaction and runs it non-transactionally unprompted - it prints
`[non-transactional]` either way - so `executeInTransaction=false` changes nothing here and is
deliberately not shipped. A team that sets it, sees the hang persist and concludes the problem lies
elsewhere is the failure this paragraph exists to prevent. It is written up in
[`schema-change-under-traffic.md`](../runbooks/schema-change-under-traffic.md), which is a page this
repository did not have at all before WP-24b.

---

## 7. F-28 has its figures, and the retention period is still not an engineering question

**F-28 has been open since WP-09 and has never had a number.** Nothing prunes `outbox_record` or
`idempotency_record`: a dispatched outbox row and a completed idempotency record are kept forever,
because a retention sweep needs a retention period and that is a regulatory question rather than an
engineering one. `V5__idempotency.sql` anticipated it in a comment and nothing has answered it since.
This measures the cost of not answering it. It does not answer it.

> Twelve business dates, 2026-03-02 to 2026-03-17, driven back to back against **one** ledger loaded
> to 300 001 accounts and 6 616 226 postings. Scale 0.002, 720x over branch hours, seed 42, one boot
> per date with `TB_KEEP_DATA=1`. darwin arm64, 10 cores. Captured by WP-24b in
> [`soak/`](../../workload/baselines/soak/); the report is regenerable from the twelve daily scrapes
> committed beside it.

| | `outbox_record` | `idempotency_record` |
|---|---:|---:|
| At the end of day 1 | 48 786 rows, 64.9 MiB | 49 557 rows, 54.8 MiB |
| At the end of day 12 | 158 627 rows, 199.4 MiB | 166 143 rows, 210.0 MiB |
| Added over the soak | **+109 841 rows, +134.4 MiB** | **+116 586 rows, +155.2 MiB** |
| Per driven day | 8 010 rows | 8 704 rows |
| **Per posting** | **0.93 rows** | **1.01 rows** |
| Bytes per row | **1 283 B** | **1 396 B** |

**The per-posting figure is the only one that transfers.** Rows per day moves with `--scale` and
`--compress` and describes this fixture's dial; rows *per posting* is a property of the ledger. At
roughly **one row in each table per money movement, at about 1.3 kB each**, a bank posting a million
movements a day writes about **2.6 GB a day into two tables nothing ever deletes from**. That
sentence is arithmetic on a measured rate, and it is the sentence a retention policy has to be argued
against.

The report prints the same arithmetic over 250 business days - 2.0 million rows and 2.6 GiB for the
outbox, 2.2 million rows and 1.3 GiB for idempotency - in a section headed *"Extrapolation, which is
not a measurement"*, because it is not one.

### The row counts are estimates, and they went backwards twice

`ledger_db_live_tuples` is `n_live_tup` from `pg_stat_user_tables`, which the statistics collector
maintains and autovacuum corrects. `ledger_db_table_size_bytes` is `pg_table_size`, which is exact.
Presenting both as measurements would overstate half the report, so it does not.

This is not a theoretical caveat. Between two consecutive closing scrapes the estimate **fell** - by
2 rows once and by 763 rows once - in a table nothing deletes from, and between two others it rose by
19 140 in a gap where the fixture wrote a few hundred. Those are the collector revising itself, not
the ledger losing or gaining rows. Anyone reading a row count off this metric to the unit is reading
an estimate as a measurement.

### The rate is taken from inside each day, and that is a correction

A run **seeds before it drives** - it opens and funds the accounts the day will use - and the driver
takes its opening scrape after seeding. So everything the fixture writes to set a day up falls
between one day's closing scrape and the next day's opening one. A rate taken from consecutive
closing scrapes would divide row growth that includes the fixture's setup by a posting count that
excludes it, and the first version of this report did exactly that. Both figures are now taken from
each day's own two scrapes.

The trajectory row above is the other claim and deliberately still includes everything, because it is
what the table actually holds.

### Autovacuum did not run on `balance` at all, and that is the expected state

`balance` is the table every posting rewrites in place - one row per account, an `UPDATE` per leg -
so it is a pure dead-tuple generator against a fixed live count. Dead tuples climbed monotonically
across all twelve days, **4 913 to 16 187**, and `ledger_db_autovacuums` never moved.

That reads like a collector falling behind and it is not. Autovacuum triggers at
`autovacuum_vacuum_threshold + scale_factor x live_tuples`, which on this estate's defaults is
`50 + 0.2 x 336 956` - about **67 400 dead tuples**. Twelve business days reached under a quarter of
it. The collector was never asked.

The general form is worth carrying: **the default threshold scales with the whole table rather than
with the part being written**, so a large table with a small hot working set vacuums rarely, however
hard that working set is hit. `balance` is exactly that shape, and "dead tuples climbing while
nothing vacuums" is its normal rather than its incident. The report says so rather than reporting a
collector that is losing, and `ledger-observability.md` now carries the arithmetic.

### What this does and does not license

It licenses the growth rates **per posting** and the per-row sizes, which are properties of the
schema and transfer to any volume. It does not license the per-day figures anywhere except at this
fixture's dials, and it licenses nothing at all about a retention period - twelve days say nothing
about what a regulator requires to be kept.

It also does not license a claim that autovacuum keeps up on `balance`, because **the collector never
ran**. A soak long enough to cross 67 400 dead tuples would answer that question; this one is not it,
and the report says so in those words rather than reporting a healthy vacuum.

---

## 8. The overnight cycle, and what a movement file that mostly posts is worth

Every measurement above this section is of the modern spine. This one is stratum 0: the 1995 core,
driven by files and nothing else, over a day the same WP-20 model drew.

### The generator had been measuring the reject path for nineteen packages

**F-18 has been open since WP-05.** `build_movements` hard-coded `PLN` on both legs while
`build_master` drew from five currencies, and movements were drawn without regard to account status,
so on a full run **162 of 302 movements rejected and 140 posted**. Every reject was correct. What was
wrong is what the file exercised: 111 `R003` currency mismatches and 48 `R002` closed accounts meant
the cycle's happy path was thinly covered on real data and **multi-currency posting was never
exercised at all**, on any run, in any package.

The same file now posts **300 of 302**, and the two that remain are the deliberate fixtures WP-04
proves `R001` and `R004` with - an unknown account, and a JPY amount whose ISO 4217 scale of 0
`PIC S9(13)V99` cannot represent.

| Over the committed fixture, `--seed 42` | Before | After |
|---|---:|---:|
| Movements read | 302 | 302 |
| **Applied** | **140** | **300** |
| Rejected `R003`, currency mismatch | 111 | **0** |
| Rejected `R002`, account not open | 48 | **0** |
| Rejected `R001`/`R004`, the deliberate fixtures | 2 | 2 |
| Rejected `R005`, debit exceeds balance | 1 | **0** |
| Currencies in the report | 3 | 3 |

The `R005` is worth its own line. A debit that would take a `LIABILITY` account below zero is
refused, because this core has no arranged-overdraft concept at all - the master carries no limit
field. The generator now draws the amount against the debited account's **running** balance, so the
file stops containing debits the cycle was always going to refuse.

**The test that proves this does not need a COBOL compiler.** `predict_rejects` in
[`test_generate.py`](../../mainframe/data/test_generate.py) reimplements ACCTPOST's validation in the
order the program documents - `R001`, `R006`, `R004`, `R002`, `R003`, `R005` - and against the old
generator it returned **162 rejects with exactly the breakdown REJECTS.DAT contained**, before
anything was changed. A model that agrees with the real program on the failing case is a model worth
trusting on the passing one; the real cycle then confirmed it.

### A stratum-2 test was pinned byte-for-byte to what a stratum-0 generator writes

Closing F-18 broke `esb-adapter`'s build. `MovementRecordTest.aRecordMatchesOneTheGeneratorWroteByteForByte`
holds the Java COMP-3 encoder against a record `mainframe/data/comp3.py` actually wrote, found by
transfer reference and leg - deliberately the strongest check available, and it did its job: the draw
changed, transfer 20 landed on a different account, and the assertion failed at byte 36 with a
legible message.

It is a **cross-stratum coupling nothing declares**. Changing a 1995 data generator fails a 2019
integration module's build, and no document says so. The literals were re-read from the regenerated
file and the pin is now stronger than it was - the record is **EUR rather than PLN**, so the encoder
is held to a currency that is not the base one. **F-96**.

### The window at three volumes, per step

> One business date, 2026-03-02, model `TB-WORKLOAD-DAY-V1`, seed 42. A volume is customers:scale,
> both dials moving together because the master scales with one and the movement file with the other.
> darwin arm64, 10 cores. Captured by WP-25a in
> [`batch-window/`](../../workload/baselines/batch-window/); every cycle log is committed beside the
> report it was derived from.

| | 400 001 accounts | 1 200 001 accounts | 2 400 001 accounts |
|---|---:|---:|---:|
| Movements | 243 292 | 729 776 | **2 429 346** |
| Applied / rejected | 243 292 / **0** | 729 776 / **0** | 2 429 346 / **0** |
| STEP010 `SORT` | 0.102 s | 0.250 s | 0.783 s |
| **STEP020 `ACCTPOST`** | **1.184 s** | **2.450 s** | **5.402 s** |
| STEP030 `SORT` | 0.171 s | 0.442 s | 0.847 s |
| STEP040 `EODREPT` | 0.666 s | 1.308 s | 2.219 s |
| **The window** | **2.123 s** | **4.450 s** | **9.251 s** |
| of which the sort stand-in | 13% | 16% | 18% |
| Peak RSS, the cycle | 0.19 GiB | 0.52 GiB | **1.02 GiB** |
| Peak RSS, the writer | 1.26 GiB | 3.70 GiB | **5.63 GiB** |

The top row is the model's **whole declared population** - 1.2 million customers, two accounts each -
and the cycle posts a 2.4-million-movement day against a 2.4-million-account master in **nine and a
quarter seconds**, refusing nothing.

**Ten times the movements costs 4.4 times the window**, not ten, because a large part of the small
figure is fixed: compiling two COBOL programs, opening files, and the per-step timing itself. Between
the middle and top points - 3.3x the movements - the window grows 2.1x, so the marginal cost is
converging on linear rather than staying flat. Read the top two columns for the shape and the first
one for the floor.

### The step that scales is not the step that would worry an operator

`ACCTPOST` is 57% of the window at every volume and it is the step that **cannot** run out of memory:
it match-merges two already-sorted files in one pass and never holds the master. That is the property
the tier exists to demonstrate, and `CLAUDE.md` keeps a trap entry about the version that loads the
master into a table - it passes every test in this repository and destroys the point of the tier.

The sorts are the opposite. `sortrec.py` is the local stand-in for DFSORT and its own docstring says
it *"reads the whole file into a list"* and that *"nothing here should be read as evidence that the
local cycle handles a master larger than memory"*. Its share of the window climbs from 13% to 18%
across the three points, and the cycle's memory climbs with it to **1.02 GiB**. Neither figure
transfers to a mainframe: DFSORT spills to work datasets and sorts files far larger than the machine.

### The ceiling is the fixture, not the cycle

**The writer needs 5.63 GiB to prepare a day the cycle then runs in 1.02 GiB.** `generate.py
--from-stream` holds the whole day as Python objects - every account, every action, every encoded
record - before it writes a byte, so preparing the day costs five and a half times what running it
does. That is what decides how large a day can be exercised on a given machine, and it is the
fixture's own limit rather than anything about stratum 0. **F-97**; the fix is a streaming writer,
and nothing needed one until this measurement existed.

### What the file does and does not represent

**92 872 of 2 429 346 movements were posted in `PLN` rather than in the currency the model drew** -
3.8%, reported on every run. The model draws a currency per transfer from a mix of up to five and
gives each customer two accounts, so an account has no currency of its own; the stream carries a
currency on every action and none on any account. Every account is therefore opened in the base
currency and each substitution is counted, which is the convention **F-72** records WP-21
establishing against the ledger, reused rather than a second answer being invented one stratum down.
Multi-currency posting is exercised where the master genuinely draws five currencies - the committed
fixture, above - rather than pretended at volume.

Everything the stream offered is accounted for on the run's own output: transfers written, reads and
holds that are not movements, unknown accounts, and debits that would have overdrawn. A file that
omits without saying so is a file whose totals cannot be checked.

## 9. Stratum 1 answers 7 800 reads a second, and the pool everyone blames is not the ceiling

WP-25's Objective names *"a SOAP endpoint whose thread pool is smaller than anyone remembers"* as one
of the operational failures that happen where the eras meet. This tested it, and on this estate the
sentence is wrong.

> A concurrency ladder against `legacy/customer-master` deployed as a WAR on a **real Tomcat 8.5.100**
> against **real Oracle Database 23ai Free**, both booted by `workload/scripts/legacy-up.sh`. 4 000
> account references seeded from the WP-20 population and read back out of the database; 8 s per rung;
> darwin arm64, 10 cores. Captured by WP-25b in [`soap/`](../../workload/baselines/soap/), as
> `pool-default.json` and `pool-32.json`.

### The ladder

| Workers | `GetAccount` | mean | p95 | `NotifyTransferPosted` | mean |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 834/s | 0.5 ms | 0.8 ms | 1 000/s | 1.0 ms |
| 2 | 4 067/s | 0.5 ms | 0.6 ms | 2 591/s | 0.8 ms |
| 4 | 5 884/s | 0.7 ms | 0.8 ms | 3 345/s | 1.2 ms |
| 8 | 7 541/s | 1.0 ms | 1.3 ms | 3 993/s | 2.0 ms |
| 16 | 7 874/s | 2.0 ms | 2.7 ms | **4 069/s** | 3.9 ms |
| **32** | **7 886/s** | 4.0 ms | 6.4 ms | 3 959/s | 8.1 ms |
| 64 | 7 637/s | 8.4 ms | 14.5 ms | 4 001/s | 16.0 ms |

**Reads level off at about 7 900 a second from eight workers and writes at about 4 000 from sixteen**,
and **not one call failed at any level** - no refusal at the socket, no timeout, nothing unknown. Past
the knee the mean doubles every time the worker count doubles: 2.0 ms at 16, 4.0 at 32, 8.4 at 64.
That is the arithmetic of a queue in front of a resource of fixed capacity, and it is the only thing
the single run establishes.

A 2011 SOAP endpoint answering **7 900 reads a second** is worth stating plainly, because the shape of
this repository invites the assumption that the old tier is the slow one. On the same machine the
modern ledger peaks at about **790 postings a second** (section 3). The comparison is not fair - one
writes an audit chain under an advisory lock and the other reads a row - and that is the point: *the
era a component was built in predicts very little about its throughput, and the work it does predicts
almost all of it.*

### The control: the same ladder with one setting moved

Everything above answers everything, late. That rules out **Tomcat's connector** - a thread pool that
ran out would refuse at the socket rather than answer slowly - but it does not say which resource the
queue formed behind, because a datasource pool and a saturated machine produce the same shape from
outside. So the ladder was run again with `maxTotal` raised from Tomcat DBCP's default of **8** to
**32**, everything else held identical. The same shape as WP-24b's two migrations: one setting apart,
so the difference is a consequence of that setting and of nothing else.

| `GetAccount` | pool 8 (default) | pool 32 |
|---|---:|---:|
| 8 workers | 7 541/s, 1.0 ms | 7 452/s, 1.1 ms |
| 16 workers | 7 874/s, 2.0 ms | **8 775/s, 1.7 ms** |
| 32 workers | **7 886/s**, 4.0 ms | **4 439/s**, 7.2 ms |
| 64 workers | **7 637/s**, 8.4 ms | **3 833/s**, 16.7 ms |
| Worst observed latency at 64 | **34.7 ms** | **682.9 ms** |

**Four times the connections buys about 11% at sixteen workers and then loses half the throughput at
sixty-four.** 7 637/s becomes 3 833/s, the mean doubles from 8.4 ms to 16.7 ms, and the worst case a
customer would see goes from 35 ms to **683 ms**. `NotifyTransferPosted` does the same: 4 001/s
becomes 2 656/s at 64 workers.

So the datasource pool is **not** the ceiling. What is left is the machine - Oracle in a container, a
JDK 8 Tomcat and the driver itself on ten cores - and past that point more connections means more
contention rather than more work. The narrow win at sixteen is real and it is the trap: a team that
measured only at their current concurrency would raise the pool, see an improvement, and ship a change
that halves throughput the first time the tier is genuinely busy.

### Why this matters more than the number

**The premise was tested rather than assumed, and it failed.** "The pool is too small" is the first
thing anyone says about a tier like this, it is cheap to act on, and acting on it here **halves the
throughput** at the concurrency that matters. The 683 ms worst case in the right-hand column is what
that looks like from a customer's side, against 35 ms with the setting left alone.

It also does not license the opposite claim. This says the pool is not the constraint **on this
machine at this volume with this working set** - 4 000 accounts, every one of them in Oracle's buffer
cache. A master that does not fit in cache would put I/O wait behind every borrowed connection, and
the pool could matter again. The lock modes and the shape transfer; the seconds do not.

### What the driver caught about itself, twice

The write path faulted on **every call** in its first two runs, and both times the estate was right.

`SAME_ACCOUNT` first: the driver named one account on both legs, and a transfer from an account to
itself is not a transfer. Then `ORA-02290: APPLIED_TRANSFER_REF_CK violated`: the driver invented a
`WL`-prefixed transfer reference where `TransferRefType` declares `TB[0-9]{18}` and the Oracle schema
enforces the same pattern a second time. **The contract and the database agreed with each other
against the driver**, which is what both are for - and a ladder of 167 731 faults would otherwise have
been reported as a throughput figure. The report now refuses to print one when faults dominate, which
is the control that finding produced.

## 10. What changed because of these numbers

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
- **F-28 has figures and stays open.** One row in each unpruned table per money movement, at about
  1.3 kB each. The retention period is still a regulatory question, and WP-24b did not invent one.
- **This repository has a schema-change procedure for the first time**, and every claim on it was
  measured rather than reasoned about -
  [`schema-change-under-traffic.md`](../runbooks/schema-change-under-traffic.md).
- **F-89 was confirmed from the other side.** The soak's second day reported `funded 37359,
  replayed 0` while the ledger wrote 417 new idempotency rows. The fundings were replays, correctly -
  a funding key is `wl-funding-<accountRef>` and an account is funded once - and the driver cannot
  see it because the gateway strips the header. The table growth is the cross-check seeding lacked.
- **F-88 has produced a wrong number in a committed document.** `workload/baselines/README.md`
  claimed `with-broker`'s dataset digest was `35747263` while the manifest committed beside it says
  `04f66dda`. Five loads at identical flags have now produced four distinct digests, with
  `rowsWritten`, `chainLength` and `chainHead` identical in every one.
- **Four new findings.** The catalogue digest's granularity (**F-91**), no `lock_timeout` anywhere
  (**F-92**), no requirement about data growth or a change procedure (**F-93**), and the Flyway image
  being amd64-only (**F-94**).
- **F-18 is closed after nineteen packages.** The synthetic movement file posts 300 of 302 rather
  than 140, and multi-currency posting is exercised for the first time on any run in this repository.
- **The overnight cycle has a number.** 2.4 million movements against a 2.4 million account master in
  **9.25 s**, refusing nothing, with `ACCTPOST` 57% of it at every volume - the step that streams.
- **Three new findings.** A stratum-2 test pinned to stratum-0 bytes with nothing declaring it
  (**F-96**), the volume writer needing 5.6 GiB to prepare a day the cycle runs in 1.0 GiB
  (**F-97**), and the dataset stream not carrying the opening balance the driver funds with, which is
  what a reconciliation across the two would need (**F-98**).
- **Stratum 1 has a number, and it is 7 900 reads a second.** A 2011 JAX-WS endpoint on Tomcat 8.5
  against real Oracle, ten times the modern ledger's posting rate on the same machine - because the
  ledger writes an audit chain under an advisory lock and this reads a row.
- **"The pool is too small" was tested and is false here.** Four times the connections buys 11% at
  sixteen workers and **halves the throughput at sixty-four**, with the worst observed latency going
  from 35 ms to 683 ms. The narrow win at low concurrency is the trap: it is exactly what a team
  would measure before shipping the change.
- **Two more findings.** Seeding stratum 1 cannot be done without manufacturing personal data or
  filling the columns with a marker, because the 2011 schema requires an identity (**F-99**), and
  `legacy-up.sh` cannot ask Tomcat what its pools are actually doing, because the WAR exposes no
  metrics at all (**F-100**).
- **F-98 is closed and two more defects were found closing it.** The opening balance travels with the
  day; the emitter draws the date the driver draws (**F-102**); and a driven day is dated by the day
  it drives rather than by the machine's clock (**F-103**). All three were invisible until something
  compared the ledger against a stratum-0 master, and each on its own made the reconciliation useless.
- **The two cores agree, at eighty thousand accounts and zero tolerance.** 80 001 compared, 80 001
  matched, total absolute drift zero.
- **Two new findings.** A capture and a reversal cannot be dated by their caller at all, so the
  reconciliation agreed about them only because the business date was in the past (**F-104**), and
  stratum 0's `ACCT-BOOKED-BAL` caps the master at 99 999 accounts at the opening figure the model
  implies (**F-101**).

## 11. One day, both phases, and a reconciliation that matched every account

Every measurement in sections 1 to 10 stops at a boundary. The online day stops at the edge; the
batch window stops at the movement file; the SOAP ladder stops at Tomcat. This one crosses: the
online phase runs against the live estate, the cut-off ends it, the COBOL cycle applies the same day
to the account master, and `batch/recon` compares the master that cycle produced against the ledger
that fed it. It is the first run in this repository where the two cores are held against each other
under a day's worth of load rather than under the single transfer WP-16 built the control with.

> One business day driven end to end by `workload/scripts/two-phase-day.sh`. 40 000 customers,
> **80 001 accounts**, scale 0.002, 720x, seed 42, business date 2026-03-02 reconciled on 2026-03-03.
> darwin arm64, 10 cores. PostgreSQL 16, GnuCOBOL, JDK 17. Captured in
> [`two-phase-day/`](../../workload/baselines/two-phase-day/).

### What the run did

| Phase | Window | What happened | Elapsed |
|---|---|---|---|
| Seeding | before the day | 80 001 accounts opened, 80 000 funded, 0 replayed | 4 min 21 s |
| Online | minute 0 to 1 200 | 44 767 scheduled, 44 762 sent, 12 007 money movements, 0 failed | 1 min 40 s |
| Movement file | at the cut-off | 10 877 transfers as 21 754 records, 80 001 master records | - |
| `STEP010` SORT | overnight-batch | 21 754 movements into account-reference order | 0.037 s |
| `STEP020` ACCTPOST | overnight-batch | 21 754 read, **21 754 applied, 0 rejected**, BALANCED | 0.465 s |
| `STEP030` SORT | overnight-batch | 80 001 records into currency order | 0.057 s |
| `STEP040` EODREPT | overnight-batch | 80 001 accounts, 1 currency, 0 rejected, BALANCED | 0.411 s |
| `recon` | morning-reconciliation | **80 001 compared, 80 001 matched, 0 broken** | - |

**Total absolute drift: zero minor units.** Not one account of eighty thousand disagreed, and the
break report is committed with an empty `breaks` array rather than summarised.

**A run that reconciles exactly is a stronger claim than one that was made to**, and it is worth
saying what was *not* done to get it: no tolerance, no window, no account excluded, and the
comparison is the real `compare` against a master a real `ACCTPOST` wrote. `batch/recon` sums
balances from postings rather than reading the `balance` table, so the figure it checked is not the
ledger's own cache of the answer.

### Three defects stood between the two halves, and each one made the control useless

None of them was visible until something compared the two sides. Each produced a reconciliation that
ran, exited 0, and measured nothing.

**F-98 - three components, three opening balances.** The driver funded twenty times the largest
transfer the model can draw, `services/ledger-loader` loaded two hundred times a cohort median, and
`mainframe/data/generate.py` wrote a constant. The stream carried none of them, so nothing built from
it could agree with the driver about a single account. The figure now travels in the header as
`openingBalanceMinor` and every consumer reads it.

**F-102 - the two halves drew different days from the same seed.** `internal/dataset` derives a
per-date seed so that a year of dates does not give one small cast of accounts every day's history;
`cmd/workload-run` drives one date and draws it from the run seed itself. Pointed at the same date
they disagree about every event in it, and the first run that compared them said so out loud: the
driver scheduled **17 658** actions and the stream, bounded identically, drew **17 871**. Neither
number was wrong. They were different days. `--driver-seed` makes the emitter draw the date the way
the driver draws it, and the counts then agree exactly.

**F-103 - a driven day was dated by the machine's clock.** The ledger defaults `valueDate` to
`LocalDate.now` when a request omits one, and this driver omitted it while already holding the
business date it mints references and idempotency keys from. A run of 2026-03-02 therefore wrote
journal entries dated **2026-08-22**, and a reconciliation asking the ledger for the business date's
postings got *none of them* - 80 001 accounts, every one `VALUE_DRIFT`, total drift 200 000 000 000
000 minor units, which is the whole bank. The field is in `contracts/openapi/ledger-core.yaml`
already, optional, described as *"defaults to the current business date when omitted"*. Nothing had
to change but the sending of it. Seeding is dated the day **before** the run, which is the rule
`services/ledger-loader` already follows in `Header.openingDate`: an opening balance is the position
the day starts from, not part of it.

### The arithmetic closes, and the part that does not reach stratum 0 is the interesting part

The driver posted **12 007** money movements and the movement file carries **10 877** transfers. The
difference is not a loss, and the ledger accounts for all of it:

| | Count | Reaches the movement file |
|---|---:|---|
| `createTransfer` | 10 877 | yes, two legs each |
| `placeHold` | 603 | no - reserves, posts nothing |
| `releaseHold` | 355 | no - releases, posts nothing |
| `captureHold` | 161 | **no, and it posts** |
| `reverseTransfer` | 11 | **no, and it posts** |
| **Total** | **12 007** | |

`MOVEREC` has one shape - a debit and a credit sharing a transfer reference - so a hold transition
has no record to be written as, and `generate.py` counts each rather than dropping it. That is
correct and deliberate. But **172 of those movements did post to the ledger and never reached the
master**, and the reconciliation agreed anyway. That is ADR 0015's two-figure design working: a
posting counts towards what the master *ought* to hold only when its reference is in the movement
file or its value date precedes the business date, so a capture is timing rather than drift.

### The reconciliation agreed about the captures for the wrong reason

**F-104.** `CaptureRequest` and `ReversalRequest` declare no `valueDate` and are both
`additionalProperties: false`, so a caller *cannot* date them - F-103 was only fixable for transfers
and funding. The 161 captures and 11 reversals therefore carry the ledger's own clock, 2026-08-22,
and the reconciliation excluded them from **both** of its figures because its query bounds everything
at `value_date <= business_date`. They agreed because the business date is in the past. Drive a
business date later than today - which nothing forbids and `--date` makes trivial - and the same 172
entries land inside the bound on one side only, and read as drift. The exercise measured the good
case; the bad one is one flag away.

### What stratum 0 costs when the population is capped by its own record

**F-101.** The treasury carries one leg of every funding, so its balance is the account count times
the opening figure, and `ACCT-BOOKED-BAL` is `PIC S9(13)V99 COMP-3` - fifteen digits. At
`seeding.Opening`'s figure of 10 000 000 000 minor units the master tops out at **99 999 accounts**:

| Accounts | Treasury contra | Fits `S9(13)V99` |
|---:|---:|---|
| 99 999 | 999 990 000 000 000 | yes |
| 100 000 | 1 000 000 000 000 000 | **no** |
| 400 000 | 4 000 000 000 000 000 | **no** - WP-25a's low volume |
| 2 400 001 | 24 000 010 000 000 000 | **no** - WP-25a's top volume |

No per-account figure derived from the largest drawable transfer fits at the top volume at all: 2.4
million accounts leave **4 166.66** each, which is a thousandth of the 5 000 000.00 a corporate
transfer can draw. **The model's amount distribution and a 1995 record's field width are coupled, and
nothing in this repository declared it.** `generate.py` now refuses past the ceiling naming the
arithmetic rather than letting `encode_comp3` raise eight frames down with an integer, and
`batch-window.sh` passes `--opening-balance` so WP-25a's three volumes stay reproducible - reported
as a substitution on every run that makes it, the way currency substitutions already are under F-72.

## 12. The four-era hop, and the write to 1995 that gets dearer all day

Everything before this drives one tier, or one boundary between two. This is the only exercise here
that needs **every stratum up at once** - PostgreSQL, Kafka, the ledger and the gateway from
`estate-up.sh`, Oracle and Tomcat 8.5 from `legacy-up.sh`, and `integration/esb-adapter` running
between them on JDK 8 - and it drives the path the master plan names as the reason the repository
exists: a Kafka event becomes canonical XML by XSLT, a SOAP call to a 2011 monolith, and a COMP-3
record in a fixed-width file for 1995. `FourEraTransferIT` walks that path once, with one transfer.
This walked it **24 023 times**.

> One day driven by `workload/scripts/four-era-day.sh`. 8 000 customers, **16 001 accounts**, scale
> 0.002, 720x, `branch-hours`, seed 42, business date 2026-03-02. One partition, listener concurrency
> 1, outbox relay 100 rows / 500 ms. PostgreSQL 16, Kafka 7.6.1, Oracle 23ai Free, Tomcat 8.5.100,
> JDK 8 and JDK 17, darwin arm64, 10 cores, Docker given 8 GiB - of which Oracle took 2.2 and nothing
> was starved. Captured in [`four-era/`](../../workload/baselines/four-era/).

### The arithmetic closes to the record

The ledger's own counters, the topic, the movement file and Oracle all agree, and the agreement is
what makes everything below readable:

| | Count | Publishes `TransferPosted` | Reaches the movement file |
|---|---:|---|---|
| `transfer` - seeding's funding | 16 000 | yes | yes |
| `transfer` - the day's own | 7 895 | yes | yes |
| `hold.capture` | 122 | yes | yes |
| `reversal` | 6 | yes | yes |
| `hold.place` | 438 | no - reserves, posts nothing | no |
| `hold.release` | 245 | no - releases, posts nothing | no |
| **money movements** | **24 706** | | |
| **events published, and crossed** | | **24 023** | **24 023** |

24 023 transfers, 48 046 movement records at 120 bytes each, and **not one redelivery and not one
dead letter**. The relay had drained before the driver stopped - `ledger_outbox_pending` zero, which
`estate-up.sh` asserts rather than assumes - so every event was on the topic before the hop's own
backlog was measured.

### Three legs, and the report says which one is a measurement

The adapter publishes nothing about itself: no actuator, no Micrometer, not even a web starter to put
an endpoint on. That is **F-100's situation one stratum up**, and it gets F-100's answer - observe it
from outside rather than modernise a Boot 2.7 component to make it measurable. What it does have is
two INFO lines per transfer that WP-11b wrote for operators, and they bracket the one step nobody had
timed.

| Leg | Count | Mean | p95 | Max | What it is |
|---|---:|---:|---:|---:|---|
| **file** | 24 023 | **8.2 ms** | 15.0 ms | 38.0 ms | measured directly between two logged instants |
| inbound | 24 022 | 2.9 ms | 5.0 ms | 199.0 ms | a **difference**, not a measurement |
| service | 24 022 | 11.0 ms | 18.0 ms | 200.0 ms | one crossing to the next |

**Only the file leg is measured.** Nothing is logged when a message is picked up, so the inbound leg
is one transfer's `carried to the system of record` minus the previous one's `crossed to stratum 0` -
it holds the poll, the XSLT transform, the schema validation, the JAXB unmarshal *and* the SOAP call.
Reporting it as "SOAP latency" would be a plausible number for something nobody measured, so the
capture carries what each leg contains in a field beside the figure.

### The write to 1995 gets dearer all day, and it is the only part that does

| Transfers | File leg | Service | Per second |
|---|---:|---:|---:|
| 1 - 2 402 | 1.1 ms | 5.9 ms | 169.5 |
| 4 805 - 7 206 | 4.3 ms | 7.0 ms | 142.9 |
| 9 609 - 12 010 | 7.6 ms | 10.3 ms | 97.1 |
| 14 413 - 16 814 | 10.4 ms | 12.7 ms | 78.7 |
| 19 217 - 21 618 | 13.5 ms | 16.0 ms | 62.5 |
| 21 619 - 24 023 | **15.0 ms** | 17.6 ms | **56.8** |

**Appending got 13.6 times dearer across one day and the throughput fell by two thirds** - 169.5
transfers a second to 56.8 - while the inbound leg, the half that includes the SOAP call to Tomcat,
stayed flat at about 3 ms. The rise is close to a straight line: about 1.5 ms per 2 400 transfers,
which is roughly 0.3 microseconds for every record already in the file.

That is `MovementFileWriter` doing exactly what it says it does. Before appending it looks for the
transfer reference among the records already there, twenty bytes at a time, one positional read per
record, under an exclusive file lock, and forces the result to disk. **The cost of writing transfer
*n* is proportional to *n*** - and its own javadoc had already said so: *"Linear in the size of the
file, which is correct for a bank day's worth of records and unmeasured for anything larger."* This
is that measurement, and at a bank day's worth the linear scan is already the dominant cost of
crossing four decades.

It is not a defect, and that is the point worth keeping. The scan is what makes the file its own
unique constraint ([ADR 0014](../governance/adr/0014-the-movement-file-is-its-own-unique-constraint.md)),
which is what makes at-least-once delivery safe without a second source of truth about the bank's
money. **The estate pays for that guarantee in a cost that grows all day**, and nothing had priced it
until every stratum was up at once.

### Where the backlog formed, and the control that says the broker was not it

`fraud-scoring` consumes the same topic in its own group, so it is the control: if only one consumer
falls behind, the broker is not what is slow.

| | Peak lag | At | Closing |
|---|---:|---:|---:|
| `esb-adapter` | **7 983** | 263 s | 0 |
| `fraud-scoring` | 59 | - | 0 |

The backlog formed at the adapter and drained there. It could not have formed anywhere else: the
topic has **one partition**, the listener declares **no concurrency**, and the SOAP call is
synchronous - so the whole four-era hop is a single thread, whatever the tiers on either side of it
can do. The ledger posted its 24 706 movements in 45 seconds of wall clock; the hop took 396 seconds
to carry 24 023 of them across.

### The constraint held under load

*Nothing is written to the movement file unless the SOAP call succeeded* is WP-11b's rule, enforced
structurally by statement order in `TransferBridge` and pinned by three unit tests. This is the first
time it has been asked of a day rather than of a transfer:

```
  movement records                    48046
  distinct transfers in the file      24023
  transfers the system of record has  24023
  in the file, not in the master      0
  in the master, not yet in the file  0
```

Zero in the file that 2011 had not accepted first, which is the direction that does not recover: a
record with no transfer behind it is 1995 believing a payment that never happened.

### Getting there cost two runs, and the estate refused the first one outright

**F-105 - three components date an opening balance by three correct rules, and stratum 1's schema
makes them jointly impossible.** `internal/client.Fund` dates the opening credit the day *before* the
run, and `services/ledger-loader`'s `Header.openingDate()` independently returns `from.minusDays(1)`,
both because an opening balance is the position the day starts from rather than part of it - F-103's
rule. `workload-legacy-seed` opened stratum-1 accounts on the business date itself. `customer-master`
then declares `CHECK (last_movement_date IS NULL OR last_movement_date >= opened_date)`, so **every
funding posting was refused `ORA-02290` by a 2011 check constraint**. Each rule is right on its own.
Nothing had ever run them against each other, because nothing had driven the ledger's own events into
stratum 1.

**F-106 - and the refusal never reached the dead-letter path, because both components were behaving
as designed.** `CustomerMasterEndpoint` deliberately lets a `DataAccessException` become a generic
SOAP server fault rather than the WSDL's declared `ServiceFault`, and says why: *"a caller that
cannot tell 'your request was wrong' from 'we are broken' retries the first and gives up on the
second."* `CustomerMasterClient` implements exactly that reading - declared fault permanent,
`WebServiceException` transient. **The hole is where the database raises the data error rather than
the application.** A check-constraint violation is technical by exception type and permanent in fact,
so the message was never acknowledged, Spring Kafka's default `FixedBackOff(0L, 9)` retried it with
no backoff at all, the partition blocked behind it by design because ordering is what that buys, and
**nothing was ever dead-lettered** - the one signal an operator would look for stayed silent. The
estate has no poison-message escape at the era boundary.

### What this does and does not license

It licenses saying that **the era boundary is the narrow part of this estate, and the narrow part of
the era boundary is the write to 1995 rather than the call to 2011.** It licenses saying the cost of
that write grows with the day, linearly, and why.

It does not license a throughput figure for `esb-adapter` in general. One partition and one consumer
thread is this fixture's shape as much as the estate's, and the shared `CustomerMasterPortType` is
not thread-safe, so raising either is not a one-line change and was not attempted here. It does not
license reading the inbound leg as SOAP latency. And it says nothing about what happens when the file
is larger than a day: this measurement stops at 48 046 records because that is what a day produced,
and the scan that costs 15 ms at that size is the same scan.

## Reproducing

```bash
# The batch window. No Docker and no database - stratum 0 is driven by files and files only.
bash workload/scripts/batch-window.sh

# Stratum 1. Boots Oracle and a real Tomcat 8.5 with the WAR, then walks the ladder.
bash workload/scripts/legacy-up.sh --keep --customers 2000 --accounts 4000
go -C workload run ./cmd/workload-soap \
  --accounts "${TMPDIR:-/tmp}/tessera-legacy/accounts.txt" \
  --levels 1,2,4,8,16,32,64 --duration 8s --writes

bash workload/scripts/ceiling.sh --levels 1,2,4,8,16,32,64 --duration 10s

bash workload/scripts/baseline.sh --out-name spine-only \
  --customers 20000 --from 2026-06-01 --to 2026-06-15 --date 2026-08-21

bash workload/scripts/baseline.sh --out-name with-broker \
  --customers 150000 --from 2025-09-01 --to 2026-08-21 --date 2026-08-21

bash workload/scripts/signatures.sh --baseline with-broker

bash workload/scripts/migration.sh --baseline with-broker --variant both

bash workload/scripts/soak.sh --days 12

# One day, both phases, and the reconciliation between them.
bash workload/scripts/two-phase-day.sh

# All four eras at once: Kafka in, XSLT, SOAP to Tomcat 8.5, COMP-3 out. The heaviest thing here.
bash workload/scripts/four-era-day.sh --customers 8000
```

All of them need Docker, a JDK 17 and Go; every one from the baselines down also needs uv,
for the scorer. `--date` is pinned
because it has to be: left to the fixture it is today, and today has a weekday multiplier - a Friday
is 1.2 and a Saturday 0.45, so the same command run at the weekend produces a third of the demand and
a diff nobody should read as a regression. The `with-broker` capture takes about twelve minutes, most
of it the load.

`migration.sh` runs against the database a load left behind, the way `signatures.sh` does, and takes
about six minutes for the first variant and four for the second - the difference is seeding, which
opens 42 769 accounts the first time and finds them already open the second. `soak.sh` loads its own
ledger and then drives 1 min 45 s per business date, so twelve dates is a little over half an hour
with the load. **Do not edit either script while it is running**: bash reads a script incrementally,
and an edit that shifts byte offsets under a running interpreter makes it resume mid-token. That is
how WP-24b lost the report step of an otherwise complete soak, and re-running the report over the
twelve committed captures was the whole of the repair.

`four-era-day.sh` is the only one that needs **a JDK 8 as well as a JDK 17**, and the WAR and the
adapter jar built (`make build-legacy build-integration`). It boots four containers and four
processes and takes about seven minutes, most of it Oracle starting and 16 001 accounts being seeded
over sqlplus. **It refuses to start while any container it does not own is running**, naming them,
because Oracle alone wants 2.2 GiB and it will not remove something it did not start - stop them
first. It never writes to `mainframe/data/out/`, and the adapter's own log is left in the work
directory rather than committed: it is three lines per transfer and twelve megabytes for this run.

`signatures.sh` runs against the database `baseline.sh` leaves behind, and it is **single-use against
it**: its seven business dates are pinned, so a second sweep over the same ledger replays instead of
posting and measures the replay path. Load the ledger again between sweeps. The driver refuses such a
run rather than reporting it, which is what section 5 is about.
