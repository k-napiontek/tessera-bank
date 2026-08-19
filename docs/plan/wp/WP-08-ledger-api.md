# WP-08 - Ledger API

| | |
|---|---|
| **Ticket** | TB-1008 |
| **Branch** | `feat/TB-1008-ledger-api` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-07 |
| **Status** | `Done` |

## Objective

Expose the ledger over HTTP with the guarantees a payment system genuinely requires. The defining
feature is idempotency: a client that retries a transfer after a timeout must never move money twice,
and must receive the original response rather than a new one. This is the behaviour that separates a
banking API from a CRUD API.

## In scope

- Use cases: open account, get balance, get statement, transfer funds, place and release hold,
  reverse entry.
- REST adapters implementing `contracts/openapi/`.
- **Idempotency**: `Idempotency-Key` required on every money-moving endpoint, with a
  unique-constrained store of key, request fingerprint and stored response.
- Cursor-paginated statement endpoint.
- RFC 9457 Problem Details for every error response.
- A contract test asserting the implementation matches the OpenAPI document.

## Out of scope

- Authentication and authorisation - the gateway owns those, WP-12.
- Audit chain, outbox and metrics - WP-09.
- The customer-facing UI - WP-14.

## Constraints

- The OpenAPI document is the **source of truth**. It changes before the implementation does, and the
  contract test fails the build if they drift.
- Replaying an idempotency key returns the original stored response. The same key with a **different**
  request body returns `409 Conflict` - it is a client defect, not a retry.
- Statement pagination is cursor-based, never offset-based: offsets skip and duplicate rows when data
  is being written concurrently, which on a statement is a correctness bug rather than a cosmetic one.
- No error response may leak an internal exception, a stack trace or a SQL fragment.

## Tasks

Eleven implementation commits, test-first throughout. That is one above `PROTOCOL.md`'s "roughly 3
to 10", and deliberately so: this package is ten HTTP operations plus an idempotency layer plus the
first Spring Boot application in the repository, and grouping further pushes single commits past the
~400-line target. The sizing rule exists to produce reviewable commits, so the target is honoured
and the count is stated here rather than discovered in the pull request.

### Six things settled before task 1

**1. `POST /accounts` is added to the contract.** The **In scope** section above opens with "open
account", but the OpenAPI document has no write operation on accounts, and its `accounts` tag reads
"Account metadata, balances and statements. Read-only." The **Verification** section's walkthrough
begins "open two accounts", which is unrunnable over HTTP today. The contract changes first, per
`PROTOCOL.md` step 12. `OpenAccountRequest` invents no concept - `customerRef`, `accountType`,
`currency`, `openedDate` and an optional overdraft limit are all already in
[`canonical-data-model.md`](../../architecture/canonical-data-model.md) section 3 or on WP-06's
`Account`.

**2. The statement gains cursor paging, and the balances bracket the page.** The contract's
`Statement` is a whole-range object - `from`, `to`, `openingBalance`, `closingBalance`, `movements` -
with no cursor at all, while the **Constraints** section above forbids offset paging outright. Added:
`cursor` and `limit` query parameters, a nullable `nextCursor` on the response, and
`openingBalance`/`closingBalance` re-scoped to bracket **the page** rather than the range.

Every page then foots on its own, and page N's `closingBalance` equals page N+1's `openingBalance`.
The alternative - keeping the balances at range scope - leaves a single page unverifiable, and a
check that only works after concatenating every page is a check nobody runs.

The cursor is an opaque, base64-encoded keyset token over `(valueDate, postedAt, entryRef, seq)`.
That tuple is a total order because postings are append-only and `posting_seq_uq` makes
`(entry_ref, seq)` unique, so a movement posted while the client is paging can be neither skipped
nor returned twice. An offset cannot promise that, which is the whole reason the constraint exists.

**3. Replay is declared on all five money-moving operations, not just one.** The document's own
description says "Replaying a key with the same request body returns the original response" for every
money-moving operation, but only `createTransfer` declares a `200` response. `reverseTransfer`,
`placeHold` and `captureHold` declare `201` alone, so a replay would have no valid status to return
and the contract test would fail against correct behaviour. The document contradicts itself; it is
corrected in the same commit. `releaseHold` already returns `200` and needs nothing.

**4. The use cases live in `ledger-core`, behind a `UnitOfWork` port; the web adapter is a new
module.** The **Verification** section names `./gradlew :services:ledger-core:test`, which turns out
to be right for the use cases and wrong for everything else.

| Module | Gains |
|---|---|
| `services/ledger-core` | `application/` - the use cases - plus the ports `UnitOfWork`, `ReferenceGenerator`, `LedgerReadModel` and `IdempotencyStore`. Pure Java, driven by fakes, no database. |
| `services/ledger-persistence` | `V3` and `V4` migrations, four new adapters, the keyset statement query. |
| `services/ledger-api` (**new**) | Spring Boot 3.2, controllers, DTOs, RFC 9457 handling, the contract test. |

A third module is not a preference. `ledger-core`'s `build.gradle.kts` states that its dependency
list must never grow a framework, `DomainPurityTest` scans every source in it for
`org.springframework`, `com.fasterxml.jackson` and `jakarta.*`, and `HexagonalBoundariesTest` bans
`com.fasterxml.jackson..` and `jakarta..` from `..ledger.domain..` and `..ledger.port..`. An
annotated DTO cannot live anywhere near the domain, and narrowing either scan to make room for one
would weaken a control that currently works. This is the same argument WP-07 made for
`ledger-persistence`, and it applies again unchanged.

WP-08 is therefore the first Spring Boot application in this repository: the first `main`, the first
`application.yml`, the first use of the Boot Gradle plugin. `Transactions.java` was written in
anticipation of it - *"WP-08 can pass a container-managed `TransactionTemplate` instead"* - and every
WP-07 adapter already takes one as a constructor argument, so the persistence layer wires from a
Spring context without modification.

The `UnitOfWork` port is the one genuinely new idea. The transfer use case must run inside a
transaction with both accounts locked, but `AccountLocks.lockInOrder` lives in the persistence module
and WP-07 deliberately refused to widen `AccountRepository` to accommodate it. So the port expresses
the **transaction boundary**, which is an application concern rather than a persistence one:

```java
public interface UnitOfWork {
    <T> T inTransaction(Supplier<T> work);
    <T> T inTransactionLocking(Collection<AccountRef> accounts, Supplier<T> work);
}
```

The ordering rule stays in the adapter - `JdbcUnitOfWork` delegates to `AccountLocks.lockInOrder` -
so the decision WP-07 proved load-bearing by deleting its `.sorted(...)` line is untouched.

**The verification command therefore becomes `:services:ledger-core:test` and
`:services:ledger-api:test`.** Stated here rather than substituted silently, exactly as WP-07 did.

**5. `openedDate` and `lastMovementDate` are read-model fields, and `Account` is not touched.** The
OpenAPI `Account` schema requires `openedDate` and offers `lastMovementDate`. WP-06's `Account` has
neither, and `JdbcAccountRepository` maps neither `created_at` nor `updated_at`. Adding them to the
aggregate would be a persistence-shaped back door into WP-06's type - precisely the move F-21 records
being refused for `Hold`.

Neither field participates in any invariant. `openedDate` is `account.created_at`;
`lastMovementDate` is `MAX(value_date)` over the account's postings. Both are derived, so they belong
to a query port, `AccountQueries`, returning an `AccountSummary` record - the same reasoning that
already keeps a balance off the aggregate.

For the same reason **`Transfer.status` is derived, not stored**. `ACCEPTED` and `REJECTED` never
reach the database: a rejected transfer is a `422` Problem document, not a row. A persisted entry is
`POSTED`, and becomes `REVERSED` once another entry names it in `reverses`.

**6. The idempotency fingerprint is defined, not assumed.** The fingerprint is SHA-256 over the HTTP
method, the resolved request path, and the canonical - key-sorted, whitespace-stripped - JSON body.
For `releaseHold`, which carries no body, the body contributes the empty string.

The store holds `(key PRIMARY KEY, fingerprint, status, response_body jsonb, created_at)`. The same
key with the same fingerprint replays the stored status and body byte for byte; the same key with a
different fingerprint is `409`.

Uniqueness is resolved by `INSERT ... ON CONFLICT DO NOTHING`, never by a `SELECT` followed by an
`INSERT`. Two concurrent retries of the same request both pass a read-then-write check, and the
result is a double spend - which is the exact failure this package exists to prevent.

### 1. Mark the package in progress

`chore(plan): mark WP-08 in progress [TB-1008]`

### 2. The contract, and the model behind it

[`canonical-data-model.md`](../../architecture/canonical-data-model.md) section 1 gains a paging
convention defining `cursor` and `nextCursor` as opaque keyset tokens. Section 11 of that document
requires the model to change **before** the contracts derived from it, not alongside them.

`contracts/openapi/ledger-core.yaml` then gains `POST /accounts` with an `OpenAccountRequest` schema,
`cursor` and `limit` parameters on `getStatement`, `nextCursor` on `Statement`, the re-scoped balance
descriptions, and the `200` replay response on the three operations missing it. The `accounts` tag
loses "Read-only".

**ADR 0003** records the paging decision, because section 11 requires an ADR for a change to a wire
format. It is also the first time the Definition of Done's ADR box is honestly ticked - F-09 and F-12
have both been open since WP-02 and WP-06.

Verified by `bash contracts/validate.sh` exiting 0, with Redocly clean and **no configuration file**,
per the decision of 2026-08-17. A config added to silence a rule is a weakened validator, not a
passing one.

`feat(contracts): add account opening and cursor-paged statements [TB-1008]`

### 3. The account use cases and the ports they need

`bank.tessera.ledger.application` in `ledger-core`: `OpenAccount`, `GetAccount`, `GetBalance`,
`GetTransfer` and `ListHolds`, plus the port interfaces `UnitOfWork`, `ReferenceGenerator` and
`LedgerReadModel`. All pure Java - this module's dependency list does not grow.

Adapters in `ledger-persistence`: `V3__ledger_references.sql`; `JdbcUnitOfWork` delegating to
`Transactions` and `AccountLocks.lockInOrder`; `JdbcReferenceGenerator` producing
`TB` + `CCYYMMDD` + ten digits and `HL` + `CCYYMMDD` + ten digits from a sequence;
`JdbcLedgerReadModel` reading `opened_date`, `MAX(value_date)`, `created_at` and `reverses`.

Tests: the use cases against in-memory fakes with no database at all, which is the point of keeping
them here; the adapters on a fresh Testcontainers schema; a generated reference matches the canonical
pattern, and sixteen threads allocating at once never collide.

**Three corrections to this task as it was written.**

The read-model port is named `LedgerReadModel`, not `AccountQueries`. It turned out to owe the API
four derived figures rather than two - the account's opening and last-movement dates, an entry's
posting instant, and the entry that reverses another - and a name promising only accounts would have
been wrong the moment `GetTransfer` needed a status.

`IdempotencyStore` moves to task 6, where it is implemented and tested. A port interface committed
with no implementation and no test is a commit that cannot be verified on its own, which the
sizing rule exists to prevent.

`HoldRepository` gains `findAllFor`. The contract's `listHolds` takes `includeInactive` and WP-06's
port answers only "what is reserved", so there was no way to serve the operation. Additive, and kept
as a second method rather than a flag on `findActiveFor`: the available balance must never be
computed from a list that includes released holds, and a boolean is one mistaken argument away from
exactly that.

**A gap this task found and closed.** `journal_entry` had no column for `JournalEntry.reverses()`, so
a reversal round-tripped through the database losing the link to the entry it corrected. `V3` adds
the column, a foreign key, a check that nothing reverses itself, and a unique index so a transfer can
be reversed at most once. WP-07 could not have noticed - it had no reversal to store.

`feat(ledger): add the account use cases and their ports [TB-1008]`

### 4. The cursor-paged statement

A keyset query method on `JournalEntryRepository`, its SQL in `JdbcJournalEntryRepository` ordering
by `(value_date, posted_at, entry_ref, seq)`, and the `GetStatement` use case assembling per-page
opening and closing balances. `movementRef` is derived as `entryRef + "-" + %02d(seq)`, which
`posting.seq` already stores in list order.

Tests, with the expected figures written out as explicit numbers: a page foots - opening plus its
movements equals closing; page N's closing equals page N+1's opening; `nextCursor` is null on the
last page; and **a movement inserted between two page reads is neither skipped nor duplicated**. That
last one is the assertion an offset implementation fails, so it is the test that makes the constraint
mean something.

`feat(ledger): add the cursor-paged statement query [TB-1008]`

### 5. The transfer use case - locking, policy and append composed

This closes **F-22**.

`Transfer` loads both accounts inside `inTransactionLocking`, asserts both `canBePosted()` and that
both share the amount's currency, calls `Balance.afterEffect` on the debit side, builds the
`JournalEntry` and appends it. The overdraft decision goes through `afterEffect` rather than
`OverdraftPolicy.permits` directly, because `afterEffect` is the single place WP-06 put the rule that
a credit is never blocked and only a worsening effect is tested.

Tests: a transfer that would breach a forbidden overdraft is rejected and **nothing is written** -
the case F-22 states currently succeeds; a transfer against a `BLOCKED` account is rejected; a
currency mismatch is rejected; and the ring-transfer concurrency test, run through the use case
rather than the adapter, still conserves total value across the ring.

`feat(ledger): compose locking, overdraft policy and append into a transfer [TB-1008]`

### 6. The idempotency store

`V4__idempotency.sql`, and `JdbcIdempotencyStore` implementing the port with
`INSERT ... ON CONFLICT DO NOTHING` deciding the race.

Tests: a replayed key with the same fingerprint returns the stored response byte for byte; a
different fingerprint raises the conflict signal; and **two concurrent threads with the same key
produce one record and one execution**. The third is the one that matters - it proves the database
constraint is what holds, not the application check, and it is the test that fails if anyone later
"simplifies" the upsert into a read followed by a write.

`feat(ledger): add the idempotency store [TB-1008]`

### 7. The reversal and hold use cases

`ReverseTransfer` loads the original entry and calls `JournalEntry.reverse(newReference, valueDate)`.
The domain offers no other way to construct a reversal, which is deliberate: a reversal that does not
descend from its original cannot name it.

`PlaceHold`, `CaptureHold` - posting the transfer and clearing the hold in one transaction, so
available balance is never reduced twice - and `ReleaseHold`.

Tests: reversing twice is rejected; capturing an already-captured hold is rejected; a placed hold
reduces available balance and leaves booked unchanged; capture moves booked balance exactly once.
Fixtures use microsecond precision, because `timestamptz` does not store nanoseconds and an `Instant`
does, which turns a correct adapter into a failing equality assertion.

`feat(ledger): add the reversal and hold use cases [TB-1008]`

### 8. The module, and Problem Details

`services/ledger-api`: the Boot Gradle plugin, `spring-boot-starter-web`,
`spring-boot-starter-validation`, the first `main`, the first `application.yml`, the
`settings.gradle.kts` include, and both Makefile lists - `build-services` and `test-services` are
hand-maintained and neither wildcards. Java 17 toolchain, `-Xlint:all -Werror`, and the same JUnit,
AssertJ, Testcontainers and Boot BOM versions the other two modules pin literally, since there is no
root build file and no version catalogue.

A `@RestControllerAdvice` maps the domain's whole failure vocabulary to RFC 9457 `ProblemDetail`
served as `application/problem+json`: `OverdraftNotPermittedException` and `CurrencyMismatchException`
to `422`, `UnbalancedEntryException` to `422`, `IllegalArgumentException` from the `of` factories to
`400`, `IllegalStateException` from the hold transitions to `409`.
`spring.mvc.problemdetails.enabled=true` so framework failures take the same path, and the whitelabel
error page is disabled.

A container smoke test mirrors `ContainerSmokeTest`, so a stopped Docker daemon fails on one readable
test rather than on twenty stack traces.

Tests: a missing `Idempotency-Key` returns `problem+json` rather than Spring's default body, and **no
problem document contains a stack trace, a SQL fragment or an exception class name** - REQ-API-003
asserted directly rather than assumed from the handler's shape.

`build(services): add the ledger-api module with problem details [TB-1008]`

### 9. The read endpoints

Controllers and DTOs for `getAccount`, `getBalance`, `getStatement`, `getTransfer` and `listHolds`,
plus the mappers translating the domain's `JournalEntry`, `EntryRef` and `Posting` into the
contract's `Transfer`, `transferRef` and `Movement`, and the derived `Transfer.status`.

`Money` serialises as `amountMinor` plus `currency` and never as a decimal - asserted, not assumed. A
Jackson default would happily emit either, and `toPlainString()` is a few keystrokes away from any
mapper that gets written in a hurry.

`feat(api): expose the ledger read endpoints [TB-1008]`

### 10. The money-moving endpoints and the idempotency filter

`createTransfer`, `reverseTransfer`, `placeHold`, `captureHold` and `releaseHold`, each behind an
interceptor that computes the fingerprint, consults the store, and either replays the recorded
response or records a new one.

Tests: a replay returns the original response and the balance moved once; the same key with a changed
amount returns `409`; a key shorter than sixteen characters returns `400`.

`feat(api): make every money-moving endpoint idempotent [TB-1008]`

### 11. The contract test

Loads `contracts/openapi/ledger-core.yaml` and validates **every request and response the suite
exchanges** against the declared schema, plus a coverage assertion that fails if any `operationId` in
the document was never exercised. Drift in either direction fails the build, which is REQ-API-002.

Each asserted field is named against the `canonical-data-model.md` field it implements, as WP-02's
conformance instruction requires, so a reviewer can see that the API and the ledger domain mean the
same thing by `Money`.

*Implementation note.* The document is OpenAPI 3.1, whose schemas are JSON Schema 2020-12, and it
uses `type: [string, 'null']` and `examples`. Start with `swagger-request-validator-mockmvc`; if it
does not parse 3.1 faithfully, fall back to `networknt/json-schema-validator`, which implements
2020-12 directly. Whichever is used, the reason goes in the module README - a contract test that
silently downgrades the document it validates against is worse than none.

`test(api): hold the implementation to the openapi document [TB-1008]`

### 12. Documentation and evidence

- `services/ledger-api/README.md`, following the shape `ledger-persistence` set.
- `services/README.md` - the module table and what now exists.
- `services/ledger-core/README.md` - its header already names WP-08.
- `services/ledger-persistence/README.md` - the two new migrations.
- `docs/compliance/traceability-matrix.md` - the WP-08 section, flipping REQ-API-001, REQ-API-002 and
  REQ-API-003 from `Contract` to `Met`, each row naming the test **and the mutation shown to make it
  fail**.
- `Makefile` - the help text for `test-services`, now three modules.

`docs(services): document the ledger api and record evidence [TB-1008]`

## Definition of Done

- [ ] Every endpoint in the OpenAPI document is implemented and contract-tested.
- [ ] Replaying a transfer with the same key moves money once and returns the original response.
- [ ] The same key with a different body returns 409.
- [ ] Every error path returns a Problem Details document.

## Verification

```bash
bash contracts/validate.sh
make test-services
```

Both need their prerequisites: `contracts/validate.sh` downloads Redocly on first run, and
`test-services` needs a running Docker daemon for Testcontainers - `make docker` says so in one line
rather than in a stack trace.

Then the manual walkthrough:

1. Open two accounts through `POST /accounts`.
2. Transfer between them; confirm `201` and that both balances moved.
3. Replay the request with an identical `Idempotency-Key` and body; confirm the response is
   **byte-identical**, the status is `200`, and the balance moved once.
4. Replay the same key with a changed amount; confirm `409` and a `problem+json` body.
5. Read the statement across a page boundary; confirm each page foots and `nextCursor` chains.

Negative evidence, to the standard PR #19 set for WP-07 - each pasted into the pull request as a real
failing run before the fix is restored:

- delete the `lockInOrder` delegation in `JdbcUnitOfWork` and show the ring test deadlocking;
- drop the primary key on `idempotency_record.key` and show the concurrent-replay test double
  spending;
- remove the `Balance.afterEffect` call and show the F-22 test writing the forbidden entry;
- add a field to a response DTO that the OpenAPI document does not declare, and show the contract
  test failing on it.

The command named here when this package was written was `./gradlew :services:ledger-core:test`, on
the assumption that everything would live in one module. It does not, for the reason given in task
4's preamble, so the verification runs through `make test-services`, which covers all three.

**Contract conformance (WP-02).** The OpenAPI contract test must trace explicitly to
[`../../architecture/canonical-data-model.md`](../../architecture/canonical-data-model.md):
each asserted field named against the model field it implements, so a reviewer can see that the
API and the ledger domain mean the same thing by `Money`.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-API-001 Money-moving requests are idempotent | idempotency store |
| REQ-API-002 The implementation cannot drift from its contract | contract test |
| REQ-API-003 Errors are machine-readable and leak nothing | RFC 9457 handler |
