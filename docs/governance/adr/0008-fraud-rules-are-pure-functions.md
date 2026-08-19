# ADR 0008 - Fraud rules are pure functions, and the recorded version covers the parameters

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

WP-13 requires two things of `edge/fraud-scoring` that pull against each other.

REQ-FRD-003, and the package's own Definition of Done: *rescoring a replayed event produces an
identical decision*. Consumption is at-least-once, so events **will** be replayed, and a decision
that changes on replay is a decision nobody can defend to a regulator months later.

And the thing every fraud engineer reaches for first: a **velocity rule**. "Five transfers from this
account in ten minutes" catches behaviour no single transfer reveals, and it is the single most
useful rule that could be written here.

The two cannot both be had. A velocity rule reads state accumulated from earlier events, so its
answer depends on what this consumer has already seen and in what order - which differs between the
original run and the replay, between two instances of the service, and before and after a restart.

There is a second, quieter version of the same problem. Even with every rule pure, the thresholds
are configuration. A decision recorded as `rules-2026.08.1` can only be reproduced from that version
if nobody has changed a threshold since - and "nobody changed it" is precisely the assumption model
risk management exists to forbid.

## Decision

**Every rule is a pure function of one event and the parameters in force**, and the published
`modelVersion` identifies both the catalogue and those parameters.

Concretely:

- A rule receives the event and the parameters, and nothing else. No clock, no randomness, no
  lookup, no memory. The out-of-hours rule reads the event's own `postedAt` rather than the wall
  clock, so the answer travels with the message.
- **No velocity rule, and no behavioural rule of any kind.** They are not deferred for effort; they
  are refused because they would make the reproducibility claim false while leaving it stated.
- `modelVersion` is `<catalogue version>+<digest of the parameters>` - for example
  `rules-2026.08.1+99fe6d36`. The digest is length-prefixed before hashing, so two different
  parameter sets cannot share one: the same argument the audit chain's canonical form makes in
  [ADR 0005](0005-hash-chained-audit-trail.md).
- The rule module is checked by a test that parses it and fails if it imports `datetime`, `time`,
  `random`, `os`, `socket`, `pathlib` or `requests`. The rule that breaks this will be added by
  somebody with a good reason who has not read the docstring, which is why the check reads the
  syntax tree rather than trusting the convention.

`decidedAt` is deliberately outside the guarantee. When scoring happened is a fact about the run,
not about the transfer; freezing it to make two payloads byte-identical would be the service lying
about when it did its work. What must match on a replay is the verdict - decision, score, reason
codes and version - and that is what the tests compare.

## Consequences

**What becomes easier.** A decision can be re-derived from its own recorded version: check out that
catalogue, set those parameters, feed the event, get the same answer. Testing needs no clock control
and no fixture ordering. Two instances of the service cannot disagree, so the consumer group can be
scaled without changing what anybody is told about their payment.

**What becomes harder, and it is not small.** The rule set cannot see patterns across transfers,
which is where a great deal of real fraud lives. A single 9 990.00 payment is flagged as structuring;
ten of them in an hour look exactly like one. This service will therefore miss things a stateful
scorer would catch, and that is a stated limitation of the design rather than a gap to be quietly
closed by adding a dictionary to a rule.

**What would change the decision.** A feature store - somewhere that account-level aggregates are
computed, versioned and *addressable as of a point in time*. A rule reading "transfers in the last
ten minutes **as of the event's own timestamp**" from such a store is pure again, because the answer
is a function of the event and a versioned dataset rather than of consumer history. That is a real
component with real operational weight, and it belongs to a package that can carry it.

## Alternatives considered

**Velocity rules with an in-memory window.** Cheap, useful, and it makes the Definition of Done's
third box unstickable. The failure would be silent: decisions would simply differ on replay, and
nothing would report it.

**Velocity rules, and drop the reproducibility requirement.** An honest trade in some contexts, but
the requirement comes from model risk management rather than from engineering taste, and dropping it
is not this package's call.

**Version the catalogue only, and treat thresholds as deployment detail.** What most services do.
It leaves the recorded version unable to answer the only question ever asked of it - *why was this
payment flagged, and what would it score today* - whenever a threshold has moved in between.
