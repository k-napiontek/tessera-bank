# workload/baselines - what this estate actually did

Committed measurements, each carrying the conditions it was taken under.

### The recorded normal

| File | What it is |
|---|---|
| [`baseline-report.txt`](baseline-report.txt) | The run against the catalogue: which objectives were met |
| [`baseline-run-manifest.json`](baseline-run-manifest.json) | The run: model digest, seed, both dials, window, commit |
| [`baseline-dataset-manifest.json`](baseline-dataset-manifest.json) | The ledger it ran against: dataset digest, chain head, row counts |
| `baseline-before.prom`, `baseline-after.prom` | The ledger's scrapes, taken where the run brackets itself |
| `baseline-before-edge.prom`, `baseline-after-edge.prom` | The gateway's, at the same two instants |

Produced by [`../scripts/baseline.sh`](../scripts/baseline.sh). The scrapes are committed because
without them the report cannot be regenerated, and a report nobody can regenerate is one nobody can
check:

```bash
# go -C runs with workload/ as the working directory, so the paths below are relative to it.
go -C workload run ./cmd/workload-report \
  --manifest baselines/baseline-run-manifest.json \
  --before baselines/baseline-before.prom --before baselines/baseline-before-edge.prom \
  --after baselines/baseline-after.prom --after baselines/baseline-after-edge.prom \
  --catalogue ../contracts/slo/tessera-slo-v1.json \
  | diff - workload/baselines/baseline-report.txt && echo "identical"
```

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

## What this baseline does not cover, and says so

`estate-up.sh` boots the modern spine - PostgreSQL, the ledger and the gateway - and nothing else.
So the baseline is a recorded normal for two of the five components in the catalogue, and the report
prints `nothing happened` against the other three rather than quietly leaving them out:

- **`fraud-scoring` was not running.** It consumes from Kafka and there is no broker in this fixture.
- **`reporting` and `recon` are batch jobs.** Neither ran during the window, and their metrics go to
  a node_exporter textfile rather than to an endpoint anything scraped here.
- **`SLO-LEDGER-OUTBOX-FRESHNESS` is outside its objective in this capture** - the lag was 140 s at
  the start and 185 s at the end against a 60 s target. That is the fixture rather than the ledger:
  with no broker to publish to, the relay cannot drain, so the oldest unpublished event only ages.
  It is left in the report rather than suppressed, because a baseline that hides the signals it
  could not exercise is a baseline that will be quoted as though it had.

## What they show

The interpretation, with the numbers and what they mean, is in
[`../../docs/architecture/estate-under-load.md`](../../docs/architecture/estate-under-load.md).
