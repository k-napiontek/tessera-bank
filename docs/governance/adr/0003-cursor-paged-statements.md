# ADR 0003 - Page the statement with an opaque keyset cursor

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

WP-08 exposes the ledger over HTTP. Its statement endpoint returns the movements on an account over
an inclusive value-date range, and a busy account over a wide range returns more movements than
belong in one response.

The OpenAPI contract as WP-02 wrote it has no paging at all: `getStatement` takes `from` and `to`,
and `Statement` carries `openingBalance`, `closingBalance` and an unbounded `movements` array. WP-08
requires paging and states in its Constraints that it must be cursor-based, "never offset-based:
offsets skip and duplicate rows when data is being written concurrently, which on a statement is a
correctness bug rather than a cosmetic one."

Two things make the decision non-obvious.

**First, the failure mode is invisible in testing.** `LIMIT 100 OFFSET 200` is the reflex, it reads
naturally, and against a quiet dataset it is indistinguishable from correct. It only misbehaves when
rows are being inserted between two page reads - which on a ledger is the normal case, not an edge
case. A movement posted while a customer is paging shifts every later row by one: the row that was
about to be read is skipped, and the row already read is returned again. The customer sees a
statement that is missing a debit and shows a credit twice, and nothing in the system reports an
error. This is the same class of defect as the `PIC S9(15)` truncation WP-04 caught - plausible
output, silently wrong.

**Second, paging breaks the property that makes a statement checkable.** `Statement`'s description
says "`openingBalance` plus every movement in the range equals `closingBalance` - a statement that
does not foot is a defect, not a rounding artefact." Once the movements arrive in pages, that
sentence has to mean something new, and the choice is not cosmetic. Keeping the balances at range
scope leaves any single page unverifiable: the reader can only check the arithmetic after fetching
and concatenating every page, and a check with that precondition is a check nobody performs.

The canonical data model defines no paging concept at all, so §11 applies - the model changes before
the contracts derived from it - and a change to a wire format requires this record.

## Decision

The statement is paged with an **opaque keyset cursor**, and the balances are scoped to the page.

`getStatement` gains two optional query parameters: `cursor`, the value of the previous page's
`nextCursor`, and `limit`, capped at 500 and defaulting to 100. `Statement` gains `nextCursor`,
which is null on the last page.

The cursor encodes the sort key of the last movement on the page - `(valueDate, postedAt, entryRef,
seq)` - so the next page is fetched with a `WHERE key > cursor` predicate. That tuple is a total
order: postings are append-only, and `posting_seq_uq` makes `(entry_ref, seq)` unique. A movement
inserted between two page reads therefore lands on one side of the boundary or the other, and can be
neither skipped nor repeated.

The token is **opaque and base64-encoded**, and a cursor the service did not issue is rejected rather
than interpreted.

`openingBalance` and `closingBalance` bracket **the page**: opening is the booked balance immediately
before the page's first movement, closing immediately after its last. Each page therefore foots on
its own, and one page's `closingBalance` is the next page's `openingBalance`, so the chain is
self-proving at every step rather than only at the end.

`canonical-data-model.md` §1 gains a `Paging` convention defining `cursor` and `nextCursor`, marked
strata 3 and 4 only - stratum 0 reads sequential files end to end and stratum 1 returns whole SOAP
documents, so neither has a collection to page.

## Consequences

**Easier.** Correctness under concurrent writes stops being a matter of timing. The per-page footing
identity gives the WP-08 contract test something sharp to assert, and gives an operator investigating
a customer complaint a page that either balances or does not. The keyset predicate also stays fast at
depth, where an offset scan degrades linearly.

**Harder.** The server must maintain a total order it can seek into, and the statement query is no
longer a plain range scan. The opening balance of a page is a derived figure - the balance as at the
row before the cursor - which costs a second query per page. Clients can no longer jump to page 7;
they must walk the chain. For a bank statement that is the correct affordance, but it is a real loss
of function and is stated here rather than discovered.

**Committed to.** The cursor's opacity is now a contract: the encoding may change freely, and no
client may parse one. Should the sort key ever need to change, previously issued cursors become
invalid and must be rejected rather than misread - which the "reject, never guess" rule already
requires.

**Consumers named.** `edge/api-gateway` (WP-12) proxies this endpoint and must forward `cursor` and
`limit` untouched. `edge/web-banking` (WP-14) renders the statement and must follow `nextCursor`
rather than compute page numbers. Neither exists yet, so nothing breaks today.

## Alternatives considered

**Offset paging.** The default reflex, and the reason this ADR exists. Rejected outright: it is
wrong under concurrent writes, and wrong in a way that produces a plausible statement rather than an
error.

**Cursor paging with range-scoped balances.** A smaller contract change - only `movements` pages, and
`openingBalance`/`closingBalance` keep their present meaning. Rejected because it makes the footing
identity unverifiable per page. The check would hold only across the concatenation of every page,
which means in practice it would never be run.

**No paging: cap the range instead.** Refuse a `from`/`to` span wider than, say, 31 days and return
everything within it. Genuinely simpler, and defensible for a retail statement. Rejected because the
cap is arbitrary, a single busy day can still exceed any response size worth serving, and it pushes
the problem onto the client as a series of date-window requests - which is offset paging wearing a
different hat, with the same skip-and-duplicate behaviour at each window edge.

**Snapshot the query and page within it.** A server-side cursor or materialised result set, paged
from a consistent snapshot. Correct, and how a database cursor works. Rejected because it makes the
API stateful: the server must hold and expire per-client state, and a horizontally scaled deployment
must then route every page of a statement to the same instance. A keyset cursor achieves the same
guarantee with no server state at all.
