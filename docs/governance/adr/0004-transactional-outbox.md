# ADR 0004 - Publish domain events through a transactional outbox

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

WP-09 makes the ledger's events publishable. `contracts/asyncapi/ledger-events.yaml` has declared
`transferPosted` since WP-02, `edge/fraud-scoring` consumes it and `integration/esb-adapter` carries
it to the older strata. Until now nothing published it.

The obvious implementation is to post the transfer and then send to Kafka. It is wrong in a way that
is easy to miss, because it works every time it is tried by hand.

**Two databases, one transaction that does not exist.** PostgreSQL and Kafka are separate systems,
and there is no transaction spanning them. Whichever order the two writes are attempted, a failure
between them leaves the estate inconsistent:

- **Post, then send.** The send fails - the broker is rolling, the network drops, the process is
  killed - and the transfer is in the ledger while nothing downstream knows. Fraud never scores it,
  the ESB never carries it to the mainframe, and the next morning's reconciliation reports a break
  nobody can explain from either side.
- **Send, then post.** The transaction rolls back after the event has gone. Every downstream tier
  now acts on a transfer that does not exist: fraud scores a phantom, the ESB encodes a `MOVEREC` for
  the mainframe, and the COBOL master gains a movement the ledger has never heard of.

Neither is fixable by retrying. A retry needs to know what the other system did, and that is exactly
what is unavailable in the window. This is the dual-write problem, and the only real answer is to
stop dual-writing.

The alternative usually proposed - two-phase commit across PostgreSQL and Kafka - is discussed under
Alternatives. It is not available in practice and would not be chosen if it were.

## Decision

**The event is written to a table in the ledger's own database, in the same transaction as the
postings. A separate relay publishes from that table afterwards and marks each row dispatched.**

Concretely:

- `outbox_record` (migration `V8`) holds the topic, the partition key, the payload and a
  `dispatched_at` that is null until the broker has acknowledged it.
- `EventOutbox` is a port. `Transfer` and `ReverseTransfer` call it inside the transaction they are
  already in; the adapter opens none of its own and talks to no broker.
- `OutboxRelay` claims pending rows with `FOR UPDATE SKIP LOCKED`, **publishes, and only then marks**.
- `KafkaEventPublisher` awaits the broker's acknowledgement before returning, keyed by `transferRef`.

**Delivery is therefore at-least-once, and that is a published property of the interface, not an
implementation detail.** The relay can die between the broker's acknowledgement and the mark, and the
next pass republishes. The AsyncAPI document has stated this since WP-02 and requires every consumer
to de-duplicate on `transferRef`.

## Consequences

**What becomes easier.** An event cannot describe a transfer that did not commit, because the row
carrying it would not have committed either. A transfer no longer waits on Kafka, so broker
unavailability delays announcements instead of failing customer requests. Relaying scales with the
number of instances, because skip-locked lets each take rows the others are not working on.

**What becomes harder.** Every consumer must be idempotent, and that cost is paid once per consumer
rather than once here. Events are announced within about a relay interval rather than instantly.
There is now a backlog that can grow, so it has to be watched - `docs/runbooks/outbox-backlog.md`,
and the outbox-lag metric WP-09's observability half publishes.

**What we are committed to.** The outbox table is part of the ledger's schema and its retention is
the ledger's problem. Nothing prunes it yet; that is a follow-up rather than an omission, because a
sweep that deletes dispatched rows is easy and a sweep that deletes the wrong ones is a lost event.

## Alternatives considered

**Publish from the request thread, after the commit.** The default reflex. Rejected because the
window is not small enough to ignore in a ledger: the failure it produces is a transfer nobody
downstream knows about, discovered by reconciliation the next morning, and unrecoverable without
replaying from the journal by hand.

**Two-phase commit (XA) across PostgreSQL and Kafka.** The textbook answer, and it does not exist
here: Kafka has no XA resource manager. Even where 2PC is available it trades an unlikely
inconsistency for a likely unavailability, because an in-doubt transaction holds locks until a
coordinator that may itself be down resolves it. A bank cannot hold row locks on customer accounts
waiting for a coordinator.

**Change data capture from the WAL (Debezium).** A genuine alternative, and the closest one. It
removes the relay and the table entirely: the event is derived from the postings themselves. Rejected
for this repository because it moves the interesting logic into infrastructure configuration, which
ADR 0001 keeps in the companion platform repositories - the payload shape would be defined by a
connector's transform rather than by code that can be read and tested here. The outbox keeps the
contract's shape in application source, where the AsyncAPI document can be enforced by a test.

**Publish and mark in the other order.** Not a design so much as a mistake worth naming, because the
code reads perfectly well. Marking first turns every failed publish into a permanently lost event,
since the row already claims to be done. `OutboxRelayTest` fails on this mutation, which is how the
ordering is kept.
