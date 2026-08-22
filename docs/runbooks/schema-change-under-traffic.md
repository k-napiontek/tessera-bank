# Runbook - applying a schema change while money is moving

**Applies to:** `services/ledger-persistence`'s migration set, applied by `services/ledger-api` at
boot and by `services/ledger-loader migrate`.

Every claim on this page was **measured** by WP-24b's exercise rather than reasoned about. The
figures, the conditions they were taken under and the artefacts they came from are in
[`../architecture/estate-under-load.md`](../architecture/estate-under-load.md); the captures are
under [`../../workload/baselines/migration/`](../../workload/baselines/migration/).

This is a map, not a change policy. When a migration may be applied is a change-management question -
see [`../ways-of-working/change-management.md`](../ways-of-working/change-management.md).

## The one that hangs instead of failing

**`CREATE INDEX CONCURRENTLY` under Flyway never returns unless
`flyway.postgresql.transactional.lock=false` is set.**

Flyway takes its schema-history lock on a **second connection**, and holds it in an open transaction
for the length of the migration. `CREATE INDEX CONCURRENTLY` waits for every transaction that could
see the table to finish - including that one. Neither gives up. There is no error, no timeout, and
the bank goes on serving normally the whole time, so nothing pages and nothing looks wrong.

What it looks like from inside the database:

```
pid | state               | wait_event_type | query
101 | idle in transaction | Client          | SELECT COUNT(*) FROM pg_namespace WHERE nspname=$1
102 | active              | Lock            | CREATE INDEX CONCURRENTLY posting_exercise_ix ON ...
```

The `idle in transaction` row is Flyway's own lock connection. Measured on PostgreSQL 16.15 with
Flyway 9.22.3.

**The setting that looks like the fix is not the fix.** Flyway 9 already detects that
`CREATE INDEX CONCURRENTLY` cannot run inside a transaction and runs it non-transactionally without
being told - it prints `[non-transactional]` either way. Setting `executeInTransaction=false` in a
script config therefore changes nothing about this hang, and a team that sets it and sees the hang
persist will conclude the problem is elsewhere.

## What each kind of migration takes, and who waits behind it

| Statement | Lock on the table | Who is blocked |
|---|---|---|
| `CREATE INDEX` | `SHARE` | every writer; readers pass |
| `CREATE INDEX CONCURRENTLY` | `SHARE UPDATE EXCLUSIVE` | nobody; two passes and a wait instead |
| `ALTER TABLE ... ADD COLUMN` (no volatile default) | `ACCESS EXCLUSIVE`, briefly | everyone, for as long as it takes to **acquire** |
| `DROP INDEX` | `ACCESS EXCLUSIVE` | everyone |

**The duration of the statement and the duration of the outage are different numbers.** A metadata-only
`ALTER TABLE` finishes in microseconds and still stops the bank for as long as it waits for its
`ACCESS EXCLUSIVE` lock - and while it waits, everything arriving behind it queues too, because a
pending exclusive request blocks later shared ones. On this estate every transfer takes
`pg_advisory_xact_lock` and holds it to the end of its transaction (ADR 0005), so there is always
something to wait for.

Set `lock_timeout` before any statement taking `ACCESS EXCLUSIVE`, so a migration that cannot get its
lock quickly fails and is retried rather than building a queue behind it. Nothing in this repository
sets one yet.

## Reading it while it happens

The two questions are *what is holding the lock* and *how many are behind it*. One query, because a
sampler that took two would report two instants as one:

```sql
SELECT
  (SELECT count(*) FROM pg_stat_activity
     WHERE datname = current_database() AND wait_event_type = 'Lock')       AS waiting,
  (SELECT string_agg(DISTINCT l.mode, ',') FROM pg_locks l
     JOIN pg_class c ON c.oid = l.relation
    WHERE c.relname = 'posting' AND l.granted)                              AS granted,
  (SELECT string_agg(DISTINCT l.mode, ',') FROM pg_locks l
     JOIN pg_class c ON c.oid = l.relation
    WHERE c.relname = 'posting' AND NOT l.granted)                          AS queued;
```

**Run it while the migration is running, not afterwards.** A lock is held for a window; a query run
after that window finds nothing, and that is how a runbook comes to claim a migration takes no lock.
`workload/internal/migration` samples it every 250 ms for exactly this reason, and the samples are
committed beside each capture as `locks.txt`.

`waiting` is the number an operator actually wants. It is the bank queueing, and it moves before any
customer-facing signal does.

## What the customer sees

**Read the gateway, not the ledger.** The ledger's own `ledger_posting_latency_seconds` times the
posting it performed, not the wait to get a connection to perform it in - the same effect WP-24c
measured for `SCN-POOL-EXHAUSTION`, where the ledger's objective read **met** at 0.99398 while the
edge missed at 5.71x its budget. A migration that blocks writers produces the same shape: the ledger
looks healthy because it is not being asked anything.

`SLO-GATEWAY-LATENCY` over a scrape pair bracketing **the migration window** is the figure. Bracketing
the whole run instead averages a lock held for seconds across a nine-hour day and reports almost
nothing.

Expect latency and not errors. Every condition WP-24c injected arrived at the edge as latency and
never as failure - `SLO-GATEWAY-AVAILABILITY` was 1.00000 in all seven runs, including the one that
suspended the ledger process outright. A migration is unlikely to be the exception, so **a migration
that caused no 5xx is not evidence that it caused nothing.**

## Verifying it afterwards

Flyway saying it applied a migration and the schema having changed are two different claims.

```sql
SELECT c.relname, i.indisvalid FROM pg_class c
  JOIN pg_index i ON i.indexrelid = c.oid WHERE c.relname = '<index>';
```

`indisvalid = f` is an index that exists and cannot be used: what a failed `CREATE INDEX
CONCURRENTLY` leaves behind. It has to be dropped and rebuilt; it will not repair itself, and queries
will quietly not use it in the meantime.

**Flyway exits zero when it does nothing.** A migration whose history table already records it prints
`No migration necessary` and succeeds. Any tooling that infers "the migration ran" from an exit code
will be wrong exactly when the schema is not what it expects - `internal/migration` refuses such a run
outright for this reason.

## Related

- [`ledger-observability.md`](ledger-observability.md) - what the ledger reports about itself, and
  what `ledger_db_*` says about table growth and dead tuples
- [`edge-refusing-requests.md`](edge-refusing-requests.md) - reading a degradation from the edge
- [`../architecture/estate-under-load.md`](../architecture/estate-under-load.md) - the measurements
  behind every figure on this page
- [`../../workload/migrations/README.md`](../../workload/migrations/README.md) - the exercise's own
  migrations, and why they are not the ledger's
