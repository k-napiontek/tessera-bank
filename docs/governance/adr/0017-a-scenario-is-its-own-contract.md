# ADR 0017 - A failure scenario is its own contract, not a field on the day model

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Karol Napiontek

## Context

WP-24 makes a run worth watching. A load run against a healthy system answers one question - how fast
is it - and that is the least interesting question in operations. What a platform engineer needs to
practise is recognising a failure from its **signature**: which graph moves first, which one moves
misleadingly, and which stays flat while the customer experience collapses.

So the estate has to be degraded on purpose, and the degradation has to be declared somewhere. The
package's own Constraint fixes half of that already: *a condition is a scenario in the model, not a
branch in the driver*, because a flag in the driver is neither reproducible, nor comparable against a
baseline, nor available to WP-25's second driver without being rewritten.

What it does not fix is **which** contract the scenario goes in. `contracts/workload/` already holds
one - `tessera-day-v1.json`, the bank's day - and adding a `scenarios` array to it is one line of
schema and no new checker. That is the decision this ADR takes, because the cheap answer is wrong for
a reason that is invisible until somebody tries to compare two runs.

## The problem with putting a scenario on the day model

**Every manifest in the workload strand records the model digest, and that is the whole point of it.**
`internal/model` computes a SHA-256 over the decoded model and `internal/manifest` writes it into the
run record. WP-21's replay proof, WP-22's dataset manifest and WP-23's committed baseline all carry
the same digest, `d887de99…`, and that is what licenses the sentence *"these two runs were driven by
the same demand"*. It is the reason a baseline is a baseline rather than an old number.

A scenario added to `workload-model.schema.json` would change `tessera-day-v1.json`. The digest would
change with it, and `modelVersion` would have to be raised - for a reason that has nothing to do with
demand. Every committed baseline would then have been captured under a model that no longer exists,
and the strand would lose the only mechanical answer it has to *"is this a regression or a different
day?"*. Worse, it would lose it **silently**: the run would still work, the report would still print,
and the two figures would still line up in a table. The same shape as the WP-04 `V99` truncation and
the closed-model latency in [ADR 0016](0016-the-workload-model-is-open.md) - a plausible number
describing a different thing.

The two subjects also have different lifetimes. A day model changes when the bank's demand changes,
which is rarely and deliberately. A scenario catalogue grows every time somebody thinks of a new way
to break the estate, which is often and cheaply. Binding the second to the first taxes every new
condition with a version bump of the artefact every measurement is anchored to.

And they are different statements. The day model states **demand** and names no host, no URL, no
topic and no file, because a model that knew where to send a request would be half a driver. A
scenario states **degradation**, and degradation is inherently about a named thing: a broker to pause,
a pool to starve, a process to stop.

## Decision

**A failure scenario is declared in its own contract pair, versioned and digested separately from the
day model.**

1. `contracts/workload/scenario.schema.json` and `contracts/workload/tessera-scenarios-v1.json`,
   catalogue id `TB-SCENARIOS-V1`, beside the day model rather than inside it. Both live under
   `contracts/workload/`, so the package's Constraint - injection is declared in the workload contract
   - is satisfied either way.
2. `contracts/check-workload-scenarios.py`, wired into `contracts/validate.sh` beside the five checks
   already there, importing the shared JSON Schema subset from `check-workload-model.py` rather than
   copying it.
3. **`tessera-day-v1.json` does not change, and neither does its digest.** Every WP-21, WP-22 and
   WP-23 manifest stays comparable with everything WP-24 and WP-25 produce.
4. A scenario names the objectives it is expected to **move** and the ones expected to stay **flat**,
   both resolved against `contracts/slo/tessera-slo-v1.json` by the checker. A run manifest records
   the scenario id and the scenario digest alongside the model digest, so a degraded run says which
   degradation it was under.

## Consequences

**A signature becomes something a check can fail rather than something a write-up asserts.** This is
the half that pays for the extra contract. `workload-report` already evaluates objectives out of
`contracts/slo/`, so a scenario carrying both lists lets the report state, per objective, whether
what happened is what was declared. The flat list is the load-bearing half: the interesting claim
about a rate-limit storm is not that refusals rose - of course they did - but that
`SLO-GATEWAY-AVAILABILITY` stayed at 1.0 while customers were being turned away. WP-23 made the same
move one layer down, separating the chain wait from the account lock; a record of what moved alone
would have proved nothing about which of the two was the ceiling.
[ADR 0012](0012-slo-catalogue-boundary.md) names the dangerous direction: a claim nobody checks still
looks like a control.

**A scenario that moves nothing has to say so in the contract.** Where no catalogued objective
responds to a condition, the scenario carries an empty move list and a written reason, the same shape
as `noObjectiveBecause` on an SLO signal. That converts "the estate cannot see this" from an omission
into a declaration, and it is the finding rather than a gap in the work.

**Two documents in one directory now describe two different things**, and a reader has to know which.
`contracts/workload/README.md` carries the distinction in its Contents table, and the day model's own
`summary` says it states demand.

**A third hand-written checker joins `contracts/validate.sh`.** **F-68** already records that the
JSON Schema subset is hand-written and that the next contract family wanting a keyword pays for it in
that one place. This family wants none: `scenario.schema.json` is written inside the vocabulary the
shared subset already asserts, which is a real constraint on its shape - per-condition parameter rules
cannot be expressed as `oneOf` and are asserted by the checker instead.

**The boundary ADR 0012 draws applies here too.** A scenario says what is degraded and which
objectives should respond. It does not say who to notify, at what severity, or on which dashboard -
the checker holds the same denylist for the same reason, because a boundary that is easy to agree
with is eroded one convenient field at a time.

## Alternatives considered

**A `scenarios` array on `workload-model.schema.json`.** Rejected above. Cheapest to write and it
destroys the comparability the whole strand rests on. Worth naming that the damage is not the version
bump itself but that nothing would fail: the digest would simply differ, and a team comparing a WP-24
run with the WP-23 baseline would be comparing two models and calling it a regression.

**A scenario as a flag on the driver - `--inject slow-dependency --latency 1500`.** Rejected by the
package's Constraint and worth restating. It is not reproducible from an artefact, it cannot be
diffed, WP-25's driver would have to grow its own copy of the same flags, and the expected signature
would live in nobody's head but the operator's at the time.

**A scenario as a field on the SLO catalogue.** Superficially attractive, since the move and flat
lists are SLO ids and the catalogue is where those ids live. Rejected on ADR 0012's own boundary: the
catalogue declares what the software promises, and *"here is how to break it"* is not a promise. It
would also make `tessera-slo-v1.json` change every time a condition was added, which is the same
digest problem one directory over - `workload-report` reads the catalogue and the committed baseline
report is regenerated from it byte for byte.

**One contract holding both the day and the scenarios, with separate version fields.** Rejected
because a digest is over a document, not over a field. Two versions in one file give two claims and
one hash, and the hash is what the manifest records.
