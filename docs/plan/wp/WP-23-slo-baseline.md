# WP-23 - SLO catalogue and baseline

| | |
|---|---|
| **Ticket** | TB-1023 |
| **Branch** | `feat/TB-1023-slo-baseline` |
| **Stratum** | - |
| **Depends on** | WP-21, WP-22, WP-13, WP-17 |
| **Status** | `Not started` |

## Objective

State what good looks like for each component, record what normal actually is, and produce a run
report that can be compared against it.

The estate is well instrumented and entirely unjudged. Four business metrics on the ledger, four on
the gateway, five on the fraud scorer, five on reporting - and nowhere a statement of what value any
of them should have. F-41 puts it exactly: the fraud score histogram "will show a rule set drifting
towards a threshold long before the outcomes change... but nothing watches it, and there is no
recorded **normal** to compare against." The same is true of every other signal in the repository.

The database is the larger gap. Not one database signal is exported today. The ledger reports how
many transfers posted and nothing at all about the PostgreSQL that posted them - no pool
utilisation, no lock wait, no table growth, no vacuum activity - while every transfer takes two row
locks and one global advisory lock, and two follow-ups (F-27, F-28) are open questions that only a
database signal can answer.

## In scope

- `docs/ways-of-working/slo-catalogue.md`: per component, the SLI definition, the objective, the
  measurement window and the error-budget arithmetic, written against metrics that already exist.
- A machine-readable form of the same catalogue, so a platform repository can consume it rather than
  re-type it.
- **Database signals emitted by the ledger**: connection-pool utilisation and acquire wait, lock wait
  separated into per-account and the global advisory lock, table and index growth, dead tuples and
  vacuum activity.
- A **committed baseline artefact**: the recorded normal, captured from a WP-21 run against a WP-22
  ledger, with the run manifest that produced it.
- A run report generated from a manifest and a metrics snapshot, reproducible the way
  `batch/reporting` is reproducible.
- The measured number F-27 asks for.
- **The two follow-ups already on record as belonging here.** F-37 has named this package as the one
  that exports the gateway limiter's gauge since WP-12, so one metric in the catalogue is added here
  rather than merely described. F-71 - a first `releaseHold` counted as a replay by the ledger's own
  metric - is taken here **by explicit instruction of the repository owner**, because a baseline that
  records a replay rate known to be wrong is a recorded normal nobody can use. The decision log
  records both.

## Out of scope

- **Alert rules, Grafana dashboards, Alertmanager configuration, recording rules.** Thresholds and
  dashboards live in the platform repositories - ADR 0001, and
  [`ledger-observability.md`](../../runbooks/ledger-observability.md) has said so since WP-09.
- Enabling `pg_stat_statements`, which needs `shared_preload_libraries` and is therefore server
  configuration. The catalogue names the signal and says where it comes from.
- Any change to the audit chain's locking. F-27 asks for a number, not a redesign; the redesign is a
  separate decision taken on the evidence this package produces.
- Tracing at the edge. The gateway forwards no `traceparent`, so a trace begins at the ledger rather
  than at the customer. That is a real gap and it is logged, not closed here.

## Constraints

- **An SLO in this repository is a declaration, not an alert.** What a component emits and what
  "good" means for it are properties of the software and belong here. When to page, who to page and
  at what burn rate are properties of a deployment and belong to the platform repositories. ADR 0012
  draws that line; this package is the first thing to sit on it.
- **A baseline names the conditions it was captured under or it is worthless.** Model version, seed,
  scale, compression, dataset digest, git SHA and the hardware it ran on. Two baselines that do not
  state their conditions cannot be compared, and comparing them anyway is how a team concludes a
  regression exists.
- **`@AutoConfigureObservability` is required in any test that asserts on a metric.** F-32 records
  that Boot switches observability off inside `@SpringBootTest`, leaving a `SimpleMeterRegistry` and
  no `/actuator/prometheus` - so a metrics test passes while verifying nothing, which is the failure
  mode this repository cares about most.
- **The measured number is measured, and the conditions are stated with it.** F-27 asks for "a
  measured number, not a hunch" about the audit chain's advisory lock. A number without the pool
  size, the dataset and the concurrency it was taken at is another hunch wearing a decimal point.
- No personal data reaches a report or a baseline. Verification greps the actual artefacts.

## Tasks

1. Set the package `In progress` in `STATUS.md` and branch from up-to-date `main`, per
   [`PROTOCOL.md`](../PROTOCOL.md).

2. **The catalogue is a contract, so that a platform repository can consume it rather than re-type
   it.** `contracts/slo/` gains a JSON Schema 2020-12 document and one committed catalogue,
   `TB-SLO-CATALOGUE-V1`, landed **before** anything reads it - the shape `contracts/workload/`
   established. One entry per objective: the SLI as a metric, its tags and how it is aggregated; the
   objective as a target and a measurement window; the error budget that follows arithmetically from
   the two; and the package that introduced the signal. The schema is closed and every string
   bounded, for the reason WP-20's is - an object left open accepts a field nobody checked.
   **[ADR 0012](../../governance/adr/0012-slo-catalogue-boundary.md) is what this file is**, and it
   decides what may not appear in it: no burn rate, no notification route, no threshold that would
   differ between two deployments of the same code. The test the ADR gives is whether the artefact
   would change if the same code were deployed differently.

3. **A checker that reads the components rather than a copy of them.**
   `contracts/check-slo-catalogue.py`, standard library only and wired into `validate.sh` like the
   three checks beside it - `validate.sh` runs from a clean checkout. It validates the catalogue
   against the schema, **fails on a JSON Schema keyword it does not implement** rather than agreeing
   with a schema it never checked, proves each error budget is what its objective and window actually
   imply, and asserts every metric the catalogue names is **emitted by the component that owns it** -
   read out of `edge/api-gateway/internal/metrics/metrics.go`, the `observability.py` of
   `fraud-scoring`, `reporting` and `recon`, and the Java meter-name constants, never transcribed.
   **F-64 records what transcribing costs.** Demonstrated both ways against planted faults, one of
   which is a metric renamed in code and left stale in the catalogue: that is the drift ADR 0012
   names as the dangerous direction, because an objective nobody can check still looks like a control.

4. **The ledger reports how many transfers posted and nothing about the database that posted them.**
   A `DatabaseSignals` component in `services/ledger-api`, registered beside the existing
   `outboxGauges` in `LedgerConfiguration`: connection-pool utilisation and acquire wait, table and
   index size, dead tuples, and autovacuum count and recency, polled from `pg_stat_user_tables` and
   `pg_total_relation_size`. Hikari's own meters are **confirmed present in a real scrape** rather
   than assumed present because Boot usually registers them. Cardinality is bounded by construction -
   one series per table from a fixed list, never `pg_stat_user_tables` as it comes, which grows a
   series every time a migration adds a table. The test carries **`@AutoConfigureObservability`**:
   **F-32** records that without it Boot leaves a `SimpleMeterRegistry`, `/actuator/prometheus` does
   not exist, and a metrics test passes while verifying nothing.

5. **Two locks, two timers, and never one average of both.** `pg_advisory_xact_lock` in
   `JdbcAuditLog` is service-wide and held to commit; the `FOR UPDATE` acquisition in `AccountLocks`
   is per account and contends only between transactions touching the same accounts. Averaged into
   one "lock wait" they are worse than no signal at all, because the composite moves for two
   unrelated reasons and **F-27 becomes unanswerable** - the whole question is which of the two is
   the ceiling. Timed separately, with a test that proves contending transactions move one and not
   the other, in both directions. No change to the locking itself: F-27 asks for a number, and the
   redesign is a separate decision taken on the evidence this package produces.

6. **F-37: a limiter whose memory is invisible.** `ratelimit.Limiter.Tracked()` reports how many
   buckets are held and nothing exports it, so the memory the limiter uses - and the sweep that is
   supposed to bound it - cannot be seen in production. A gauge beside the refusal counter in
   `edge/api-gateway/internal/metrics/metrics.go`, and its catalogue entry. F-37 has named WP-23 as
   the package that does this since WP-12.

7. **F-71: a first `releaseHold` is not a replay.** The contract gives `releaseHold` a single success
   status while the other four money-moving operations answer `201` on first execution and `200` on a
   replay, so `MoneyMovementMetricsFilter` maps every successful release to `replayed` - the ledger's
   own metric and WP-21's driver agree with each other and both call a success a retry. Contract
   first, per [`PROTOCOL.md`](../PROTOCOL.md) step 12: the status list in
   `contracts/openapi/ledger-core.yaml`, then `HoldController`, then the filter, then the driver's
   expectations in `workload/internal/client`. **Taken here by explicit instruction of the repository
   owner** and recorded in the decision log: a baseline that records a replay rate known to be wrong
   is a recorded normal nobody can use, which is the state this package exists to end.

8. **A report generated from a manifest, not printed as the run goes past.** `workload-run` prints
   its report to stdout and the report dies with the terminal. `workload/cmd/workload-report` reads a
   run manifest and one or more Prometheus scrapes from files and renders the report - reusing
   `internal/manifest` and `internal/reconcile`'s exposition reader rather than growing a second copy
   of either. **It reads no clock and opens no socket.** A generation timestamp is what makes a
   byte-identical rerun impossible by construction, which `batch/reporting` already learnt and says
   so in its own tests. The rerun test compares **bytes** over committed fixtures, not structs, as
   WP-20 pins its schedule.

9. **The measured number F-27 has been asking for since WP-09.** A harness under `workload/cmd/`,
   reusing `internal/client`, driving concurrent transfers **directly at the ledger** and never
   through the gateway: the question is about an advisory lock on the write path, and an edge in
   front of it adds a rate limiter and a token check to a figure that is supposed to be about the
   database. Raise concurrency against one ledger until throughput stops rising, then repeat against
   two instances sharing one PostgreSQL - the Definition of Done asks for both, and a ceiling that
   does not move when a second writer is added is the answer, not a disappointment. Record the pool
   size, the dataset and the concurrency each figure was taken at: **a number without its conditions
   is another hunch wearing a decimal point.**

10. **The run, the baseline, and the threshold that was invented.** Load a WP-22 ledger, boot the
    estate with `workload/scripts/estate-up.sh`, drive a compressed day, and capture the scrapes.
    The baseline is committed with the run manifest and the dataset digest, and **it names the
    conditions it was captured under or it is worthless** - model version, seed, scale, compression,
    dataset digest, git SHA and the hardware. Two baselines that do not state their conditions cannot
    be compared, and comparing them anyway is how a team concludes a regression exists. Then
    **F-69**: `plausiblePeak = 2000` in `cmd/workload-plan` is a round number named in the code as
    standing in for a figure nobody had, and this run produces the figure.

11. **Documentation, traceability and landing.** `docs/ways-of-working/slo-catalogue.md` is the prose
    and points at the contract; a write-up in `docs/architecture/estate-under-load.md` in the register
    [`query-plans-at-volume.md`](../../architecture/query-plans-at-volume.md) established; the new
    signals added to [`../../runbooks/ledger-observability.md`](../../runbooks/ledger-observability.md);
    REQ-PERF-005 and REQ-PERF-006 given their evidence rows in
    [`../../compliance/traceability-matrix.md`](../../compliance/traceability-matrix.md); and the
    README of every directory that changed, including the root one that **F-17** and **F-31** both
    record going stale. Then the Verification below, with real output into the pull request.

## Definition of Done

- [ ] Every component with metrics has an SLI, an objective, a window and an error budget stated.
- [ ] The database signals above are emitted by the ledger and appear in a scrape.
- [ ] A baseline is committed together with the manifest and dataset digest that produced it.
- [ ] A run report is generated from a manifest and is byte-identical on a rerun over the same
      inputs.
- [ ] The audit chain's throughput ceiling is stated as a measured figure with its conditions, and
      the effect of adding a second ledger instance on that figure is recorded.
- [ ] No alert rule, dashboard or threshold configuration is added to this repository.

## Verification

Run a compressed day against a loaded ledger, capture the baseline, and rerun the report generator
over the same manifest to confirm it is byte-identical. Then raise concurrency until the ledger stops
scaling, and record where it stops and what the database says about why.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-005 Every service states its SLI, its objective and its error budget | the SLO catalogue |
| REQ-PERF-006 Normal is recorded before it is needed | the committed baseline artefact |
