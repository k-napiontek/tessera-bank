# workload/baselines - what this estate actually did

Committed measurements, each carrying the conditions it was taken under.

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

## What they show

The interpretation, with the plans and the run report, is in
[`../../docs/architecture/estate-under-load.md`](../../docs/architecture/estate-under-load.md).
