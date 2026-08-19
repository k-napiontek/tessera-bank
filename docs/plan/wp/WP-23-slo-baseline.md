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

To be detailed before execution.

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
