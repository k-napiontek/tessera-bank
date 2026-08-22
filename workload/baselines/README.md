# workload/baselines - what this estate actually did

Committed measurements, each carrying the conditions it was taken under.

### One directory per capture

A baseline is not a file, it is a set of conditions and what the estate did under them. Writing a
second capture over the first would leave one report and two sets of conditions, so each gets its
own directory and `baseline.sh` requires `--out-name` rather than defaulting to one.

| Directory | What the estate was, when it was measured |
|---|---|
| [`spine-only/`](spine-only/) | PostgreSQL, the ledger and the gateway. No broker, so `fraud-scoring` never ran and the outbox relay had nowhere to publish. Captured by WP-23 at 20 000 customers over a fortnight |
| [`with-broker/`](with-broker/) | The same, plus Kafka, `edge/fraud-scoring` and the controllable hop. Captured by WP-24a at 150 000 customers over 355 dates - 300 001 accounts, 14 491 832 rows |
| [`signatures/`](signatures/) | The same estate, degraded on purpose. One directory per condition in `TB-SCENARIOS-V1`, each diffed against `with-broker/`. Captured by WP-24c |
| [`migration/`](migration/) | The same estate, with a schema migration applied **while the day was running**. One directory per variant, read against each other. Captured by WP-24b |
| [`soak/`](soak/) | The same day over twelve business dates against one ledger, to measure what nothing prunes. Captured by WP-24b |

Every capture directory holds the same seven files. `migration/` adds four of its own and `soak/`
keeps a subset, and each says below why:

| File | What it is |
|---|---|
| `report.txt` | The run against the catalogue: which objectives were met |
| `run-manifest.json` | The run: model digest, seed, both dials, window, commit, hardware |
| `dataset-manifest.json` | The ledger it ran against: dataset digest, chain head, row counts |
| `before.prom`, `after.prom` | The ledger's scrapes, taken where the run brackets itself |
| `before-edge.prom`, `after-edge.prom` | The gateway's, at the same two instants |
| `before-fraud.prom`, `after-fraud.prom` | The scorer's, where there was one to scrape |

Produced by [`../scripts/baseline.sh`](../scripts/baseline.sh). The scrapes are committed because
without them the report cannot be regenerated, and a report nobody can regenerate is one nobody can
check:

```bash
# go -C runs with workload/ as the working directory, so the paths below are relative to it.
go -C workload run ./cmd/workload-report \
  --manifest baselines/spine-only/run-manifest.json \
  --before baselines/spine-only/before.prom --before baselines/spine-only/before-edge.prom \
  --after baselines/spine-only/after.prom --after baselines/spine-only/after-edge.prom \
  --catalogue ../contracts/slo/tessera-slo-v1.json \
  | diff - workload/baselines/spine-only/report.txt && echo "identical"
```

The two `ceiling-*.json` files below sit at the top level rather than in a capture directory. They
are not a bank day and there is no baseline to diff them against: they answer where throughput stops
rising, which is a property of the design rather than a normal to compare a run with.

### The seven signatures

`signatures/<SCN-ID>/` holds the same seven files as a capture directory above, and its `report.txt`
carries one extra section: the **Signature**, which judges the run against what the scenario declared
*before* it - the objectives it said would move, the objectives it said would stay flat, and where
each one actually ended. A verdict reads `as declared`, `CONTRADICTED`, or `inconclusive` when this
run could not answer the question at all.

Produced by [`../scripts/signatures.sh`](../scripts/signatures.sh), which runs against the database
[`../scripts/baseline.sh`](../scripts/baseline.sh) leaves behind. **It is single-use against that
database.** Its seven business dates are pinned, so a second sweep over the same ledger replays every
request instead of posting it - and a run in which nothing was posted writes no journal entry, no
outbox row, publishes nothing and scores nothing, which reads as an estate full of dead components.
Load the ledger again between sweeps. `workload-run --require-postings` refuses such a run rather
than reporting it; F-86 is what happens without that control.

### The throughput ceiling

| File | What it measures |
|---|---|
| [`ceiling-one-instance.json`](ceiling-one-instance.json) | Money-moving throughput against one ledger, at rising concurrency |
| [`ceiling-two-instances.json`](ceiling-two-instances.json) | The same ladder against two ledgers sharing one PostgreSQL |

Produced by [`../scripts/ceiling.sh`](../scripts/ceiling.sh), which drives
[`../cmd/workload-ceiling`](../cmd/workload-ceiling).

## A measurement names its conditions or it is worthless

Every file here carries a `conditions` block: how many ledger instances, the concurrency ladder, how
long each level was held, the connection pool's ceiling, how accounts were allocated, the hardware
and the commit. Two measurements that do not state their conditions cannot be compared, and
comparing them anyway is how a team concludes a regression exists.

The figures below were taken on a developer machine, not on anything resembling production
hardware. What they establish is a **shape** - where throughput stops rising, and what stops it -
and that shape is a property of the design rather than of the laptop.

## What `spine-only/` does not cover, and says so

When it was captured, `estate-up.sh` booted the modern spine - PostgreSQL, the ledger and the
gateway - and nothing else. So it is a recorded normal for two of the five components in the
catalogue, and the report prints `nothing happened` against the other three rather than quietly
leaving them out:

- **`fraud-scoring` was not running.** It consumes from Kafka and there is no broker in this fixture.
- **`reporting` and `recon` are batch jobs.** Neither ran during the window, and their metrics go to
  a node_exporter textfile rather than to an endpoint anything scraped here.
- **`SLO-LEDGER-OUTBOX-FRESHNESS` is outside its objective in this capture** - the lag was 140 s at
  the start and 185 s at the end against a 60 s target. That is the fixture rather than the ledger:
  with no broker to publish to, the relay cannot drain, so the oldest unpublished event only ages.
  It is left in the report rather than suppressed, because a baseline that hides the signals it
  could not exercise is a baseline that will be quoted as though it had.

**WP-24a's fixture boots a broker and the scorer**, so a capture taken after it is a capture of a
different estate. That is why this one is kept rather than replaced: the difference between the two
is itself a finding, and a signature diffed against a normal that records a missed outbox objective
would credit the injection with a failure the fixture had all along.

`run-manifest.json` here says `hardware: unrecorded` because it was written before the manifest had
the field. That is the honest answer rather than a machine name reconstructed afterwards.

## The dataset digest is the one field here that does not reproduce

This table used to say `with-broker`'s dataset digest was `35747263`, and
[`with-broker/dataset-manifest.json`](with-broker/dataset-manifest.json) beside it says `04f66dda`.
Both were written by a real load at the same commit with the same flags; the sweep had to be run
against a freshly loaded ledger (F-86), and the second load produced a different digest from the
first. **F-88.**

WP-24b loaded it twice more, at the same flags again, and got `cafa90ad` and `35747263`. **Five
loads at identical flags have now produced four distinct digests** - `35747263`, `04f66dda`,
`37ef7dc3` (F-88's), `cafa90ad`, and `35747263` again. In every one of them `rowsWritten` was
**14 491 832**, `chainLength` was **3 804 955** and `chainHead` was **`0a993c8a`**, identically.

Two things follow. The **audit chain head is the field that names a ledger**, and it is the stronger
statement anyway - it is computed by the ledger's own controls and verified end to end rather than by
the loader's arithmetic. And the fourth value repeating a value seen months earlier says the digest
is not a per-run random number but one of a **limited set of outcomes** - which is a much narrower
thing to go looking for than F-88 originally implied, and a hint that what varies is an ordering
among a few writers rather than the data itself.

## What `with-broker/` covers that `spine-only/` did not

Both are a Friday, both at scale 0.002 and 720x over branch hours, both seed 42. What differs is the
estate and the ledger underneath it, and the two are stated here so that the pair can be compared
rather than merely placed side by side.

| | `spine-only/` | `with-broker/` |
|---|---|---|
| Business date | 2026-08-21 | 2026-08-21 |
| Components running | 2 of 5 in the catalogue | 3 of 5 |
| Ledger | 40 001 accounts, 799 565 rows | **300 001 accounts, 14 491 832 rows** |
| Dataset digest | `747f4177` | `04f66dda` (see below) |
| Hardware | unrecorded | darwin arm64, 10 cores, go1.25.6 |
| `SLO-LEDGER-OUTBOX-FRESHNESS` | **missed** - lag 140 s then 185 s | **met** - lag 53 s then **0** |
| `fraud-scoring` | `nothing happened` | 21 483 events, both objectives met |

Three things worth reading out of that.

**The scorer's two objectives have figures for the first time**, which is the broker half of
**F-77** closed. It consumed 21 483 events and published a decision for every one of them.

**The outbox objective was the fixture, and now it is not.** With no broker the relay could not drain
at all and the lag only aged; with one it clears. What replaced it is a real number rather than an
absence: the relay published the run's last **10 883 events in 1 minute 2 seconds** after a
45-second day. It ships at most `LEDGER_OUTBOX_BATCH` rows every `LEDGER_OUTBOX_INTERVAL_MS` - 100
every 500 ms by default - and **that tick does not move with the compression dial**, so a day
replayed at 720x hands the relay money movements roughly seven hundred times faster than the bank
ever would. The lag of 53 s in the opening scrape is the same effect from seeding.

**`reporting` and `recon` still print `nothing happened`.** They are batch jobs and do not run inside
a compressed nine-hour window. That is the half of F-77 WP-24a does not close, and it stays open.

## What `migration/` and `soak/` are, and why they are not baselines

Neither is a recorded normal. A baseline says what the estate does when nothing is wrong; these two
say what it does while something specific is being done to it, and they are read against each other
rather than against a diff.

**`migration/blocking/` and `migration/concurrent/`** are the same index applied to a live ledger
mid-day, once with `CREATE INDEX` and once with `CREATE INDEX CONCURRENTLY`. Everything else about
the two runs is identical, so whatever differs between the captures is a consequence of that one
keyword - which is what makes either lock duration mean anything. Each holds the seven files above
plus four of its own:

| File | What it is |
|---|---|
| `migration.json` | The statement, Flyway's own account of applying it, how long it took, and the lock summary |
| `locks.txt` | `pg_locks` sampled every 250 ms **while the lock was held**, one line per reading |
| `before-edge-migration.prom`, `after-edge-migration.prom` | The gateway, bracketing the **migration** rather than the run |
| `before-ledger-migration.prom`, `after-ledger-migration.prom` | The ledger, at the same two instants |

The two extra scrape pairs are the point of the capture. The run's own brackets cover a whole
compressed day, and a lock held for seconds averaged across nine business hours reads as nothing at
all.

**`soak/`** is the same day driven over twelve business dates against one ledger, with `day-01/` to
`day-12/` each holding that day's manifest and the ledger's two scrapes. It measures how the tables
nothing prunes grow - **F-28** - and nothing else, which is why the gateway's and the scorer's
scrapes are not kept: twenty-four more files nothing reads would be noise in a directory whose value
is that every file in it is evidence of something.

Produced by [`../scripts/migration.sh`](../scripts/migration.sh) and
[`../scripts/soak.sh`](../scripts/soak.sh). Both are captured by WP-24b.

## What `batch-window/` is, and why it has no manifest

`batch-window/` is stratum 0 and shares nothing with the captures above. There is no run manifest, no
scrape pair and no SLO report, because there is no estate: the cycle is four programs over two files,
started and finished before anything could be scraped. What is committed is the **job log of each
run** - `cycle-<customers>.txt`, carrying `ACCTPOST`'s own control totals and the elapsed time each
step reported - and the **generator's own accounting**, `generate-<customers>.txt`, which says how
many of the stream's actions became movements and why the rest did not.

`report.txt` is derived from those and nothing else, which is why they are committed rather than
summarised. The three volumes are customers:scale pairs, both dials moving together because the
master scales with one and the movement file with the other; the top point is the model's whole
declared population.

Two numbers on that page are properties of this fixture rather than of the tier, and the report says
so on its own last lines: the share of the window spent in `sortrec.py`, which holds files in memory
where DFSORT spills to work datasets, and the writer's peak memory, which is larger than the cycle's.

## What `soap/` is, and why there are two of them

`soap/` is stratum 1 and, like `batch-window/`, shares nothing with the captures above: no run
manifest, no scrape pair and no SLO report, because `customer-master` exposes **no metrics at all** -
no endpoint, and Tomcat's manager application is not deployed. Everything in these two files was
observed from outside, by the driver, which is why the pool question needed a second run rather than
a scrape.

That is what the two files are. `pool-default.json` is Tomcat DBCP's own default of `maxTotal=8`;
`pool-32.json` is the identical ladder with that one setting raised to 32. Committed beside each other
rather than one over the other, each stating its own conditions, exactly as `spine-only/` and
`with-broker/` are - two captures that do not state their conditions cannot be compared, and comparing
them anyway is how a team concludes a regression exists.

## What they show

The interpretation, with the numbers and what they mean, is in
[`../../docs/architecture/estate-under-load.md`](../../docs/architecture/estate-under-load.md).
