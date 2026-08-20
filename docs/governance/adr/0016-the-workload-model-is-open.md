# ADR 0016 - The workload model is open, and latency is measured from the intended send time

**Status:** Accepted
**Date:** 2026-08-20
**Deciders:** Karol Napiontek

## Context

WP-20 declares Tessera Bank's demand as a versioned contract and builds the engine that turns it
into a schedule. WP-21 executes that schedule against the modern spine, WP-25 against the older
strata, WP-23 records a baseline from what they measure, and WP-24 compares a soak run against it.
Every figure the strand produces rests on one decision taken here: **what generates the load, and
what the latency is measured from.**

There are two ways to build a load generator and they are not equivalent.

A **closed model** holds *N* virtual users. Each sends a request, waits for the response, thinks for
a moment, and sends the next. It is the easier thing to write, it is what most quick scripts do, and
its concurrency is trivially bounded - which is exactly why it is reached for.

An **open model** decides *before the run starts* when every request will be sent, and sends it then
regardless of what the system is doing. Arrivals are a stochastic process rather than a consequence
of replies.

## The problem with the closed model

A closed model throttles itself precisely when the system under test slows down.

Suppose the ledger's p99 doubles under contention on the audit chain's advisory lock - the ceiling
**F-27** already records as a known and unmeasured limit. In a closed model every virtual user is now
waiting twice as long before sending again, so the offered rate **halves**. The queue never builds.
The system is handed less work at exactly the moment it is failing to keep up with what it had, and
it recovers on paper. The measured latency is the latency of a system that was never overloaded,
reported as though it were the latency under load.

This is **coordinated omission**, and this repository already has a name for the shape of it. It is
the same class of defect as the `V99` truncation in WP-04: a debit of 100.01 against a balance of
100.00 computed to exactly zero, the rejection never fired, and *the run output looked entirely
plausible*. Nothing crashed. Nothing was obviously wrong. Only a test caught it.

A closed-model load report has that property in full. It produces a number, the number is in the
right units and the right order of magnitude, and it is describing a different system.

## Decision

**The engine schedules an intended send time for every event, computed from the model and the seed
alone, independent of any response.**

Concretely:

1. Arrivals are a **non-homogeneous Poisson process** whose intensity is the model's day curve, not
   a pool of looping workers. `internal/arrivals` emits `Event{Seq, At, Minute}` where `At` is an
   offset into the business day, fixed before the run begins.
2. **A driver that falls behind stays behind.** There is no feedback path from a response to the
   schedule. If the estate cannot keep up, the backlog is visible as a backlog rather than absorbed
   as a lower rate.
3. **WP-21 measures latency from the intended send time**, not from the actual send. A request that
   waited four seconds for a free connection and then took 30ms has a latency of 4.03 seconds,
   because that is what the customer experienced.
4. The schedule is **reproducible from the manifest**: seed, model digest, git SHA, scale,
   compression and window. Two runs of the same manifest are the same run, which is the whole basis
   on which WP-23's baseline and WP-24's comparison mean anything.

## Consequences

**A run can ask for more than the estate will take, and it should be able to.** At scale 1.0 the
committed model asks for around 55 000 requests a second at peak under 72x compression. Tessera Bank
will not serve that, and the planning tool says so on the page rather than leaving somebody to
discover it. Two dials exist so that a run can be brought down to something real: `scale` changes
how much demand the day contains, `compress` changes how fast it happens. The manifest records both,
because a throughput figure without them is unreadable.

**Concurrency is unbounded in principle, and the driver has to decide what to do about it.** This is
the cost of the open model and it is real: a closed model can never have more than *N* requests in
flight, and an open one can have as many as the arrival process and the system's slowness between
them produce. That decision belongs to WP-21 - a bounded pool that records what it could not send is
honest; silently dropping the backlog is not, and neither is quietly becoming a closed model by
blocking the scheduler on a full pool.

**The intensity function must be integrable and the sampler must be right.** A closed model is
correct by construction; an open one is correct only if its arrival process actually realises the
declared intensity. That is why the engine's two carrying tests are that the schedule is
byte-identical for a seed, and that the realised rate over every hour matches the declared intensity
within a stated tolerance - and why disabling the thinning step fails the second one by 3 000%.

**Nothing in this repository can validate the choice against a real system yet.** WP-21 is the first
package that sends anything. Until it exists, this ADR records a decision taken on reasoning rather
than on evidence, which is the honest position - and the reasoning is the one **F-27** already
applies to the audit chain's throughput ceiling: worth revisiting only with a measured number, not a
hunch. The difference is that a closed model would guarantee the measured number was wrong.

## Alternatives considered

**A closed model with a fixed thinking time.** Rejected above. Worth naming that it is not rescued by
tuning: any feedback from response to send rate reintroduces the same coupling, and a thinking time
long enough to dominate the response time makes the concurrency bound meaningless anyway.

**A constant-rate open model.** Correct on the coordinated-omission question and wrong about banks. A
flat rate exercises no diurnal shape, never reaches the peak the estate has to survive, and would
have made the whole calendar half of the model decoration. The peak-to-trough ratio of the committed
day is 32.

**Recording the schedule to a file and replaying it.** Reproducible, and it makes the schedule an
artefact somebody can inspect - which is genuinely attractive. Rejected on size: a day at scale 1.0
is 21 million events, and a driver that has to materialise one before sending the first request has
the wrong shape. The stream is lazy and the manifest is the artefact instead, which reproduces the
schedule exactly without storing it.
