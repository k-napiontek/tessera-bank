# Query plans at volume

**Owned by WP-22** | Captured 2026-08-21 | PostgreSQL 16 (`postgres:16-alpine`), default configuration

Every query in this repository had, until this document, only ever run against about three accounts.
The plans they get at that size say nothing about the plans they get at a year of postings, and
[**F-24**](../plan/STATUS.md) has been open since WP-08 asking for exactly this evidence.

Reproduce it with:

```bash
bash services/ledger-loader/scripts/load-dataset.sh \
  --customers 150000 --from 2025-09-01 --to 2026-05-08 --scale 0.0017 --seed 42
bash services/ledger-loader/scripts/capture-plans.sh
```

The queries are transcribed into [`plans.sql`](../../services/ledger-loader/scripts/plans.sql) and the
transcription is pinned by `QueryPlanSourceTest`, which fails if a fragment stops appearing in the
`JdbcLedgerReadModel` or `batch/reporting` source it came from.

## What it was measured at

| Relation | Rows |
|---|---|
| `account` | 300 001 |
| `journal_entry` | 2 420 043 |
| `posting` | 4 840 086 |
| `audit_record` | 2 854 025 |
| `balance` | 300 001 |
| `hold` | 114 661 |

250 business dates, 2025-09-01 to 2026-05-08, from the committed workload model at seed 42, scale
0.0017, over 150 000 customers holding two accounts each. Loaded in 365 s. Database 2.1 GB.

**Two accounts are used below and the difference between them is the finding.**

| | Postings | What it is |
|---|---|---|
| `TB000000000066LY` | 59 | The busiest **customer** account the draw produced |
| `TB00000000006FHC` | 300 000 | The treasury: every opening balance was debited from it |

Fifty-nine is shallow, and that is a property of this dataset rather than of the estate: 2.1 million
transfers spread over 300 001 accounts give each about seven sent and seven received. The decision
log recorded that risk before the load and named the alternative - a depth-targeted profile - as a
follow-up rather than a silent substitution. What the load did produce is a genuinely deep account,
and it is the bank's own.

---

## 1. The statement page

### At 59 postings: 0.48 ms, and the index does its job

```
 Limit  (cost=351.87..351.93 rows=24 width=109) (actual time=0.456..0.459 rows=50 loops=1)
   Buffers: shared hit=153 read=157
   ->  Sort  (cost=351.87..351.93 rows=24 width=109) (actual time=0.455..0.456 rows=50 loops=1)
         Sort Key: e.value_date, e.created_at, e.reference, p.seq
         Sort Method: quicksort  Memory: 32kB
         ->  Nested Loop  (cost=0.86..351.32 rows=24 width=109) (actual time=0.027..0.415 rows=58 loops=1)
               ->  Index Scan using posting_account_ix on posting p  (actual time=0.010..0.140 rows=59 loops=1)
                     Index Cond: ((account_ref)::text = 'TB000000000066LY'::text)
               ->  Index Scan using journal_entry_currency_uq on journal_entry e  (actual time=0.004..0.004 rows=1 loops=59)
                     Index Cond: ((reference)::text = (p.entry_ref)::text)
                     Filter: ((value_date >= '2025-09-01'::date) AND (value_date <= '2026-05-08'::date))
 Execution Time: 0.483 ms
```

`posting_account_ix` narrows to the account, the entries are fetched one at a time, and the whole
account is sorted in 32 kB of memory. **At this depth F-24 costs nothing**, and saying so is part of
the answer: the follow-up asked for a measurement, and a measurement that only reported the bad case
would be an argument rather than a measurement.

### At 300 000 postings: 2.5 s, and the index is abandoned

```
 Limit  (cost=6.37..283.35 rows=50 width=109) (actual time=2512.066..2512.067 rows=0 loops=1)
   Buffers: shared hit=8426235 read=115075
   ->  Incremental Sort  (cost=6.37..1588171.04 rows=286695 width=109) (actual time=2512.065..2512.066 rows=0 loops=1)
         Sort Key: e.value_date, e.created_at, e.reference, p.seq
         Presorted Key: e.value_date, e.created_at, e.reference
         ->  Nested Loop  (cost=0.86..1575269.76 rows=286695 width=109) (actual time=2512.033..2512.034 rows=0 loops=1)
               ->  Index Scan using journal_entry_statement_ix on journal_entry e  (actual time=0.015..186.561 rows=2120043 loops=1)
                     Index Cond: ((value_date >= '2025-09-01'::date) AND (value_date <= '2026-05-08'::date))
               ->  Index Scan using posting_seq_uq on posting p  (actual time=0.001..0.001 rows=0 loops=2120043)
                     Index Cond: ((entry_ref)::text = (e.reference)::text)
                     Filter: ((account_ref)::text = 'TB00000000006FHC'::text)
                     Rows Removed by Filter: 2
 Execution Time: 2512.085 ms
```

**This is F-24, and it is worse than F-24 predicted.** The follow-up says the sort happens after the
join and one index narrows it. What actually happens once an account is deep enough is that the
planner stops using `posting_account_ix` at all: sorting a quarter of a million rows looks more
expensive than reading `journal_entry_statement_ix` in the order the `ORDER BY` already wants, so it
drives from `journal_entry` and probes `posting` for every entry in the date range. Two million
index probes and 8.4 million buffer hits, to answer a question about one account.

The cost is then a function of **the date range**, not of the account. That is the part a reader
should take away: at this shape the statement page stops being a per-account query.

The treasury's own postings all fall on the opening date, outside the requested range, so the page
returns nothing - which does not soften the finding, it sharpens it. Two and a half seconds and 8.4
million buffers were spent proving there was nothing to return.

### The keyset predicate is never an index condition

At either depth, and with or without a cursor:

```
 Filter: (... AND (ROW(value_date, created_at, (p.entry_ref)::text, p.seq)
                 > ROW('2026-01-09'::date, '2026-01-09 09:15:46.715+00'::timestamptz,
                       'TB202601090000005247'::text, 2)))
```

The row-wise comparison is written exactly as an index would compare tuples, and no index exists that
covers those four columns in that order, because two of them live on `journal_entry` and two on
`posting`. So it is applied as a filter after the rows have been assembled. **A keyset cursor whose
predicate is a filter is a keyset cursor that has to read what it skips**, and page *n* costs what
pages 1 to *n* cost.

### What F-24 should do about it

F-24 already names the change: denormalise `value_date` and `created_at` onto `posting`, so that one
composite index on `(account_ref, value_date, created_at, entry_ref, seq)` serves the filter, the
order and the cursor together, and the join disappears from the hot path.

**This package does not make that change**, and its Out of scope says so: an index or a column on
`posting` is a change to WP-07's migration set, not an addition from a loader's branch. F-24 stays
open and now carries the measurement it asked for.

---

## 2. The balance read: healthy

```
 Index Scan using balance_pk on balance b  (actual time=0.057..0.057 rows=1 loops=1)
   Index Cond: ((account_ref)::text = 'TB000000000066LY'::text)
   SubPlan 1
     ->  Aggregate  (actual time=0.040..0.040 rows=1 loops=1)
           ->  Index Scan using hold_account_ix on hold h  (actual time=0.016..0.016 rows=1 loops=1)
                 Index Cond: (((account_ref)::text = (b.account_ref)::text) AND ((status)::text = 'PLACED'::text))
 Execution Time: 0.066 ms
```

`balance_pk` for the booked figure and `hold_account_ix` for the holds behind `Balance.of`. Both
indexes are used as intended, at 300 001 balances and 114 661 holds. Nothing to do.

The cumulative sum behind a statement's opening balance is the same shape as the statement page and
inherits its behaviour: 0.12 ms at 59 postings, driven by `posting_account_ix`.

---

## 3. `batch/reporting`

### Movements on a business date: 43 ms

```
 Gather Merge  (actual time=36.932..42.638 rows=22088 loops=1)
   Workers Planned: 2   Workers Launched: 2
   ->  Nested Loop ...
         ->  Parallel Bitmap Heap Scan on journal_entry je  (actual time=0.161..0.387 rows=3681 loops=3)
               Recheck Cond: (value_date = '2026-05-08'::date)
               ->  Bitmap Index Scan on journal_entry_statement_ix  (actual time=0.420..0.420 rows=11044 loops=1)
         ->  Index Scan using audit_record_subject_ix on audit_record ar  (actual time=0.001..0.001 rows=1 loops=11044)
               Index Cond: (((subject_ref)::text = (je.reference)::text) AND (seq <= 2854025))
 Execution Time: 43.170 ms
```

`journal_entry_statement_ix` for the date and `audit_record_subject_ix` for the position bound, both
used as designed. The `subject_ref` join **F-43** warns about is an index lookup per entry rather
than a scan, which is why WP-09's index on `(subject_ref, seq)` earns its place at this size.

### The position of every account: 3.8 s, and it spills

```
 Finalize GroupAggregate  (actual time=3267.240..3813.991 rows=300001 loops=1)
   Buffers: shared hit=54932 read=492973 written=37, temp read=212953 written=213571
   ->  Sort  (actual time=3253.481..3409.137 rows=1613362 loops=3)
         Sort Key: a.reference
         Sort Method: external merge  Disk: 126456kB
         Worker 0:  Sort Method: external merge  Disk: 124872kB
         Worker 1:  Sort Method: external merge  Disk: 130152kB
         ->  Parallel Hash Right Join ...
               ->  Parallel Seq Scan on posting p  (actual time=0.031..133.330 rows=1613362 loops=3)
               ->  Parallel Seq Scan on audit_record ar  (actual time=0.083..102.829 rows=806681 loops=3)
                     Filter: (((action)::text = ANY ('{TRANSFER_POSTED,TRANSFER_REVERSED}'::text[])) AND (seq <= 2854025))
 JIT:
   Functions: 135
   Timing: ... Total 502.870 ms
 Execution Time: 3825.368 ms
```

Three sequential scans and an **external merge sort spilling about 380 MB across three workers**, plus
half a second of JIT compilation. This is a report over the whole ledger, so scanning it is the right
plan and 3.8 s for 300 001 accounts is not alarming. What is worth recording is that the sort no
longer fits in memory at this size, which is a `work_mem` question rather than a schema one - and
`work_mem` is deployment configuration, which [ADR 0001](../governance/adr/0001-source-only-repository.md)
puts in the companion platform repositories rather than here.

The number to carry into **WP-23** is that this report is the estate's longest-running query by two
orders of magnitude, and it grows with the whole ledger rather than with a day's movements.

---

## What this does not cover

A plan capture is a sample. These five queries are the ones the estate reads a ledger with today; a
query added elsewhere is not here until somebody adds it to `plans.sql`, and the pinning test cannot
know about a query it was never told about.

Nothing here was measured under concurrency. Every figure above is one query on an idle database, so
they are lower bounds. WP-23 records what these cost while a bank day is being driven at the same
instance, which is the only figure an objective can be set against.
