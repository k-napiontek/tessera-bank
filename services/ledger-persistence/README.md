# ledger-persistence

**Stratum 3** | **Java 17, Spring Data JDBC, PostgreSQL, Flyway** | **Built by WP-07**

The PostgreSQL adapters behind the ports [`ledger-core`](../ledger-core) declares. Hand-written SQL,
no JPA, no lazy loading: the statements that move money are readable in one place.

```bash
make docker                                          # is the daemon running?
./gradlew :services:ledger-persistence:test          # 58 tests, real PostgreSQL
```

## Why this is a separate module

`ledger-core` has **no framework on its compile classpath at all**, so a Spring import there fails to
compile rather than merely failing a rule. That guarantee is worth more than the convenience of one
module, and putting the adapters beside the domain would have forced `DomainPurityTest` to be narrowed
from "no source in this module" to "no source in the domain package" - weakening a control that works,
to make room for new code.

Infrastructure depends on the domain; the dependency points that way and
[`HexagonalBoundariesTest`](src/test/java/bank/tessera/ledger/HexagonalBoundariesTest.java) fails if it
ever points back.

## The schema

| Table | Holds |
|---|---|
| `account` | reference, customer, type, currency, status, overdraft limit, opening date |
| `journal_entry` | reference, value date, currency, remittance text, the entry it reverses |
| `posting` | one leg: entry, sequence, account, direction, amount. **Append-only** |
| `balance` | the materialised booked balance, one row per account |
| `hold` | an amount reserved but not posted |
| `idempotency_record` | a money-moving request's key, its fingerprint and the response it produced |
| `audit_record` | who did what to which subject, with a hash of the previous row. **Append-only** |
| `outbox_record` | a domain event, written with the postings and published afterwards |

WP-08 added three migrations, each for something the REST contract needs the database to remember:

| Migration | Adds | Why |
|---|---|---|
| `V3__ledger_references.sql` | `account.opened_date`, `journal_entry.reverses`, two sequences | `opened_date` is a **business** date, and `created_at` is when the row was inserted - they differ the moment an account is migrated in from the mainframe. `reverses` closes a gap WP-07 could not have seen: `JournalEntry` has carried `reverses()` since WP-06 and the schema had nowhere to put it, so a reversal round-tripped losing the link to the entry it corrected. |
| `V4__movement_reference.sql` | `journal_entry.reference_text` | Remittance information, 35 characters as in the SEPA field. On the entry rather than the posting because the canonical model says one value is copied onto both legs, and no invariant depends on it - which is also why `JournalEntry` does not carry it. |
| `V5__idempotency.sql` | `idempotency_record` | The table exists for its primary key. See below. |

WP-09 added three more:

| Migration | Adds | Why |
|---|---|---|
| `V6__hold_transitioned_at.sql` | `hold.transitioned_at` | Follow-up F-21. Every transition took an `Instant`, validated it and threw it away, so a released hold could not say when it was released and this adapter passed a value it knew was ignored. A `CHECK` makes "null" and "still `PLACED`" the same statement, and a second refuses a hold that ended before it began. |
| `V7__audit_log.sql` | `audit_record`, two triggers | Append-only against the application, hash-chained against everyone else. See below. |
| `V8__outbox.sql` | `outbox_record`, a partial index | The event lands in the postings' transaction; a relay publishes from here. [ADR 0004](../../docs/governance/adr/0004-transactional-outbox.md). |

**The audit trail's two mechanisms do different jobs, and conflating them is the usual mistake.** The
row trigger stops the application from editing a row - that is the control that operates every day.
The hash chain catches whoever gets past it: a superuser, a DBA with DDL rights, a backup restored
with one row altered. It cannot prevent the edit, only make it impossible to hide, and saying so is
the point: a control that claims to prevent what it can only detect is worse than one that states the
difference. Note the **statement-level `TRUNCATE` trigger** beside the row-level one - `TRUNCATE`
fires no row trigger, so without it a single statement empties the whole trail and the control never
runs. [ADR 0005](../../docs/governance/adr/0005-hash-chained-audit-trail.md).

**Appends to the chain serialise on `pg_advisory_xact_lock`, and that is a stated cost.** Reading the
last hash and inserting the row that chains onto it must be one atomic step, or two concurrent
appends claim the same predecessor and `audit_record_previous_unique` refuses the loser - a chain
defect surfacing as a customer's transfer failing. Every money-moving transaction therefore queues
behind this lock for the duration of its own transaction. That is the price of one global chain, and
it is the chain being global that makes a *missing* row detectable.

**What is hashed is normalised first.** `timestamptz` keeps microseconds while `Instant` carries
nanoseconds, and a `uuid` column returns lowercase whatever case it was given. Hash before
normalising and verification reports tampering on rows nobody touched - and a control that cries wolf
gets switched off.

**The outbox row is an insert and nothing else.** `JdbcEventOutbox` opens no transaction, starts no
thread and reaches no broker; every one of those would reintroduce the dual write the table exists to
avoid. `OutboxRelay` publishes **and only then marks dispatched** - the reverse order turns a failed
publish into a permanently lost event, and `OutboxRelayTest` fails on that mutation.

**A transfer is reversed at most once, and the unique index says so.** `journal_entry_reverses_uq`
is a partial unique index on `reverses`. The use case checks first, but a check in one code path is
not a control: two concurrent reversal requests both pass it, and the account is credited twice for
a single erroneous debit - by a second entry that balances perfectly, so nothing downstream reports
it.

**The idempotency claim is an upsert, not a read followed by a write.**
`INSERT ... ON CONFLICT (key) DO UPDATE` takes a row lock and *waits* for a conflicting transaction
that has not committed; `DO NOTHING` skips instead, and the following `SELECT` cannot see the
uncommitted row either - so both retries conclude the key is theirs. Demonstrated: with `DO NOTHING`,
eight threads retrying one request execute it eight times.

**Money is `bigint` minor units plus a `char(3)` ISO 4217 code.** Never `numeric`, never a float. A
`numeric` column invites a `BigDecimal` into the row mappers, and the domain forbids one outright.

**A null `overdraft_limit_minor` means forbidden, not zero.** `upTo(zero)` is a different policy - an
account permitted to reach exactly zero - and collapsing the two hands an overdraft of nothing to
accounts that must never have one.

**There is now a transfer service, and it is not here.** WP-08 composes locking, the overdraft
decision and the append in `ledger-core`'s `application` package, reaching this module through the
`UnitOfWork` port. `JdbcUnitOfWork` delegates to `AccountLocks.lockInOrder`, so the ordering rule
this module owns is untouched.

## Which invariant is enforced where

The domain enforces all of these. The schema enforces them again, because the domain can be bypassed by
anything holding a connection string, and a database that trusts its callers has no invariants at all.

| Invariant | In the schema |
|---|---|
| Amounts are strictly positive; direction carries the sign | `CHECK (amount_minor > 0)` |
| A posting is in its entry's currency | composite FK to `journal_entry (reference, currency)` |
| A posting is in its account's currency - no conversion exists in this estate | composite FK to `account (reference, currency)` |
| Debits equal credits | `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`, checked at commit |
| Postings are append-only | a trigger that raises on `UPDATE` or `DELETE` |
| A captured hold names its capturing entry | `CHECK ((status = 'CAPTURED') = (captured_by IS NOT NULL))` |

The balanced-entry check **must** be deferred: when the first leg is inserted the others do not exist,
so a row-level rule would reject every entry ever written.

The trigger function addresses its table through `TG_TABLE_SCHEMA` rather than by an unqualified name.
A function body resolves unqualified names against the **caller's** `search_path`, not the schema it was
created in, so an unqualified `posting` works only by luck - and stopped working the first time it was
called from a connection with a different `search_path`.

## Two balance paths, deliberately

| Path | Reads | Used by |
|---|---|---|
| fast | the materialised `balance` row plus active holds | `balanceOf`, and every API call in WP-08 |
| slow | `SUM` over `posting` | `BalanceReconciliation` |

If `balanceOf` summed the postings itself, the `balance` table would be dead weight and the
reconciliation would compare a number to itself - a test that cannot fail. **Two independent
derivations that must agree** is what makes the materialised figure trustworthy, and it is the whole of
REQ-LED-006.

The SQL in `BalanceReconciliation` reimplements the sign convention that `AccountType.signedEffect`
holds in Java. That duplication is the point: a check written against the same code it checks proves
nothing. A test asserts the two stay in step across all five account types.

This does not contradict WP-06's decision that `Account` stores no balance. The **aggregate** holds
none; the database materialises one as a read optimisation, and the reconciliation exists precisely
because a materialised figure is a second source of truth that can drift.

## Locking

**`AccountLocks.lockInOrder` is the only sanctioned way to lock more than one account.** It sorts by
account reference and takes `SELECT ... FOR UPDATE` in that order.

Calling `findForUpdate` twice in whatever order the caller holds the references is how two transfers
deadlock: thread A holds account 1 and waits for 2, thread B holds 2 and waits for 1. PostgreSQL breaks
the cycle by killing one transaction, so the symptom is a failed transfer under load and nothing at all
in testing.

The order itself is arbitrary. That it is **the same order every time** is the entire mechanism.

The ring-transfer test proves it: six threads move money around a ring of five accounts, in both
directions over the same pairs, asserting that total value across the ring never changes. Deleting the
single `.sorted(...)` line makes PostgreSQL report `deadlock detected` and the test fail - which is how
the rule is known to be load-bearing rather than decorative.

## Tests

Real PostgreSQL through Testcontainers, one container for the module and a schema per test class. An
in-memory database takes no row locks, so the concurrency test would pass against one while proving
nothing - the worst outcome available, since it would then be cited as evidence.

| Class | Covers |
|---|---|
| `ContainerSmokeTest` | the harness itself, so a stopped Docker fails on the first test |
| `SchemaMigrationTest` | migrations apply to an empty database and are a no-op on re-run |
| `SchemaConstraintTest` | every constraint watched **rejecting** its violation |
| `JdbcAccountRepositoryTest`, `JdbcHoldRepositoryTest` | round trips, including forbidden-versus-zero overdraft |
| `JdbcJournalEntryRepositoryTest` | the money path, with expected figures written out as numbers |
| `AccountLocksConcurrencyTest` | the ring transfer, and the lock order asserted directly |
| `BalanceReconciliationTest` | zero drift, and a corrupted row detected |
| `AuditChainTest` | a tampered row named, a deleted row caught at its successor, `TRUNCATE` refused |
| `OutboxTransactionTest` | the event commits with the postings and rolls back with them |
| `OutboxRelayTest` | publish-then-mark, and republication after a crash in the window |
| `HexagonalBoundariesTest` | the domain imports no framework and never knows the adapters exist |

## Notes for the next change

- **`Hold` is rehydrated through `place` then its recorded transition.** It exposes no reconstruction
  factory by design. Do not add one: a persistence-shaped back door into an aggregate is how a
  lifecycle stops being enforced. The transition instant now comes from the row rather than from
  `placed_at` - WP-09 closed F-21 - so the round-trip test asserts the value, where it used to prove
  the value could not matter.
- **Each test class that appends to the audit chain wants a schema of its own.** Half of
  `AuditChainTest` breaks the chain deliberately, and a shared schema would make every later
  assertion depend on which test ran first.
- **Pools open connections on demand.** Hikari defaults `minimumIdle` to `maximumPoolSize`, so every
  per-class schema opened its full eight connections at once; adding schemas reached PostgreSQL's
  100-client limit and failed in whichever class happened to run last, which made it look like that
  class's fault.
- **PostgreSQL `timestamptz` stores microseconds and `Instant` carries nanoseconds.** An instant with
  nanos does not survive the round trip, so fixtures use microsecond precision. An equality assertion
  would otherwise fail against a correct adapter.
- **There is still no transfer service here, and there should not be.** WP-08 composes locking, the
  append and the overdraft decision in `ledger-core`, and reaches this module through the
  `UnitOfWork` port. This module supplies the pieces and proves each holds under concurrency -
  including, now, that the composition does: `TransferConcurrencyTest` runs the ring through the use
  case rather than the adapter and asserts total value is conserved.
