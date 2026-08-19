# ADR 0006 - Keep the edge rate limit in the process, and say so

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

WP-12 requires rate limiting "per client and per endpoint", and in the same breath requires the
gateway to be **stateless**, so that a platform repository can run as many instances of it as it
likes without a session store.

A rate limiter is state. The two requirements cannot both be satisfied in full, and the interesting
question is which way the gap is closed and whether the closing is visible.

A limit that is genuinely global needs a counter every instance can see: Redis, or the rate-limiting
service a real bank buys. Neither can live here. [ADR 0001](0001-source-only-repository.md) makes
this repository application source and governance configuration only - no Compose file, no manifest,
no infrastructure - so a shared store would be a dependency on something the repository is not
allowed to describe how to run.

The alternative that is genuinely available is a token bucket in memory, per instance. It enforces a
limit; it does not enforce *the* limit. Run four gateways and a caller gets four times the configured
rate, and which instance they land on decides which bucket they spend.

## Decision

**A token bucket in this process, keyed by subject and route class, and the arithmetic stated in
plain words wherever the limit is described.**

The package documentation, the component README and this record all say the same sentence: *n*
instances permit *n* times the configured rate.

Three details that follow from the key rather than from the storage:

- **The key is the token's subject, never the client address.** Two customers in one branch share an
  address and must not share a budget. An IP address is also personal data under GDPR, and this
  repository holds none anywhere.
- **The route class, not the path.** The path carries an account reference, so keying on it would
  give every account its own bucket and no limit at all.
- **Buckets expire.** A map keyed by subject grows with every distinct caller the gateway ever sees.
  The sweep is driven by the clock rather than by a request count, because a limiter that sweeps
  every *n* requests never sweeps once the traffic that filled it has gone - which is exactly the
  state an abusive burst leaves behind.

## Consequences

**What becomes easier.** The gateway scales horizontally with no coordination, no shared dependency
and no store to operate. Behaviour under test is deterministic: the limiter takes its clock as a
parameter, so a refill is asserted rather than slept through.

**What becomes harder, and who pays.** The configured rate has to be divided by the expected instance
count by whoever sets it, and it is wrong whenever the fleet is scaling. A caller with a valid token
who wants the full *n* times the rate needs only to spread their requests, and nothing here stops
them. This is a real limit on what the control is worth, and it is the reason the sentence about *n*
instances is repeated in three places rather than mentioned once.

**What would change the decision.** A platform repository that supplies a shared counter. The limiter
is one small interface away from taking one - `Allow(key) (bool, time.Duration)` is the whole
surface - but the seam was not built speculatively, because an interface with one implementation and
no second caller is a guess about the future rather than a design.

## Alternatives considered

**A shared store.** The correct answer, and unavailable here. Deferred to the platform repositories
rather than half-built.

**Limiting at the load balancer instead.** Moves the problem out of this repository, which is
convenient for the repository and useless for the estate: the load balancer cannot read a token, so
it can only limit by address - which is the key this decision has already rejected.

**Not limiting at all until a shared store exists.** Leaves the ledger's connection pool as the only
thing between one enthusiastic client and every other customer. A partial limit that is described
accurately is worth more than no limit described optimistically.
