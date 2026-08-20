# ADR 0014 - The movement file is its own unique constraint

**Status:** Accepted
**Date:** 2019-04-11
**Deciders:** Integration Architecture Board, Tessera Bank

> **Back-dated ADR - a historical reconstruction.** This decision was taken in 2019 and this document
> reproduces the reasoning of that year, in the idiom of that year. The date is not a real one. See
> [Back-dated ADRs](README.md#back-dated-adrs). A closing note written in 2026 records what actually
> followed.

## Context

The new ledger publishes a transfer-posted event to Kafka. The ESB adapter consumes it, tells the
Customer Master over SOAP, and must then hand the same transfer to the core - which in 1995 means
appending two fixed-width `MOVEREC` records to a file the overnight cycle reads at 02:00.

Three properties of that file drive everything below, and none of them is negotiable, because the
programme that reads it was written before any of us joined the bank.

- **It has no unique constraint.** `ACCTPOST` never looks at `MOV-TRANSFER-REF`. It match-merges on
  `MOV-ACCT-REF` and applies whatever it finds, so a movement written twice is applied twice and
  nothing anywhere reports it. The account is simply wrong the next morning.
- **It has no transaction.** A process that dies half way through an append leaves a partial record.
  The sort step refuses a file whose length is not a multiple of 120 and abends the cycle - not here,
  where the cause is visible, but at 02:00, in another team's job, naming another program.
- **It is not ours alone.** More than one instance of the adapter will run, because the platform team
  deploys everything in pairs.

Meanwhile delivery from Kafka is **at-least-once**, so the same transfer will arrive more than once
as a matter of routine, not as an incident.

The obvious answer is to reuse the one we already have. `NotifyTransferPosted` is idempotent: the
Customer Master claims each transfer with a unique index and answers `alreadyApplied`. Writing the
file only when that answer is false costs one line.

It is wrong, and the reason is a five-millisecond window. If this process dies after the SOAP call
returns and before the record reaches the disk, the redelivery is told the transfer was already
applied, writes nothing, and the core never hears about the payment **at all**. Nothing detects it
until reconciliation, and reconciliation is a report somebody reads, not a control that stops
anything. We would have built a mechanism whose failure mode is losing money quietly.

## Decision

**The movement file is its own unique constraint, and the append is made atomic by hand.**

Before appending, the adapter takes an exclusive lock on the file and looks for the transfer
reference among the records already there. Fixed width makes that a seek rather than a parse:
`MOV-TRANSFER-REF` is always the first twenty bytes of every hundred and twenty, and the search is
over that field only - never over the file as text, because a remittance reference is free text and
may perfectly well quote a transfer reference.

The look-up and the append happen under the same lock, so the answer cannot go stale between them.
The lock is a file lock, which is what keeps two instances apart; inside one process a monitor per
file does the same job, because a second file lock from the same JVM fails rather than waits.

Both legs go out in one write and are forced to disk together. Any failure truncates the file back
to the length it had before the attempt. A file that is *already* not a whole number of records is
refused outright rather than appended to - something else corrupted it, and adding good records to a
bad file only makes the abend harder to explain.

Two things this decision explicitly does **not** do:

- **It does not sort.** The copybook says the file is ascending by `MOV-ACCT-REF`; `STEP010` of the
  cycle is what puts it in that order, and that is the entire reason that step exists. Sorting here
  would duplicate it and would stop working the day the file outgrows one process's memory.
- **It does not keep a record of its own.** There is no table, no index, no local store of what has
  been sent. The file is the record. A second store would be a second source of truth about the
  bank's money, able to disagree with the first.

## Consequences

**What becomes easier.** The write is idempotent and crash-safe against exactly one question: is this
transfer in the file? That question has the same answer after a crash as before one, which is what
makes recovery automatic rather than manual. The overnight cycle can no longer abend on a partial
record this component produced. And the adapter stays stateless - it can be redeployed, scaled or
moved between hosts with nothing to migrate.

**What becomes harder.** The duplicate check reads the file, so it is linear in the file's size. For
a day's movements that is nothing; for a file an order of magnitude larger it is unmeasured, and the
honest position is that we have not measured it. If it ever matters, the answer is an index beside
the file, not a store inside the adapter.

The file lock also serialises the adapter's instances against one another. That is correct - they are
appending to one file - but it means throughput here is one writer's throughput no matter how many
instances run. Scaling the adapter scales the SOAP hop and the transformation, not this.

**What we are committed to.** That `MOV-TRANSFER-REF` stays the first field of the record and stays
unique per transfer. If a future release reuses a transfer reference, or moves the field, this
mechanism silently stops working - so the copybook's field order is now load-bearing for correctness
and not only for layout.

## Alternatives considered

**Trust `alreadyApplied` from the SOAP call.** One line, no scan, and it loses the movement entirely
in the window described above. Rejected: the failure is silent and the loss is permanent.

**A file per transfer, moved into a spool directory by atomic rename.** Uniqueness is the filename
and atomicity is the rename, both free from the filesystem, and both stronger than what we built. It
was rejected because the core does not read a spool directory - it reads one file - so somebody would
have to concatenate the spool before the sort, which means changing the overnight cycle. We are not
changing a 1995 batch schedule to make a 2019 component's life easier.

**A de-duplication table in the adapter's own database.** The adapter has no database, and giving it
one to solve this would make it stateful and would put a second record of the bank's money in a
component that is supposed to be a pipe.

**Write first, call the Customer Master afterwards.** Symmetrical on paper and much worse in
practice. A movement written for a transfer the system of record then refuses tells 1995 that money
moved when 2011 says it did not, and nothing reverses a record already in tonight's file.

---

## Closing note, 2026

The decision held and the window it was written to close turned out to be the interesting part.

`integration/esb-adapter` implements exactly this, and the test that pins it is the one that would
have passed under the rejected alternative: a transfer the far end reports as `alreadyApplied`, whose
record is *not* in the file, is written anyway. Making the bridge trust `alreadyApplied` fails that
test and nothing else - which is a fair measure of how easily the cheaper design would have shipped.

What 2019 did not anticipate: the same reasoning is now the estate's general answer to writing into a
tier that offers no constraint to borrow. WP-11a could delegate idempotency to the Customer Master's
unique index and deliberately kept no record of its own; this half could not, and the difference is
not that one component is more careful than the other. It is that a relational store hands you a
constraint and a file does not, so you build one out of what the file already has.

The linear scan remains unmeasured, as the consequences section admits. It is the workload strand -
WP-20 to WP-25 - that will produce a number, and F-27 already records the estate's rule for exactly
this kind of claim: worth revisiting only with a measured number, not a hunch.
