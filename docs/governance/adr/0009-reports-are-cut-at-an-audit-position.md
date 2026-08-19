# ADR 0009 - A report is cut at an audit sequence position, not at a timestamp

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

WP-17 requires that rerunning a report for a historical date produces **byte-identical** output.
Auditability is the entire purpose of the tier: a figure a supervisor questions six months later has
to be reproducible from the ledger, and a report that cannot be reproduced is an assertion rather
than evidence.

Postings are append-only, so at first glance the requirement looks free - the rows a past date's
report read cannot have changed. They cannot have changed, but there can be **more of them**. Two
cases produce that:

- a **backdated entry** - one posted today carrying an earlier value date, which a filter on
  `value_date` alone admits on the rerun and not on the original;
- an **account opened after the first run**, which appears in the rerun with a zero balance and moves
  the extract's record count and trailer.

The failure mode is what makes this worth an ADR. The second run is not obviously wrong: every figure
is internally consistent, both control totals reconcile, the file validates against its format, and
nothing anywhere reports a discrepancy. The report is simply for a different set of rows than the one
it claims to be for.

So the report has to be bounded by a **position** as well as by a business date, and the position has
to be a value where "everything at or below this is committed" is actually true.

### Why the obvious watermarks do not work

`max(posting.id)` and `journal_entry.created_at` both look like they would serve.

Neither is a commit order. An identity value is allocated when a row is *inserted*, and `now()` is
fixed when a transaction *starts*. A transaction that begins early and commits late therefore carries
a low value while becoming visible after rows written later. A watermark on either admits, on the
rerun, rows the first run could not see - which is the exact defect being solved, arrived at by a
different route.

`pg_snapshot_xmin(pg_current_snapshot())` filtered against `posting`'s system `xmin` column is a
correct watermark. It was rejected on two counts: a stored xid becomes meaningless after wraparound,
which is precisely the horizon over which a report must stay reproducible, and a system column is
part of no contract this repository controls.

## Decision

**The ledger position is an `audit_record.seq`.** A report selects entries whose audit row satisfies
`seq <= position`, and accounts whose `ACCOUNT_OPENED` row does the same.

This works because of a property WP-09 built for a different reason. `JdbcAuditLog` takes
`pg_advisory_xact_lock` before reading the chain head and holds it to commit, so audit appends cannot
interleave. Sequence order is therefore commit order: if seq P is visible, every seq below it has
committed. The join is total because every journal entry has exactly one audit row - `Transfer`
writes `TRANSFER_POSTED` in the same transaction as the postings, `ReverseTransfer` writes
`TRANSFER_REVERSED`, and `CaptureHold` delegates its posting to `Transfer` rather than writing an
entry of its own.

Alongside the sequence, every report carries the **chain hash at that position**. The sequence says
how far along; the hash says which chain. Two databases can both hold a row at seq 4711 and only one
can hold that row with that hash, so a file re-cut against a restored database whose history diverged
is detectable rather than merely unlikely.

Two consequences are part of the decision rather than side effects:

- **Reports are summed from postings, never read from the `balance` table.** The materialised balance
  reflects now; a report built on it would change under its own feet. It also makes the
  reconciliation required by REQ-REP-003 independent rather than circular.
- **No report body carries a wall clock.** The run instant lives in the manifest beside the files. A
  generation timestamp in a report would make byte-identical reruns impossible by construction while
  looking like helpful metadata.

## Consequences

**Good.**

- Reproducibility is a property of the mechanism rather than of a ledger that happened not to change.
  `test_reproducibility.py` runs, posts both a same-date and a backdated entry, reruns at the recorded
  position and asserts the files are identical byte for byte - and was demonstrated to fail when the
  position filter is removed.
- A figure traces to the tamper-evident chain, not merely to a table. An auditor asking "which
  postings is this number made of" gets an answer with a hash on it.
- The reporting tier needs no schema change. It reads what WP-07 and WP-09 already wrote.

**Bad, and accepted.**

- **This tier now depends on the audit chain being complete.** An entry written without an audit row
  would be invisible to every report, silently. Nothing in the schema enforces the one-entry-one-audit
  -row relationship - it holds because all three use cases go through `Transfer`. A fourth write path
  that skipped the audit log would break reporting without breaking any test in `services/`.
- **The reporting join is wider than it needs to be.** Every posting query joins `audit_record` by
  `subject_ref`, which is indexed as `(subject_ref, seq)` for a different query shape. It has not been
  measured under a large history.
- **F-27's throughput ceiling is now load-bearing in a second place.** The advisory lock serialises
  every money-moving transaction, and this ADR turns that cost into a benefit reporting depends on.
  Anyone revisiting F-27 - a per-subject chain, say - has to answer this document too, because a
  per-subject chain has no single sequence and this mechanism would have nothing to cut at.

## Alternatives considered

**A closed business date with a digest check.** Report on `value_date = D`, record a digest of the
input rows, and fail on rerun if it has changed. Simple and needs nothing from WP-09. Rejected
because it *detects* irreproducibility rather than preventing it: the Definition of Done would hold
only on the days nothing was backdated, which is not what it says.

**Snapshot xmin.** Correct, but wraparound and a system column, as above.

**A reporting cut-off enforced upstream** - the ledger refusing to post to a business date once its
report has run. This is what a mainframe does and it is genuinely the strongest answer. Rejected
because it is a change to WP-08's service driven by a downstream package's convenience, and because
this estate has no batch window: `edge/api-gateway` accepts transfers at all hours by design.
