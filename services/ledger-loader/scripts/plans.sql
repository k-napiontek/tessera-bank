-- The queries this estate reads a loaded ledger with, and what the planner does with them at volume.
--
-- **Every query below is transcribed, and the transcription is pinned by a test.**
-- QueryPlanSourceTest reads JdbcLedgerReadModel.java and batch/reporting/src/reporting/ledger.py and
-- fails if a fragment named here has stopped appearing in the file it came from. That is the same
-- technique population_test.go uses for the ledger's reference patterns, and it exists because F-64
-- records what transcribing without a check costs: five requirement ids that quietly stopped meaning
-- what the authority said they meant.
--
-- What the pin does catch: a query rewritten, a join dropped, an ORDER BY reordered. What it does
-- not: a query added somewhere else that nobody thought to capture. A plan capture is a sample.
--
-- Run through services/ledger-loader/scripts/capture-plans.sh, which supplies the account.

\set ON_ERROR_STOP on
\timing off

-- The account the load actually gave the most postings to. Named by the load manifest rather than
-- chosen here: an account planted to be deep would be a plan of a fixture.
\echo '===== the account under test'
SELECT :'account' AS account_ref,
       count(*) AS postings,
       min(je.value_date) AS first_value_date,
       max(je.value_date) AS last_value_date
  FROM posting p
  JOIN journal_entry je ON je.reference = p.entry_ref
 WHERE p.account_ref = :'account';

\echo '===== table cardinality the plans below were measured at'
SELECT 'account' AS relation, count(*) FROM account
UNION ALL SELECT 'journal_entry', count(*) FROM journal_entry
UNION ALL SELECT 'posting', count(*) FROM posting
UNION ALL SELECT 'balance', count(*) FROM balance
UNION ALL SELECT 'hold', count(*) FROM hold
UNION ALL SELECT 'audit_record', count(*) FROM audit_record;

-- ---------------------------------------------------------------------------------------------
-- 1. The statement page, first page.
--
-- From JdbcLedgerReadModel.fetch. F-24 is about exactly this shape: the ORDER BY keys value_date and
-- created_at live on journal_entry while the rows come from posting, so the sort happens after the
-- join and posting_account_ix on account_ref is all that narrows it.
-- ---------------------------------------------------------------------------------------------
\echo '===== 1. statement page, first page'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.reference AS entry_ref, p.seq, p.account_ref, p.direction, p.amount_minor,
       p.currency, e.value_date, e.created_at, e.reference_text
  FROM posting p
  JOIN journal_entry e ON e.reference = p.entry_ref
 WHERE p.account_ref = :'account'
   AND e.value_date BETWEEN :'from' AND :'to'
 ORDER BY e.value_date, e.created_at, p.entry_ref, p.seq
 LIMIT 50;

-- ---------------------------------------------------------------------------------------------
-- 2. The statement page, resumed from a cursor halfway through the account's history.
--
-- The keyset predicate is a row-wise comparison rather than a chain of ORs, which is what lets an
-- index serve it - if one covered the keys. The cursor is taken from the account's own middle rather
-- than invented, so the page is one a client would actually have asked for.
-- ---------------------------------------------------------------------------------------------
SELECT (count(*) / 2)::int AS mid FROM posting WHERE account_ref = :'account'
\gset

SELECT e.value_date AS cur_value_date,
       e.created_at AS cur_created_at,
       p.entry_ref  AS cur_entry,
       p.seq        AS cur_seq
  FROM posting p
  JOIN journal_entry e ON e.reference = p.entry_ref
 WHERE p.account_ref = :'account'
 ORDER BY e.value_date, e.created_at, p.entry_ref, p.seq
 OFFSET :mid LIMIT 1
\gset

\echo '===== 2. statement page, deep cursor'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.reference AS entry_ref, p.seq, p.account_ref, p.direction, p.amount_minor,
       p.currency, e.value_date, e.created_at, e.reference_text
  FROM posting p
  JOIN journal_entry e ON e.reference = p.entry_ref
 WHERE p.account_ref = :'account'
   AND e.value_date BETWEEN :'from' AND :'to'
   AND (e.value_date, e.created_at, p.entry_ref, p.seq)
     > (:'cur_value_date', :'cur_created_at', :'cur_entry', :cur_seq)
 ORDER BY e.value_date, e.created_at, p.entry_ref, p.seq
 LIMIT 50;

-- ---------------------------------------------------------------------------------------------
-- 3. The balance read, both halves.
--
-- The materialised row is what an API call takes; the cumulative sum behind a statement's opening
-- balance is what JdbcLedgerReadModel.openingBalance runs, and it walks the account's whole history
-- rather than a page of it.
-- ---------------------------------------------------------------------------------------------
\echo '===== 3a. materialised balance and the active holds behind Balance.of'
EXPLAIN (ANALYZE, BUFFERS)
SELECT b.booked_minor, b.currency,
       (SELECT coalesce(sum(h.amount_minor), 0) FROM hold h
         WHERE h.account_ref = b.account_ref AND h.status = 'PLACED') AS held_minor
  FROM balance b
 WHERE b.account_ref = :'account';

\echo '===== 3b. the cumulative opening balance a statement page carries'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(SUM(CASE WHEN (a.account_type IN ('ASSET', 'EXPENSE'))
                              = (p.direction = 'DEBIT')
                         THEN p.amount_minor ELSE -p.amount_minor END), 0)
  FROM posting p
  JOIN journal_entry e ON e.reference = p.entry_ref
  JOIN account a ON a.reference = p.account_ref
 WHERE p.account_ref = :'account'
   AND e.value_date < :'from';

-- ---------------------------------------------------------------------------------------------
-- 4. The two queries batch/reporting bounds a business date by.
--
-- Both join journal_entry to audit_record on subject_ref with seq <= position, which is what makes a
-- report a function of a ledger position rather than of whatever had committed when it ran. F-43
-- records that nothing in the schema enforces one audit row per entry - so at volume this join is
-- also where a missing row would show up as a report that is quietly short.
-- ---------------------------------------------------------------------------------------------
SELECT max(seq) AS position FROM audit_record
\gset

\echo '===== 4a. reporting: movements on a business date'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.entry_ref,
       p.seq,
       p.account_ref,
       a.account_type,
       p.direction,
       p.amount_minor,
       p.currency,
       je.value_date
  FROM posting p
  JOIN journal_entry je ON je.reference = p.entry_ref
  JOIN account a ON a.reference = p.account_ref
  JOIN audit_record ar
    ON ar.subject_ref = je.reference
   AND ar.action = ANY(ARRAY['TRANSFER_POSTED', 'TRANSFER_REVERSED'])
   AND ar.seq <= :position
 WHERE je.value_date = :'business_date'
 ORDER BY p.entry_ref, p.seq;

\echo '===== 4b. reporting: the position of every account'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.reference,
       a.customer_ref,
       a.account_type,
       a.currency,
       a.status,
       a.opened_date,
       coalesce(sum(m.amount_minor) FILTER (WHERE m.direction = 'DEBIT'), 0),
       coalesce(sum(m.amount_minor) FILTER (WHERE m.direction = 'CREDIT'), 0),
       count(m.amount_minor)
  FROM account a
  JOIN audit_record opened
    ON opened.subject_ref = a.reference
   AND opened.action = 'ACCOUNT_OPENED'
   AND opened.seq <= :position
  LEFT JOIN (
       SELECT p.account_ref, p.direction, p.amount_minor
         FROM posting p
         JOIN journal_entry je ON je.reference = p.entry_ref
         JOIN audit_record ar
           ON ar.subject_ref = je.reference
          AND ar.action = ANY(ARRAY['TRANSFER_POSTED', 'TRANSFER_REVERSED'])
        WHERE je.value_date <= :'business_date'
          AND ar.seq <= :position
  ) m ON m.account_ref = a.reference
 WHERE a.opened_date <= :'business_date'
 GROUP BY a.reference, a.customer_ref, a.account_type, a.currency, a.status,
          a.opened_date
 ORDER BY a.reference;
