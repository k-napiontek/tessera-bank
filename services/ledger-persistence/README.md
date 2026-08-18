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
| `account` | reference, customer, type, currency, status, overdraft limit |
| `journal_entry` | reference, value date, currency |
| `posting` | one leg: entry, sequence, account, direction, amount. **Append-only** |
| `balance` | the materialised booked balance, one row per account |
| `hold` | an amount reserved but not posted |

**Money is `bigint` minor units plus a `char(3)` ISO 4217 code.** Never `numeric`, never a float. A
`numeric` column invites a `BigDecimal` into the row mappers, and the domain forbids one outright.

**A null `overdraft_limit_minor` means forbidden, not zero.** `upTo(zero)` is a different policy - an
account permitted to reach exactly zero - and collapsing the two hands an overdraft of nothing to
accounts that must never have one.

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
| `HexagonalBoundariesTest` | the domain imports no framework and never knows the adapters exist |

## Notes for the next change

- **`Hold` is rehydrated through `place` then its recorded transition.** It exposes no reconstruction
  factory by design. Do not add one: a persistence-shaped back door into an aggregate is how a
  lifecycle stops being enforced. The transition methods take an instant they do not retain, so the
  value passed cannot affect the rebuilt hold - a round-trip equality test proves it.
- **PostgreSQL `timestamptz` stores microseconds and `Instant` carries nanoseconds.** An instant with
  nanos does not survive the round trip, so fixtures use microsecond precision. An equality assertion
  would otherwise fail against a correct adapter.
- **There is no transfer service here.** Locking, appending and the overdraft decision are composed by
  WP-08. This module supplies the pieces and proves each one holds under concurrency.
