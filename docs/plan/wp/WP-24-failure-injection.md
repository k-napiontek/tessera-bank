# WP-24 - Failure injection and the soak run

| | |
|---|---|
| **Ticket** | TB-1024 |
| **Branch** | `feat/TB-1024-failure-injection` |
| **Stratum** | - |
| **Depends on** | WP-23 |
| **Status** | `Not started` |

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
  storm, connection-pool exhaustion, and a clock skew across the batch boundary.
- **A Flyway migration applied to a live ledger under traffic**, with the lock it takes and the
  latency it causes recorded.
- A soak run long enough to show `outbox_record` and `idempotency_record` growing without bound
  (F-28) and `balance` accumulating dead tuples under churn.
- A signature record per condition: what moved, in what order, and what stayed flat.
- Runbook updates where an observed signature contradicts a documented one.

## Out of scope

- Fixing anything the injection reveals. Findings are logged as follow-ups; each becomes its own
  change, per [`PROTOCOL.md`](../PROTOCOL.md).
- Injecting faults that are dangerous or irreversible, which WP-18 excludes for the same reason.
- Automated remediation or self-healing. This estate detects and reports; a human decides.
- Retention policies for the tables the soak run grows. F-28 needs a regulatory answer before an
  engineering one, and inventing a retention period here would be inventing a compliance position.

## Constraints

- **A condition is a scenario in the model, not a branch in the driver.** Injection declared in the
  workload contract stays reproducible, comparable against a baseline, and available to WP-25's
  drivers without being rewritten. A flag in the driver is none of those things.
- **Every run is compared against the WP-23 baseline, under the same manifest.** A degradation
  described without the normal it degraded from is an anecdote.
- **The estate is not modified to make a fault injectable.** If a condition cannot be produced
  without changing a component, that is a finding about the component's testability and it is
  recorded rather than worked around.
- **A documented signature that observation contradicts is corrected in the runbook, in the same
  change.** A runbook that survives being wrong is worse than no runbook - it is read under pressure.
- The migration exercise uses a real migration against a real ledger under real traffic. A migration
  applied to an idle database demonstrates nothing about applying one to a busy one.
- No personal data in any captured artefact, including the ones captured mid-incident. Error paths
  are where a leak hides, and they are the least tested.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] Every condition in the catalogue can be injected, and each produces a recorded signature.
- [ ] Each signature is compared against the WP-23 baseline captured under the same manifest.
- [ ] A migration is applied under traffic, and the lock it takes and the latency it caused are
      recorded.
- [ ] A soak run demonstrates unbounded growth in the two tables F-28 names, with figures.
- [ ] Every runbook claim that observation contradicted is corrected.
- [ ] No component was changed to make a fault injectable, or the change is recorded as a finding.

## Verification

Run each condition against a loaded ledger under a compressed day, capture the signature, and diff it
against the baseline. Then apply a migration mid-run and record what the customer-facing latency did
while it held its lock.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-007 Degradation is exercised, not assumed | the injected-condition catalogue and its recorded signatures |
