# ADR 0018 - The migration under traffic is an exercise of its own, not a catalogue condition

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Karol Napiontek

## Context

WP-24b applies a Flyway migration to a live ledger while a compressed bank day is being driven at it.
`master-plan.md` names *"schema migration under load"* as one of the reasons this repository exists,
and until this package nothing here let anyone attempt it.

WP-24's Constraint says *a condition is a scenario in the model, not a branch in the driver*, and
[ADR 0017](0017-a-scenario-is-its-own-contract.md) decided where such a scenario lives:
`contracts/workload/scenario.schema.json` and `tessera-scenarios-v1.json`, the catalogue
`TB-SCENARIOS-V1`. Seven conditions are declared there, each naming the objectives it is expected to
**move** and the ones expected to stay **flat**, which is what turns a signature from prose into
something `workload-report --scenario` can fail.

A migration that blocks every write to `posting` for the length of a `CREATE INDEX` is a degradation
by any reasonable reading. So the obvious thing is to declare it as an eighth condition, get the
moves/flat machinery for free, and let `signatures.sh` sweep it with the other seven.

That is the decision this ADR takes, and it takes the other one.

## The mechanical reason

**`Catalogue.Digest()` covers the whole decoded catalogue, not one scenario.**
`internal/scenario/scenario.go` computes a SHA-256 over the decoded `Catalogue`, `internal/manifest`
writes it into every run record as `scenarioDigest`, and `cmd/workload-report/main.go` refuses a run
whose manifest disagrees with the catalogue it was handed:

> the catalogue digest is X and the run was executed under Y - this is a different catalogue from the
> one that produced the run, so the signature it declares is not the one that was asserted

That refusal is right, and it is the whole reason a signature is an assertion rather than a
description. But its granularity is the catalogue. All seven captures committed under
`workload/baselines/signatures/` pin `c490785f1019…`, and **adding an eighth scenario changes that
digest for all of them**. Every one of the seven would stop being reportable against the catalogue in
the tree - not because anything about those seven changed, but because something was added beside
them.

`workload/baselines/README.md` rests on the opposite claim:

> The scrapes are committed because without them the report cannot be regenerated, and a report
> nobody can regenerate is one nobody can check.

Trading that away to reuse a prediction mechanism would be paying for a feature with a control.

## The reason from the work package

WP-24's In scope lists these as two separate bullets, not one:

- *"A catalogue of injected conditions, each declared as a scenario in the workload contract: a slow
  downstream dependency, a partial outage, a stuck outbox row, consumer lag, a rate-limit storm,
  connection-pool exhaustion, and a clock skew across the batch boundary. (24a)"*
- *"**A Flyway migration applied to a live ledger under traffic**, with the lock it takes and the
  latency it causes recorded. (24b)"*

Seven conditions are enumerated and the migration is not among them. Task decision 5's phrase *"the
migration under traffic is the scenario's own, not the ledger's"* is about **whose migration set the
SQL file belongs to** - the exercise's rather than `services/ledger-persistence`'s - and its own
sentences say so: *"The SQL lives under `workload/` and is applied against the running ledger with its
own Flyway history table."* It is not a statement about the catalogue.

## Decision

**The migration exercise is declared by its own committed script, SQL and capture, and
`TB-SCENARIOS-V1` is untouched.**

- `workload/migrations/blocking/` and `workload/migrations/concurrent/` hold one migration each.
- `workload/internal/migration` applies one to a running estate and records what it did.
- `workload/scripts/migration.sh` drives the whole exercise, composing `estate-up.sh` exactly as
  `baseline.sh` and `signatures.sh` do.
- `workload/baselines/migration/<variant>/` is the capture, stating its own conditions.

The Constraint is satisfied on its own terms. What it forbids is *a branch in the driver*, because a
flag is not reproducible, cannot be diffed against a baseline, and would have to be written twice
when WP-25 arrives. A committed SQL file, a committed script, a pinned business date and a committed
capture are none of those things. `workload-run` gained no flag and `internal/runner` gained no
branch; the exercise runs beside the driver rather than inside it.

## What is lost, and what replaces it

**The moves/flat prediction is lost.** A catalogue scenario declares before the run which objectives
it expects to move, and `workload-report --scenario` prints `as declared` or `CONTRADICTED` against
that. The migration exercise has no such declaration, so its report says what happened without having
committed in advance to what should.

What replaces it is narrower and, for this exercise, sharper: **the two variants are each other's
control.** The same index, the same table, the same day, the same dials - one built with `CREATE
INDEX` and one with `CREATE INDEX CONCURRENTLY`. Whatever differs between the two captures is a
consequence of that one keyword. A prediction written by the same person who wrote the migration
would have been weaker evidence than a pair that differs in exactly one place.

The customer-side figures are still the catalogue's own. `workload-migration` evaluates
`SLO-GATEWAY-LATENCY` and `SLO-GATEWAY-AVAILABILITY` through `internal/slo` against
`contracts/slo/tessera-slo-v1.json`, over a scrape pair that brackets **the migration** rather than
the run - so no threshold is invented here, which is what
[ADR 0012](0012-slo-catalogue-boundary.md) exists to prevent.

## Consequences

- The seven WP-24c captures stay regenerable from what is committed beside them.
- `signatures.sh` keeps its seven pinned dates and its count check, unchanged.
- The migration exercise cannot be swept by `signatures.sh`; it has its own script, which it needed
  anyway, because it is the only exercise here that samples `pg_locks` while it runs.
- **The digest's granularity stays a live problem**, and it is recorded as a follow-up rather than
  fixed here: any future addition to `TB-SCENARIOS-V1` will invalidate every capture taken before it,
  for the same reason this ADR avoided. A digest per scenario rather than per catalogue would fix it,
  and that is a change to `internal/manifest`, `internal/scenario`, `workload-report` and seven
  committed manifests - which is its own package, not a side effect of this one.

## Alternatives considered

**Add the eighth scenario and accept the invalidation.** Rejected. The seven captures are WP-24c's
entire deliverable and were merged four commits ago; breaking their regenerability to add a feature
to the eighth is a poor trade, and one nobody reading the catalogue would have seen coming.

**Add the eighth scenario and fix the digest granularity in the same change.** Rejected as scope. It
is the right fix and it is not WP-24b's - the package's own Out of scope says findings become their
own change, and rewriting seven committed manifests inside a package about migrations and soak runs
would produce a pull request nobody could review as one thing.

**Keep the migration out of the contract entirely and out of any script - run it by hand.** Rejected.
It would be reproducible by nobody, comparable against nothing, and would have to be reinvented the
next time somebody asked what a migration costs. The Constraint's actual objection to a flag applies
to a manual step just as well.
