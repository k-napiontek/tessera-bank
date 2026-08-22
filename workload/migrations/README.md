# workload/migrations - the schema change that is applied while money is moving

Two directories, one migration each, and both are **the exercise's own rather than the ledger's**.

`master-plan.md` names *"schema migration under load"* as one of the reasons this repository exists,
and until WP-24b nothing here let anyone attempt it. These two files are what gets attempted.

| Directory | The statement | The lock it takes |
|---|---|---|
| [`blocking/`](blocking/) | `CREATE INDEX` | `SHARE` on `posting` - blocks every writer, lets readers through |
| [`concurrent/`](concurrent/) | `CREATE INDEX CONCURRENTLY` | `SHARE UPDATE EXCLUSIVE` - blocks no writer, two passes and a wait instead |

The same index, the same table, the same traffic. Everything that differs between the two captures
under [`../baselines/migration/`](../baselines/migration/) is a consequence of that one keyword,
which is what makes either number mean anything. A lock duration recorded on its own has nothing to
be read against.

## Why they are not in the ledger's migration set

`services/ledger-persistence/src/main/resources/db/migration/` is the ledger's schema, applied at
every boot and forward-only. A package that measures should not leave a permanent row in the schema
history of the thing it measured, so these run with **their own Flyway history table** -
`workload_exercise_blocking_history` and `workload_exercise_concurrent_history` - and both the index
and the history table are dropped again when the exercise finishes. The ledger's own
`flyway_schema_history` is never touched, and `internal/migration` refuses outright to be pointed at
it.

WP-24's fifth task decision records the reasoning, and
[ADR 0018](../../docs/governance/adr/0018-the-migration-exercise-is-not-a-condition.md) records why
this is an exercise of its own rather than an eighth entry in the scenario catalogue.

## Why this index and not a useful one

`(currency, amount_minor)` answers no question anything in this estate asks. Every posting in the
loaded dataset is in one currency, so the leading column has a cardinality of one and the planner
will never choose it. That is the point: building it is real work over every row in a table of over six
million, and finishing it changes no plan.

The useful index is the one **F-24** asks for - `value_date` and `posted_at` denormalised onto
`posting`, so the statement's keyset seek is served by one composite index instead of abandoning
`posting_account_ix` at depth. Taking that one here would partially answer an open finding as a side
effect of a lock measurement, in a package whose Out of scope forbids touching WP-07's schema. F-24
stays open, and stays WP-07's.

## Running them

Never by hand against anything that matters. `workload/scripts/migration.sh` drives the whole
exercise - it boots the estate, starts a compressed bank day, waits for the day to actually begin,
migrates part way through it while requests are in flight, and records both the lock and what the
customer experienced while it was held.

```bash
bash workload/scripts/migration.sh --baseline with-broker --variant both
```

## The trap, because it hangs rather than fails

`CREATE INDEX CONCURRENTLY` under Flyway needs `flyway.postgresql.transactional.lock=false`, and
**without it the migration never returns**. Flyway holds its schema-history lock on a second
connection which sits `idle in transaction` for the length of the migration; `CREATE INDEX
CONCURRENTLY` waits for every transaction that could see the table to finish, including that one.
Neither ever gives up. No error, no timeout, and the bank still serving normally the whole time.

Flyway 9 already knows the statement itself cannot run in a transaction - it prints
`[non-transactional]` without being told - so the `executeInTransaction=false` script setting is
**not** what makes this work, and is deliberately not shipped here. Measured on PostgreSQL 16.15 with
Flyway 9.22.3; the full account is in
[`docs/runbooks/schema-change-under-traffic.md`](../../docs/runbooks/schema-change-under-traffic.md).

## No personal data

Neither file names a column holding any, and neither reads a row. They create an index over a
currency code and an integer count of minor units.
