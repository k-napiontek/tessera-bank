# ADR 0015 - The cut-off is the movement file, not a timestamp

**Status:** Accepted
**Date:** 2026-08-20
**Deciders:** Karol Napiontek

## Context

WP-16 compares the COBOL account master against the PostgreSQL ledger every morning and reports the
differences. Most of that is arithmetic. The part that is not is deciding **which differences are
expected**, because the two systems are never meant to agree completely: the ledger accepts postings
continuously, the master is rewritten once a night, and everything posted between the cycle's input
being cut and the reconciliation running is legitimately in one and not the other.

Getting that wrong is not a cosmetic defect. The Constraints section of the work package says so
outright: *classifying a timing difference as a break trains operators to ignore the report, which
is worse than having no report.* A control that is ignored still passes its audit and stops working.

Five documents in this repository - the work package, `batch/recon`'s README, the outbox-backlog
runbook, WP-20 and WP-25 - refer to "the mainframe cut-off" as if it were a defined thing. **None of
them defines it.** WP-16 is the package that has to.

The obvious answer is a time. It does not survive contact with the estate:

- **There is no shared clock.** The ledger stamps `now()` in PostgreSQL; the overnight cycle derives
  its run timestamp from the business date precisely so that a rerun is byte-identical. Comparing
  the two means trusting two clocks to agree about a boundary that decides whether a payment is a
  break.
- **A boundary drawn at a time is not reproducible.** The work package requires the comparison to be
  *deterministic and reproducible from the same two inputs*. A cut-off of "02:00" gives a different
  answer when a posting's timestamp is 01:59:59.998 and the row commits at 02:00:00.001 - which is
  the same class of defect [ADR 0009](0009-reports-are-cut-at-an-audit-position.md) already rejected
  for reporting, where `journal_entry.created_at` looked like a usable high-water mark and was not.
- **It answers the wrong question.** What the reconciliation needs to know is not *when* a movement
  was posted. It is *whether the overnight cycle saw it*.

## Decision

**The set of transfer references in the movement file the cycle consumed is the cut-off.**

`MOVEREC` carries `MOV-TRANSFER-REF` in its first twenty bytes, and the movement file is the exact
input `ACCTPOST` applied to the master. So the file already answers the question directly:

- a ledger entry whose reference **is** in the file must have reached the master, and if the balance
  does not reflect it, that is **drift**;
- a ledger entry whose reference **is not** in the file is expected to be absent, and the difference
  it causes is **timing**.

An entry with an earlier value date is also expected in the master, because an earlier cycle applied
it. Those two conditions together are what `LedgerReader.accounts_as_at` computes as
`expected_minor`, alongside the ledger's own `booked_minor`. An account whose master balance equals
`expected_minor` is classified `TIMING`; one that equals neither figure is `VALUE_DRIFT`.

Two properties follow, and both are the reason for choosing this over a clock:

- **It is exact.** There is no window, no tolerance and no rounding. A transfer is in the file or it
  is not.
- **It is reproducible.** The same master and the same movement file give the same classification
  for ever, on any machine, regardless of what either system's clock did.

**The key field only, never the file as text.** `MOV-REFERENCE` is a remittance reference - free
text a paying customer controls - and may perfectly well quote a transfer reference. The scan is a
seek at a 120-byte stride over the first twenty bytes of each record, which the fixed width makes
cheap. This is [ADR 0014](0014-the-movement-file-is-its-own-unique-constraint.md)'s trap seen from
the reading side, and it is the same file answering a second question.

## Consequences

**What becomes easier.** The classification needs no configuration: no cut-off time to set per
environment, nothing to adjust when the batch window moves, and no argument between two teams about
whose clock is right. A break report can be re-derived years later from the two files that produced
it, which is what makes it evidence rather than a snapshot.

**What becomes harder.** The reconciliation now needs the movement file as an input, not only the
master. An operator running it by hand has to supply the file the cycle actually consumed - not
today's, not a regenerated one - and running it against the wrong file misclassifies wholesale. The
report records `cutOff.transferRefCount` for exactly this reason: a reconciliation cut against an
empty or wrong movement file shows an implausible count on its own face, where a wrong timestamp
would have shown nothing at all.

**What we are committed to.** That `MOV-TRANSFER-REF` stays unique per transfer and stays the first
field of the record. ADR 0014 already made the copybook's field order load-bearing for the writer's
correctness; this makes it load-bearing for the reconciliation's classification too. The same
sentence now protects two mechanisms in two tiers.

**What is not solved.** The cut-off answers "did the cycle see this transfer", and it answers it
from *tonight's* file. It says nothing about a movement that failed to reach the master on some
earlier night: such an entry has an earlier value date, so it is counted as expected, and it will
report as drift rather than as the missing movement it is. That is the correct outcome - it *is*
drift by then - but the report cannot say which night it came from. Reconstructing that needs the
history of movement files, which is a retention question rather than a classification one, and F-28
already records that the estate has no answer to retention.

## Alternatives considered

**A cut-off time, configured per environment.** The obvious design and the one every reader will
think of first. Rejected for the three reasons in the Context: no shared clock, not reproducible,
and it answers a question adjacent to the one that matters.

**The ledger's audit position at the moment the cycle started.** Closer, because ADR 0009 already
establishes `audit_record.seq` as this estate's reproducible boundary, and it would need no second
input. Rejected because nothing records it: the overnight cycle is a 1995 batch schedule that has
never heard of an audit sequence, and giving it one means changing stratum 0 so that a 2026
component can classify more easily. The estate's rule is the other way round - WP-11's own Out of
scope says this tier adapts what it sits between without modifying it.

**Comparing movement counts rather than references.** Cheap and nearly right, which is the dangerous
combination. Two transfers of equal value, one applied and one not, produce the same count as one
applied twice, and the second is a defect ADR 0014 exists to prevent. A set of references
distinguishes them; a count does not.

**Tolerating small differences and reporting only material ones.** A threshold looks pragmatic and
is how a reconciliation stops being a control. The estate's position is stated in WP-16's Out of
scope for correction and holds here too: every difference is reported and classified, and *timing*
is the classification that says "expected" without the report having to hide anything.
