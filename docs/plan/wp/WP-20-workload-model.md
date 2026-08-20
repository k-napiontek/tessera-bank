# WP-20 - Workload model

| | |
|---|---|
| **Ticket** | TB-1020 |
| **Branch** | `feat/TB-1020-workload-model` |
| **Stratum** | 4 - Go, ~2025 |
| **Depends on** | WP-02 |
| **Status** | `In progress` |

## Objective

Declare the shape of Tessera Bank's demand as a versioned contract, and build the deterministic
engine that turns it into a schedule of intended events.

[`master-plan.md`](../master-plan.md) says this repository exists so that "zero-downtime deployment,
schema migration **under load**, secret rotation, blast-radius control and dependency remediation"
can be practised on something non-trivial. The estate is now non-trivial, stateful and old. It has
never once been under load. Every component was built and verified one request at a time, and the
observability WP-09, WP-12, WP-13 and WP-17 installed has never had anything to observe -
`walkthrough.sh`, the nearest thing to a driver here, makes six API calls against three hard-coded
account references. A metric with no demand behind it is a number that has only ever been zero.

This package builds the model, not the load. It states what a bank's day looks like - who the
customers are, what they do, how demand varies across the day, the week and the month, and where the
cut-off and the batch window fall - and turns that into a schedule of intended send times. **It
performs no I/O of any kind.** WP-21 executes the schedule against the modern spine; WP-25 executes
the same schedule against the older strata. The model is a contract precisely so that the second
driver cannot drift from the first.

## In scope

- `contracts/workload/` - a JSON Schema 2020-12 document for the model, one committed model
  `tessera-day-v1.json`, and a validation step in `contracts/validate.sh`.
- A **customer population**: cohorts (retail, retail-affluent, small business, corporate) with a
  per-cohort transfer frequency, amount distribution, currency mix and operation mix. Account and
  customer references only, drawn to the canonical patterns.
- A **bank-day calendar**: the diurnal intensity curve, the weekday shape, payday and month-end
  multipliers, and the named windows - cut-off, overnight batch, morning reconciliation.
- A **virtual clock** with a compression factor, so a 24-hour business day can be watched in twenty
  minutes without editing the model.
- An **arrival-process engine**: a non-homogeneous Poisson process whose intensity is the day curve,
  emitting intended send times. An open model, not a pool of looping workers.
- A **run manifest**: seed, model digest, git SHA, scale, compression and window, so a run is
  reproducible and two runs are comparable.
- A Go module `workload/` at the repository root, with `build-workload`, `test-workload` and
  `lint-workload` in the Makefile's existing per-tier pattern.

## Out of scope

- **Any network call, and any file the estate reads.** This package sends nothing and drives nothing.
  It emits a schedule; WP-21 executes it.
- Token minting, HTTP, Kafka, fixed-width movement files - WP-21 and WP-25.
- Bulk-loading the ledger - WP-22.
- Metrics, SLOs, baselines and run reports - WP-23.
- Failure injection - WP-24.
- Dashboards, alert rules and schedulers - platform repositories,
  [ADR 0001](../../governance/adr/0001-source-only-repository.md).

## Constraints

- **The open model is the point, and this package must say why.** A closed model - *N* virtual users
  each waiting for a response before sending again - throttles itself precisely when the system
  slows. Offered load falls exactly when the interesting thing is happening, and the latency figures
  come out flattering. That is coordinated omission, and it is the same class of defect as the `V99`
  truncation in WP-04: the output looks entirely plausible and is simply wrong. The engine therefore
  schedules an **intended send time** per event, independent of any response, and WP-21 measures
  latency from that intended time rather than from the actual send.
- **`scale` and `compress` are separate dials, and compression multiplies intensity.** A mid-size
  retail bank runs on the order of 50-100 transfers per second on average and several hundred at
  peak. Run that day at 72x compression and the model asks for tens of thousands per second, which
  this estate will not take and must not pretend to. The model states the real-time daily volume it
  describes, the manifest records both dials, and nothing here claims a throughput a run did not
  produce.
- **The reference formats are not one format.** `^TB[0-9A-Z]{14}$` for an account, `^CU[0-9]{10}$`
  for a customer, `^TB[0-9]{18}$` for a transfer, `^TB[0-9]{18}-[0-9]{2}$` for a movement. The
  generator draws each to its own pattern, and the test reads the patterns from
  `contracts/openapi/ledger-core.yaml` rather than from a copy pasted into the test.
- **The population is what makes the load realistic, and also what makes it possible.** The gateway
  limits 10 requests per second with a burst of 20, keyed by **token subject plus route class**, per
  instance ([ADR 0006](../../governance/adr/0006-edge-rate-limit-is-per-instance.md)). One synthetic
  customer therefore cannot generate meaningful load, and raising the limit for a load run would
  measure a gateway nobody deploys. A population of thousands of distinct subjects is both the
  realistic shape and the working one - the cohort model is load-bearing, not decorative.
- **Money is minor units.** `int64` throughout with an ISO 4217 code beside it, and a test that
  parses the amount source and fails on a `float64`, a division or a `math.Round` - the same control
  as `money.source.test.ts` and `batch/reporting/money.py`, in the third language where it matters.
- **Determinism.** The same seed and the same model produce the same schedule, byte for byte.
  Asserted rather than assumed; the precedents are `fraud-scoring`'s `modelVersion` digest and
  `generate.py --seed 42`.
- **No personal data, and the schema must be incapable of expressing it.** There is no field a name
  could go in. [`data-classification.md`](../../ways-of-working/data-classification.md) also requires
  that verification greps the actual output rather than asserting about intent.
- **Standard library only.** The arrival process, the clock and the distributions are written and
  tested here, because they are the part that has to be right. No JSON Schema library either: the
  committed model is checked by a hand-written Python checker in the shape of
  `contracts/check-extract-layout.py`, which is what `validate.sh` already does twice and which
  leaves its dependency set unchanged.
- The Go directive follows `edge/api-gateway/go.mod`, for the reason recorded in the decision log:
  the tier follows what its justified dependencies require.

## Tasks

1. Detail this task list, record the decisions in `STATUS.md`, and set the package `In progress`.
2. **The contract, first.** `contracts/workload/workload-model.schema.json` and
   `contracts/workload/tessera-day-v1.json`, plus `contracts/check-workload-model.py` wired into
   `validate.sh`. A schema before a parser, per [`PROTOCOL.md`](../PROTOCOL.md) - and the precedent
   is `contracts/reporting/`, where WP-17 declared a fixed-width layout before writing the writer.
3. **Money and the amount distribution.** `int64` minor units, a log-normal-shaped draw per cohort
   clamped to a declared range, and the source-scanning test that fails on a float.
4. **The virtual clock.** Business date, time of day, compression, and the named windows. Pure
   arithmetic - the engine never reads the wall clock, which is what makes a run reproducible and a
   test fast.
5. **The intensity curve.** Diurnal shape by hour, weekday multipliers, payday and month-end
   multipliers, composed into one intensity function and tested against the peak-to-trough ratio the
   model declares.
6. **The arrival process.** Non-homogeneous Poisson by thinning, seeded, emitting intended send
   times. Two tests carry this package: the schedule is byte-identical for one seed, and the realised
   rate over each hour matches the declared intensity inside a stated tolerance.
7. **The population.** Cohorts, references drawn to the canonical patterns, and the operation mix -
   transfer, balance read, statement page, place hold, capture, release - weighted per cohort. The
   mix is checked against the eleven operations in `contracts/openapi/ledger-core.yaml`, so the model
   cannot name an operation the estate does not serve.
8. **The run manifest.** Seed, model digest, git SHA, scale, compression and window, written as JSON,
   with a test proving the digest changes when the model changes.
9. **Toolchain and documentation.** Makefile targets, `workload/README.md` stating plainly that this
   is a fixture and not a component of the bank, the traceability rows, and ADR 0016 - **0012 was
   taken** by `0012-slo-catalogue-boundary.md`, written when this strand was planned.
10. **Verification and landing.** The commands below, with real output into the pull request, then
    `STATUS.md`.

## Definition of Done

- [ ] `bash contracts/validate.sh` exits 0 with the workload family included, and fails when the
      committed model breaks the schema. Demonstrated both ways.
- [ ] The same seed and the same model produce a byte-identical schedule; changing either changes it.
- [ ] The realised arrival rate over each hour of a simulated day matches the declared intensity
      within the stated tolerance, and the peak-to-trough ratio is what the model says it is.
- [ ] Every generated reference matches the pattern in the OpenAPI document, read from that document.
- [ ] No `float64` on any amount path. The source-scanning test fails when one is planted.
- [ ] The schema has no field capable of carrying personal data, and a grep of a generated day's
      output finds nothing resembling any.
- [ ] The engine performs no I/O, asserted by a test that fails if `net`, `os/exec` or a database
      driver becomes reachable from the engine package - the same control as `DomainPurityTest`.

## Verification

```bash
make test-workload        # the engine, under the race detector
make lint-workload        # gofmt -l, go vet
bash contracts/validate.sh
make test                 # unchanged and still green - no existing tier is touched
```

Then the model printed as a day, because a curve that is only tested is not read:

```bash
go -C workload run ./cmd/workload-plan \
   --model ../contracts/workload/tessera-day-v1.json \
   --date 2026-08-31 --seed 42 --scale 1.0 --compress 72 --summary
```

The output is the hour-by-hour shape of a compressed month-end day - a night trough, a morning ramp,
a lunch peak, a payday spike, the cut-off. That is how a reader sees this is a bank's day rather
than a flat rate with a nice name.

## Traceability

This package opens the `REQ-PERF-*` family. It owns two of its eight ids; the rest are owned by the
packages that follow.

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-001 Demand is described as a versioned model, not embedded in a tool | `contracts/workload/`, validated by `validate.sh` |
| REQ-PERF-002 A load run is reproducible from its recorded manifest | the seeded arrival process and the run manifest |
