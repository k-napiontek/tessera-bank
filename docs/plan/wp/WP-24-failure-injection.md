# WP-24 - Failure injection and the soak run

| | |
|---|---|
| **Ticket** | TB-1024 |
| **Branch** | `feat/TB-1024-failure-injection` (24a), `feat/TB-1024-migration-and-soak` (24b), `feat/TB-1024-signatures` (24c) |
| **Stratum** | - |
| **Depends on** | WP-23 |
| **Status** | `Done` - 24a. `Not started` - 24b and 24c |

## Objective

Make a run worth watching: degrade the estate in ways an operator would recognise, while traffic is
flowing, and record what each degradation looks like from the outside.

A load run against a healthy system answers one question - how fast is it - and that is the least
interesting question in operations. What a platform engineer needs to practise is recognising a
failure from its signature: which graph moves first, which one moves misleadingly, and which stays
flat while the customer experience collapses. This repository already documents several such
signatures from reasoning rather than from observation. `ledger-observability.md` says
`outcome="replayed"` rising means clients are timing out, and that `ledger_outbox_lag_seconds` is
the one to page on rather than `ledger_outbox_pending`. Neither has ever been seen happening.

This package also enables the single most DevOps thing in the repository: applying a schema
migration while money is moving, which `master-plan.md` names as a motivating skill and which
nothing here currently allows anyone to attempt.

## In scope

- A catalogue of injected conditions, each declared as a scenario in the workload contract:
  a slow downstream dependency, a partial outage, a stuck outbox row, consumer lag, a rate-limit
  storm, connection-pool exhaustion, and a clock skew across the batch boundary. *(24a)*
- **A Flyway migration applied to a live ledger under traffic**, with the lock it takes and the
  latency it causes recorded. *(24b)*
- A soak run long enough to show `outbox_record` and `idempotency_record` growing without bound
  (F-28) and `balance` accumulating dead tuples under churn. *(24b)*
- A signature record per condition: what moved, in what order, and what stayed flat. *(24c, moved
  out of 24a on 2026-08-22 - see the note under Tasks)*
- Runbook updates where an observed signature contradicts a documented one. *(both halves, each in
  the change that observed the contradiction)*

## Out of scope

- Fixing anything the injection reveals. Findings are logged as follow-ups; each becomes its own
  change, per [`PROTOCOL.md`](../PROTOCOL.md).
- **No index, column or constraint is added to `services/ledger-api`'s migration set.** The migration
  exercised under traffic is the scenario's own, applied against the running ledger with its own
  Flyway history table. This is a scope boundary as much as a design choice, and it is written here
  so that the easier version is refused halfway through rather than reached for. See task decision 5.
- Injecting faults that are dangerous or irreversible, which WP-18 excludes for the same reason.
- Automated remediation or self-healing. This estate detects and reports; a human decides.
- Retention policies for the tables the soak run grows. F-28 needs a regulatory answer before an
  engineering one, and inventing a retention period here would be inventing a compliance position.

## Constraints

- **A condition is a scenario in the model, not a branch in the driver.** Injection declared in the
  workload contract stays reproducible, comparable against a baseline, and available to WP-25's
  drivers without being rewritten. A flag in the driver is none of those things.
- **Every run is compared against a baseline captured under the same manifest.** A degradation
  described without the normal it degraded from is an anecdote. 24a therefore re-takes the baseline
  when it changes the fixture, rather than diffing against a normal that no longer applies.
- **The estate is not modified to make a fault injectable.** If a condition cannot be produced
  without changing a component, that is a finding about the component's testability and it is
  recorded rather than worked around. **Extending the fixture is not modifying the estate** -
  `workload/` is a test fixture and says so in its own first paragraph - and the line between the two
  is what task decision 3 exists to hold.
- **A documented signature that observation contradicts is corrected in the runbook, in the same
  change.** A runbook that survives being wrong is worse than no runbook - it is read under pressure.
- The migration exercise uses a real migration against a real ledger under real traffic. A migration
  applied to an idle database demonstrates nothing about applying one to a busy one.
- No personal data in any captured artefact, including the ones captured mid-incident. Error paths
  are where a leak hides, and they are the least tested.

## Tasks

> **2026-08-22: the seven signatures moved out of 24a into a third half, WP-24c.** 24a's first real
> runs found four defects in its own fixture, each of which invalidated the captures taken before it
> and cost a full re-capture cycle - about thirty-five minutes each. They are listed in
> [`estate-under-load.md`](../../architecture/estate-under-load.md) and every one of them is now
> fixed and committed. A fifth is open and undiagnosed: **`edge/fraud-scoring` scores nothing during
> an injected run** while the same fixture drives it correctly on its own, and until that is
> understood a committed signature would be a measurement of the fixture. So 24a lands the contract,
> the checker, the extended fixture, the injector and the re-taken baseline - all verified - and the
> seven signatures and the runbook corrections that depend on observing them become **WP-24c**, on
> the repository owner's instruction. The alternative was committing signatures nobody should quote.

Detailed 2026-08-21. The package lands as **three halves on one ticket**, WP-24a, WP-24b and WP-24c,
each its own branch and pull request, tracked as three rows in `STATUS.md`. Detailed out, this package spans a
contract, a checker, an extended fixture, a re-taken baseline, seven injected conditions, a schema
migration under live traffic and a soak run; the decision log records after WP-09 that the right
answer at this size is to split the package in the plan rather than the pull request. This file stays
a single document because other documents link to it.

Five decisions were taken with the repository owner before any code, and each changes what gets
built.

- **A scenario is its own contract pair, not a field on the day model.**
  `contracts/workload/scenario.schema.json` and `contracts/workload/tessera-scenarios-v1.json`, with
  a checker wired into `contracts/validate.sh` beside the five already there. The day model states
  **demand**; a scenario states **degradation**, and they are different subjects with different
  lifetimes. Adding scenarios to `workload-model.schema.json` would bump `tessera-day-v1.json`'s
  version for a reason unrelated to demand - and every WP-21, WP-22 and WP-23 manifest records the
  model digest precisely so that two runs can be compared. A baseline whose model changed because
  somebody added a fault is a baseline nothing can be diffed against. `contracts/workload/` is the
  workload contract, so the Constraint above is satisfied either way.
- **A scenario names the objectives it is expected to move *and* the ones it must not.** This is what
  turns the Definition of Done's "what moved, in what order, and what stayed flat" from prose into
  something a check can fail: `workload-report` already evaluates objectives out of `contracts/slo/`,
  so a scenario carrying both lists of SLO ids lets a signature be **asserted** rather than described.
  The flat list is the load-bearing half and it is the half a hand-written write-up always omits.
  WP-23's separation of the two lock timers is the same idea one layer down - the interesting claim
  was that the account lock stayed at 0.29 ms while the chain wait moved, and a record of what moved
  alone would have proved nothing about which of the two was the ceiling.
  [ADR 0012](../../governance/adr/0012-slo-catalogue-boundary.md) names the dangerous direction: a
  claim nobody checks still looks like a control.
- **F-77 is closed here, in the fixture rather than in the estate.** `estate-up.sh` boots PostgreSQL,
  the ledger and the gateway, and three of the seven conditions have no observable against that:
  consumer lag has no consumer, and a stuck outbox row is the fixture's **permanent state** rather
  than an injected condition - the committed baseline records `SLO-LEDGER-OUTBOX-FRESHNESS` as
  *missed*, lag 140 s against a 60 s target, because with no broker the relay cannot drain at all.
  So 24a boots a Kafka container and `edge/fraud-scoring`, exactly as it already boots a PostgreSQL
  container. Both components already read their configuration from the environment and neither
  changes.
- **The baseline is re-taken at full WP-22 volume, which closes F-79.** The moment the fixture gains
  a broker and a scorer, the committed baseline is no longer the same manifest, and diffing an
  injected run against a normal that already records a missed outbox objective would credit the
  injection with a failure the fixture had all along. The re-take is at 150 000 customers over 250
  business dates rather than the 20 000 over a fortnight the committed one used, because **F-24
  records the planner abandoning `posting_account_ix` only at the larger cardinality**: a
  pool-exhaustion or latency signature taken against a database where the statement page is still
  cheap is a signature of the fixture. It is committed **beside** the WP-23 baseline rather than over
  it, with both sets of conditions stated - two baselines that do not state their conditions cannot
  be compared, and comparing them anyway is how a team concludes a regression exists.
- **The migration under traffic is the scenario's own, not the ledger's.** The SQL lives under
  `workload/` and is applied against the running ledger with its own Flyway history table, so
  `services/ledger-api`'s migration set is untouched. A package that measures should not leave a
  permanent row in the schema history of the thing it measured. Both alternatives cost something
  real and were refused: taking the index **F-24** asks for would partially answer F-24, which is
  WP-07's migration set and which WP-22's Out of scope explicitly forbade; and an add-then-drop pair
  would leave two permanent migrations in the ledger's history that exist only to demonstrate an
  exercise. What is measured does not depend on whose migration set the file lives in - the lock the
  migration takes, and what the customer-facing latency did while it held it.

### WP-24a - the scenario contract, the fixture and the seven signatures

Branch `feat/TB-1024-failure-injection`. Nine tasks, roughly one commit each, test-first throughout.

1. Set 24a `In progress` in `STATUS.md` and branch from up-to-date `main`, per
   [`PROTOCOL.md`](../PROTOCOL.md).

2. **The scenario contract, landed before anything reads it** - step 12 of `PROTOCOL.md`, and the
   shape `contracts/workload/` and `contracts/slo/` both already use. A JSON Schema 2020-12 document
   and one committed catalogue, `TB-SCENARIOS-V1`, holding the seven conditions. One entry each: the
   condition, its parameters, the SLO ids it is expected to **move**, the ids expected to stay
   **flat**, and the package that introduced it. The schema is **closed and every string bounded**,
   for the reason WP-20's is - an object left open accepts a field nobody checked - and it carries
   the same denylist of property names, because a scenario describing who to notify would be ADR
   0012's boundary crossed a second time.

3. **`contracts/check-workload-scenarios.py`, wired into `validate.sh`.** Standard library only, like
   the five checks beside it, because `validate.sh` runs from a clean checkout. It validates the
   catalogue against the schema and **fails on a JSON Schema keyword it does not implement** rather
   than agreeing with a schema it never checked - `check-slo-catalogue.py` set that rule and **F-20**
   records what a suite printing thirteen lines of `PASS` over a check it skipped costs. Every SLO id
   a scenario names is resolved against `contracts/slo/tessera-slo-v1.json`, so a scenario cannot
   expect an objective the estate does not have, and the two lists are asserted disjoint - an
   objective that is expected to move and to stay flat makes the signature unfalsifiable. Demonstrated
   both ways against planted faults, one of which is an SLO id renamed in the catalogue and left stale
   in a scenario.

4. **F-77: the fixture boots a broker and the scorer.** A Kafka container and `edge/fraud-scoring` in
   `workload/scripts/estate-up.sh`, beside the PostgreSQL container already there. Waited on rather
   than slept on, and torn down with the **process group** - **F-73** records both traps and this
   script is where they were fixed the first time. The relay is asserted to **actually drain**: an
   unreachable broker and a working one produce the same ledger log line until somebody looks at the
   lag, which is exactly why the committed baseline records a missed objective that nobody noticed
   was the fixture. `estate-up.sh` stays composable - `baseline.sh` calls it and must keep working
   unchanged.

5. **The injector, as a fixture under `workload/`.** It reuses `internal/client` and
   `internal/manifest` rather than growing a second copy of either - the duplication **F-61**,
   **F-64** and **F-66** each record rotting. `internal/purity` is told which side of the
   engine/driver line every new package sits on, and a package in neither list fails
   `TestEveryPackageIsClassified`: which side a new package belongs on is a decision somebody takes
   rather than one that happens. **F-80** notes the classification is by role rather than by purity,
   and this task neither widens nor closes it.

6. **Re-take the baseline** under the extended fixture at full WP-22 volume -
   `baseline.sh --customers 150000 --from 2025-09-01 --to 2026-08-21` - and commit it **beside** the
   WP-23 one, each naming its own conditions: model version, seed, scale, compression, dataset digest,
   git SHA and the hardware. Closes **F-79**. The old baseline is not deleted: it is the record of
   what the estate did without a broker, and the difference between the two is itself a finding.

7. *(moved to WP-24c)* **Inject all seven, capture each signature, diff it against the baseline.**

8. *(moved to WP-24c)* **Correct every runbook claim that observation contradicted.**

9. **Documentation, traceability and landing.** The write-up in the register
   [`estate-under-load.md`](../../architecture/estate-under-load.md) and
   [`query-plans-at-volume.md`](../../architecture/query-plans-at-volume.md) established;
   `REQ-PERF-007`'s evidence row in
   [`../../compliance/traceability-matrix.md`](../../compliance/traceability-matrix.md); and the
   README of every directory that changed, including the root one that **F-17** and **F-31** both
   record going stale. Then the Verification below, with real output in the pull request.

### WP-24c - the seven signatures

Branch `feat/TB-1024-signatures`. Depends on 24a, and independent of 24b. Four tasks.

1. **Diagnose why `edge/fraud-scoring` scores nothing during an injected run.** `estate-up.sh` driven
   on its own consumes correctly - a consumer group at offset 3 315 with zero lag - and the same
   script under `signatures.sh` produces `tessera_fraud_scoring_seconds_count 0.0` in every one of
   the seven, with the relay reporting nothing pending. Until that is understood, two of the eleven
   objectives are unmeasured in every signature and a committed capture would be a measurement of the
   fixture. **F-86.**

2. **Inject all seven, capture each signature, diff it against `with-broker/`.** Each run records
   which objectives moved, in what order, and which stayed flat, **against what its scenario declared
   would happen** - which `workload-report --scenario` already prints. A condition that cannot be
   produced without changing a component is left uninjected and **recorded as a finding about that
   component's testability**, per the Constraint; `SCN-CLOCK-SKEW` is already known to be one.
   `workload/scripts/signatures.sh` runs all seven and is committed by 24a.

3. **Where a declaration was contradicted, revise it with the evidence in hand.** The first runs
   contradicted several, and 24a deliberately left the catalogue as written: a prediction rewritten
   after seeing the answer is not a prediction. Revising it here, in a change that carries the
   measurement, is the honest version. **F-83** records what is already known - `SCN-POOL-EXHAUSTION`
   moved `SLO-GATEWAY-LATENCY`, which its scenario names in neither list.

4. **Correct every runbook claim that observation contradicted, in this change.** A runbook that
   survives being wrong is worse than none, because it is read under pressure.
   [`ledger-observability.md`](../../runbooks/ledger-observability.md) is the first place to look: it
   claims `outcome="replayed"` rising means clients are timing out, and that
   `ledger_outbox_lag_seconds` rather than `ledger_outbox_pending` is the one to page on. A claim that
   survives contact with the injection is left alone and **said to have been checked**, which is not
   the same statement as the one it carried before.

### WP-24b - the migration under traffic and the soak

Branch `feat/TB-1024-migration-and-soak`. Four tasks. Both exercises need 24a's fixture and 24a's
baseline to mean anything, which is why they are the second half rather than the first.

1. Set 24b `In progress` and branch from up-to-date `main`.

2. **A migration applied to a live ledger under traffic**, scenario-owned per the fifth decision
   above. The lock it takes is read from `pg_locks` while it is held, and the latency it caused is
   read **from the customer's side** rather than from the database's: what an operator needs to know
   is what the customer experienced, which is where WP-23 put the ledger's own metrics for the same
   reason. It is applied **mid-run**, and the run continues around it - a migration applied between
   two runs measures a maintenance window, which is the thing this exercise exists not to be.

3. **The soak run.** Long enough to put figures on **F-28**'s unbounded growth in `outbox_record` and
   `idempotency_record`, and on dead tuples accumulating in `balance` under churn - all three of which
   WP-23's `DatabaseSignals` already exports, so this task measures rather than instruments. The
   growth rate is reported **with the conditions it was measured at**, and where the run is shorter
   than the claim the extrapolation says so on the page rather than being presented as a measurement.
   A figure whose derivation is hidden is the WP-23 window objection in another costume.

4. **Runbook and documentation corrections** the two exercises produce, `REQ-PERF-007`'s remaining
   evidence, and the retention question left **explicitly open**: F-28 needs a regulatory answer
   before an engineering one, and a retention period invented here would be a compliance position
   invented here. Then the Verification below.

## Definition of Done

The half that satisfies each box is named, because two pull requests cannot both tick all six.

- [ ] Every condition in the catalogue can be injected, and each produces a recorded signature. *(24c)*
- [ ] Each signature is compared against a baseline captured under the same manifest. *(24c)*
- [x] A scenario is declarable, checkable and injectable, and the estate can show one. *(24a)*
- [x] A recorded normal exists for the extended fixture, at full WP-22 volume. *(24a)*
- [ ] A migration is applied under traffic, and the lock it takes and the latency it caused are
      recorded. *(24b)*
- [ ] A soak run demonstrates unbounded growth in the two tables F-28 names, with figures. *(24b)*
- [ ] Every runbook claim that observation contradicted is corrected. *(24b and 24c, each in its own change)*
- [x] No component was changed to make a fault injectable, or the change is recorded as a finding.
      *(24a; 24b and 24c re-check it)*
- [ ] Checked against [`../../ways-of-working/definition-of-done.md`](../../ways-of-working/definition-of-done.md).

## Verification

```bash
bash contracts/validate.sh                       # the scenario catalogue against its schema and the SLO ids
make test-workload                               # the module, under the race detector
make lint-workload
make test                                        # every other tier still green
```

Then, and this is the half that cannot be automated: run each condition against a loaded ledger under
a compressed day, capture the signature, and diff it against the baseline 24a re-took. Then apply the
migration mid-run and record what the customer-facing latency did while it held its lock.

Real output into the pull request, never expected output. Both halves state the conditions every
figure was taken at - **a number without its conditions is a hunch wearing a decimal point.**

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-007 Degradation is exercised, not assumed | the injected-condition catalogue and its recorded signatures |
