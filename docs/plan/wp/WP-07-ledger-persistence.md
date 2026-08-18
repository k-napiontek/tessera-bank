# WP-07 - Ledger persistence

| | |
|---|---|
| **Ticket** | TB-1007 |
| **Branch** | `feat/TB-1007-ledger-persistence` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-06 |
| **Status** | `Not started` |

## Objective

Persist the ledger to PostgreSQL without letting the database weaken any domain invariant. The hard
problems here are concurrency and the relationship between stored balances and stored postings: two
simultaneous transfers touching the same account must not lose an update, and a materialised balance
must be provably equal to the sum of its postings rather than merely assumed to be.

## In scope

- Flyway migrations for accounts, journal entries, postings, balances and holds.
- Database constraints that enforce what the domain enforces, so neither layer alone is load-bearing.
- Repository adapters implementing the WP-06 ports.
- Deterministic lock ordering on money movement.
- A reconciliation routine proving materialised balances equal summed postings.
- Testcontainers integration tests against real PostgreSQL.
- ArchUnit tests enforcing the hexagonal boundaries, including the no-framework rule from WP-06.

## Out of scope

- REST endpoints and idempotency - WP-08.
- Audit chain and outbox tables - WP-09.
- Any performance tuning beyond correct indexing.

## Constraints

- **Spring Data JDBC with hand-written SQL on the locking paths.** No JPA, no Hibernate, no lazy
  loading anywhere near the money path - the SQL that moves money must be readable in one place.
- Locking is pessimistic: `SELECT ... FOR UPDATE` on the involved account rows, acquired in
  **deterministic account-id order** so concurrent transfers cannot deadlock.
- Postings are append-only at the database level too. No `UPDATE` and no `DELETE` statement may exist
  against the postings table.
- Migrations are forward-only and never edited once merged.
- Tests run against real PostgreSQL via Testcontainers. An in-memory database would not exercise the
  locking behaviour being tested, which is the whole point.

## Tasks

Ten tasks, roughly one commit each, test-first throughout.

### Four things settled before task 1

**1. The adapters live in a new module, `services/ledger-persistence`.** The **In scope** section
says "repository adapters implementing the WP-06 ports" without saying where, and the Verification
section names `./gradlew :services:ledger-core:test`. Putting them in `ledger-core` cannot work as
that module is currently written:

- its `build.gradle.kts` states that its dependency list must never grow a framework, and the first
  Spring import contradicts that;
- `DomainPurityTest` scans **every** source under `src/main/java` for `org.springframework`,
  `jakarta.*` and `BigDecimal`. An adapter fails it immediately, and the only way to keep the module
  is to narrow that scan to the `domain` package - weakening a control that currently works, to
  accommodate new code.

A second module keeps `ledger-core` framework-free **by compilation** rather than by test. ArchUnit
stays meaningful because the persistence module has the domain classes *and* Spring on its classpath,
so a rule asserting the domain touches no framework has something real to check.
`DomainPurityTest` already anticipates this: *"WP-07 will replace this with a proper ArchUnit rule
once there is an infrastructure layer for it to police."*

**The verification command therefore becomes `./gradlew :services:ledger-persistence:test`.** Stated
here rather than substituted silently.

**2. A balance is read one way and verified another, and that is the point.** The **In scope**
section asks for both a `balances` table and "a reconciliation routine proving materialised balances
equal summed postings". Those only make sense together:

| Path | Reads | Used by |
|---|---|---|
| fast | the materialised `balance` row plus active holds | `balanceOf`, and every API call in WP-08 |
| slow | `SUM` over `posting` | `BalanceReconciliation`, on demand |

If `balanceOf` summed the postings itself, the `balance` table would be dead weight and the
reconciliation routine would compare a number to itself - a test that cannot fail. Two independent
derivations that must agree is what makes REQ-LED-006 a control.

This does not contradict WP-06's decision that `Account` stores no balance. The **aggregate** holds
none; the database materialises one as a read optimisation, and the reconciliation exists precisely
because a materialised figure is a second source of truth that can drift.

**3. Deterministic lock ordering lives in the adapter, because the port locks one account at a
time.** `AccountRepository.findForUpdate(reference)` takes a single reference, so nothing in the port
can order a pair. WP-06 owns that interface and WP-07 must not widen it to suit an adapter - that
inverts the dependency the architecture exists to protect.

So the persistence module supplies `AccountLocks.lockInOrder(Collection<AccountRef>)`, which sorts by
reference and acquires each lock in that order, and documents itself as the only sanctioned way to
lock more than one account. The ring-transfer test is what proves it.

**4. Rehydrating a `Hold` goes through `place` and then the stored transition.** `Hold` exposes
`place`, `capture`, `release` and `expire`, and no reconstruction factory - by design, since it is an
immutable aggregate with a lifecycle. The adapter therefore rebuilds a `CAPTURED` or `RELEASED` hold
by placing it and applying the recorded transition with the recorded timestamp. **Do not add a
rehydration constructor to `Hold`**: that is WP-06's type, and a persistence-shaped back door into an
aggregate is how the lifecycle stops being enforced.

### 1. Mark the package in progress

`chore(plan): mark WP-07 in progress [TB-1007]`

### 2. The module, and a container that actually starts

`services/ledger-persistence/build.gradle.kts` plus the `settings.gradle.kts` include. Java 17
toolchain, `-Xlint:all -Werror`, and the Spring Boot 3.2 BOM as a `platform(...)` **without** the Boot
plugin - there is no application to package until WP-08, and `bootJar` on a library module is noise.

Dependencies: `spring-data-jdbc`, `flyway-core`, `flyway-database-postgresql`, the PostgreSQL driver,
`testcontainers:postgresql`, `archunit-junit5`, JUnit 5, AssertJ, and `project(":services:ledger-core")`.

Test: a Testcontainers smoke test that starts real PostgreSQL and asserts `SELECT 1` returns 1.
Nothing above this can be trusted until the container harness itself is known to work, and a suite
that fails because Docker is not running should say so at the first test rather than the twentieth.

`build(services): add the ledger-persistence module [TB-1007]`

### 3. The schema

`src/main/resources/db/migration/V1__ledger_core.sql`: `account`, `journal_entry`, `posting`,
`balance`, `hold`.

**Money is `bigint` minor units plus a `char(3)` currency.** Never `numeric`, never `double
precision`. The canonical data model says money is minor units and an ISO 4217 code, and a `numeric`
column would invite a `BigDecimal` in the mapper, which the domain forbids outright.

Notable columns: `account.overdraft_limit_minor` is nullable, where **null means forbidden** -
`OverdraftPolicy.forbidden()` against `OverdraftPolicy.upTo(limit)`. `posting.seq` preserves the
order of `JournalEntry.postings()`, because a list has an order and a table does not.
`journal_entry` carries `created_at` as persistence metadata; the audit trail is WP-09 and does not
belong here.

Tests: migrations apply cleanly to an empty database, every expected table and column exists, and
**running Flyway a second time is a no-op** - the Definition of Done asks for idempotence explicitly.

`feat(ledger): add the flyway schema for the ledger [TB-1007]`

### 4. The invariants, in the schema as well as the domain

`V2__ledger_constraints.sql`. Neither layer alone is load-bearing, so every domain invariant gets a
database counterpart:

| Invariant | Mechanism |
|---|---|
| A posting amount is strictly positive - direction carries the sign | `CHECK (amount_minor > 0)`, on `hold` too |
| Every posting in an entry shares the entry's currency | composite FK `posting(entry_ref, currency)` -> `journal_entry(reference, currency)` |
| A posting is in the account's own currency - no conversion anywhere in this estate | composite FK `posting(account_ref, currency)` -> `account(reference, currency)` |
| An entry balances: total debits equal total credits | `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`, checked at commit |
| Postings are append-only | a trigger raising an exception on `UPDATE` or `DELETE` |
| A captured hold names the entry that captured it | `CHECK ((status = 'CAPTURED') = (captured_by IS NOT NULL))` |

The two composite foreign keys are worth the unique indexes they need: they express in one line what
would otherwise be a trigger, and a declarative constraint cannot be forgotten on a new code path.

The balanced-entry check **must** be deferred. A row-level check cannot see the other legs of the
entry, so it can only be evaluated once the transaction has written all of them.

Tests: each constraint is asserted to **reject** its violation - an unbalanced entry that fails only
at commit, an `UPDATE` against `posting` that raises, a posting whose currency differs from its
account, a zero amount. A constraint nobody has watched fail is a constraint nobody knows is wired up.

`feat(ledger): enforce the ledger invariants in the schema [TB-1007]`

### 5. The account and hold adapters

`adapter/jdbc/JdbcAccountRepository` and `JdbcHoldRepository`, implementing the WP-06 ports
**unchanged**. Hand-written SQL through `NamedParameterJdbcTemplate`: no JPA, no lazy loading, and
the SQL that moves money readable in one place.

`findForUpdate` is `SELECT ... FOR UPDATE`. `save` upserts. Row mappers translate to and from the
domain types through their public factories - `AccountRef.of`, `Money.of`, `CurrencyCode.of`, the
`Account.Builder`, and for holds the `place`-then-transition path settled above.

Tests: every field round-trips; an unknown reference returns `Optional.empty()` rather than throwing;
a forbidden overdraft policy survives the round trip as `forbidden()` and not as a zero limit;
`findActiveFor` returns a `PLACED` hold and omits a `RELEASED` one.

`feat(ledger): add the jdbc account and hold repositories [TB-1007]`

### 6. The journal entry adapter and the materialised balance

`JdbcJournalEntryRepository`: `append`, `findByReference`, `findByAccount(account, from, to)` and
`balanceOf`.

`append` writes the entry, its postings in list order, and updates each affected `balance` row **in
the same transaction**. The signed effect comes from `AccountType.signedEffect(direction, amount)` -
the adapter performs no arithmetic of its own, because the sign convention is domain knowledge and a
second copy of it here is a second copy to get wrong.

`balanceOf` reads the materialised row and passes it to `Balance.of(account, booked, holds)`, which
already derives available from the holds.

Tests, with expected figures written out as explicit numbers: an appended entry reads back with its
postings in order; the balance row moves by exactly the posted amount; a debit to a `LIABILITY`
account decreases it and a debit to an `ASSET` increases it; available balance drops by an active
hold and ignores a released one; `findByAccount` is inclusive at both ends of the date range.

`feat(ledger): add the journal entry repository and materialised balance [TB-1007]`

### 7. Deterministic lock ordering

`adapter/jdbc/AccountLocks.lockInOrder(Collection<AccountRef>)`, sorting by account reference and
locking in that order.

Test: the ring-transfer concurrency test the Definition of Done names. N threads move money around a
ring of accounts; the assertion is that **total value across the ring is unchanged**, with no lost
update and no deadlock. Two threads transferring in opposite directions over the same pair is the
case that deadlocks without ordering, so the test must contain it deliberately rather than hope for it.

This is why Testcontainers is not negotiable: an in-memory database does not implement `FOR UPDATE`
row locking, so the test would pass against one while proving nothing.

`feat(ledger): lock accounts in deterministic order on money movement [TB-1007]`

### 8. Reconciliation

`adapter/jdbc/BalanceReconciliation`: `SUM` the postings per account, compare against the `balance`
row, and **return the drift as data** rather than logging it. A routine that writes a warning nobody
reads has not reported anything.

Tests: zero drift over a generated dataset, and **a deliberately corrupted balance row is detected** -
named specifically in WP-07's Verification section. A reconciliation that has never caught anything is
not known to work, which is the same reason WP-04 demonstrated its reject paths failing.

`feat(ledger): reconcile materialised balances against summed postings [TB-1007]`

### 9. The boundaries, enforced

ArchUnit rules in the persistence module - the proper version of the source scan `DomainPurityTest`
stands in for:

- classes in `..ledger.domain..` and `..ledger.port..` depend on no `org.springframework..`,
  `jakarta..`, `org.flywaydb..` or `java.sql..`;
- `..domain..` and `..port..` never depend on `..adapter..`. Infrastructure depends on the domain,
  never the reverse, and this is the rule that keeps it that way after the third person joins;
- no source in the module issues `UPDATE` or `DELETE` against `posting` - the trigger blocks it at
  runtime, and this catches it at build time.

`DomainPurityTest` in `ledger-core` **stays**. It guards that module's dependency list, it costs
milliseconds, and removing a working control because a better one arrived elsewhere is how coverage
quietly shrinks.

`test(services): enforce the hexagonal boundaries with archunit [TB-1007]`

### 10. Documentation and evidence

- `services/ledger-persistence/README.md` - the schema, which invariant is enforced where, the
  locking rule, and the two balance paths with the reason they differ.
- `services/README.md` - the module table and what now exists.
- `docs/compliance/traceability-matrix.md` - the WP-07 section for REQ-LED-005, REQ-LED-006,
  REQ-LED-007 and REQ-ARC-001, each checked against the catalogue rather than recalled.
- `Makefile` - `build-services` and `test-services` cover both modules, and a `docker` target reports
  a stopped daemon in one line instead of a Testcontainers stack trace, the same courtesy `jdk17`
  already extends for a missing JDK.

`docs(services): document the persistence layer and record evidence [TB-1007]`

## Definition of Done

- [ ] Migrations apply cleanly to an empty database and are idempotent on re-run.
- [ ] The concurrency test runs N threads transferring around a ring of accounts and asserts total
      value is conserved, with no lost update and no deadlock.
- [ ] The reconciliation routine reports zero drift over a generated dataset.
- [ ] ArchUnit tests pass, including the domain-has-no-framework-imports rule.

## Verification

`./gradlew :services:ledger-persistence:test` with Docker available. Assert specifically: the
ring-transfer concurrency test conserves total value; reconciliation reports zero drift; a
deliberately corrupted balance row is detected by reconciliation.

The module named here was `:services:ledger-core:test` when this package was written, before that
module existed and before it turned out to enforce its own framework-free constraint by compilation.
The adapters live in `services/ledger-persistence` for the reason given in task 1's preamble, so the
tests do too.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-LED-005 Concurrent transfers cannot lose an update or deadlock | deterministic lock ordering |
| REQ-LED-006 Materialised balances are verifiable, not assumed | reconciliation routine |
| REQ-LED-007 Postings cannot be updated or deleted | schema constraints |
| REQ-ARC-001 Domain layer is free of framework dependencies | ArchUnit tests |
