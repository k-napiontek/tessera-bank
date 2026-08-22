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

Every directory holds the same seven files:

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

## What `with-broker/` covers that `spine-only/` did not

Both are a Friday, both at scale 0.002 and 720x over branch hours, both seed 42. What differs is the
estate and the ledger underneath it, and the two are stated here so that the pair can be compared
rather than merely placed side by side.

| | `spine-only/` | `with-broker/` |
|---|---|---|
| Business date | 2026-08-21 | 2026-08-21 |
| Components running | 2 of 5 in the catalogue | 3 of 5 |
| Ledger | 40 001 accounts, 799 565 rows | **300 001 accounts, 14 491 832 rows** |
| Dataset digest | `747f4177` | `35747263` |
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

## There is no `signatures/` here yet

`../scripts/signatures.sh` runs all seven conditions and writes one directory per condition, and
**WP-24a deliberately committed none of them.** Every run so far has had `fraud-scoring` score
nothing at all while the same fixture driven on its own consumes correctly, so two of the eleven
objectives are unmeasured in each - and a capture nobody should quote is worse than none. WP-24c
diagnoses that first. **F-86.**

## What they show

The interpretation, with the numbers and what they mean, is in
[`../../docs/architecture/estate-under-load.md`](../../docs/architecture/estate-under-load.md).
