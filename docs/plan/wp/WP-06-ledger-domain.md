# WP-06 - Ledger domain

| | |
|---|---|
| **Ticket** | TB-1006 |
| **Branch** | `feat/TB-1006-ledger-domain` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-02 |
| **Status** | `Not started` |

## Objective

Build the double-entry ledger domain as pure Java with no framework dependency at all - no Spring, no
JPA, no database. Every rule that makes a ledger a ledger rather than a table of balances lives here
and is provable by unit test in milliseconds. This is the deepest, most important code in the
repository, and it must be correct before anything is allowed to persist it.

## In scope

- `Money` - minor units as `long` plus an ISO 4217 currency, with scale resolved per currency so JPY
  (0 decimals) and BHD (3 decimals) are both handled correctly. Arithmetic that refuses to mix
  currencies.
- `Account` - typed `ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE`, single currency, owner,
  status, overdraft policy.
- `JournalEntry` - atomic, balanced, immutable set of postings.
- `Posting` - one debit or credit leg against one account.
- `Balance` - booked and available; `Hold` reducing available.
- Reversal semantics: a compensating entry referencing the original.
- Repository **ports** as interfaces only.
- Unit tests and property-based tests (jqwik) over the invariants.

## Out of scope

- Any persistence, SQL, migration or database - WP-07.
- Any REST endpoint, DTO or Spring configuration - WP-08.
- Audit chain and outbox - WP-09.

## Constraints

- **Zero framework imports.** No `org.springframework`, no `jakarta.persistence`. An ArchUnit test
  added in WP-07 will enforce this permanently; write the code as though it already does.
- Money is never a floating-point type. No `double`, no `float`, and `BigDecimal` only at the
  presentation boundary.
- A customer's current account is a **liability** of the bank; cash and reserves are **assets**.
  Sign conventions follow from account type and must be explicit, not implied.
- Domain objects are immutable. Operations return new instances.

## Tasks

Eight tasks, roughly one commit each - inside the 3-10 that `PROTOCOL.md` sets. Each is test-first:
the failing test, then the implementation, then the refactor.

### Two things settled before task 1

**Naming.** [`canonical-data-model.md`](../../architecture/canonical-data-model.md) is the authority
for every concept that crosses a tier boundary. Where this package's vocabulary differs from the
model's, the mapping is fixed here and not re-litigated per task:

| Canonical model | This package | Note |
|---|---|---|
| `Movement` | `Posting` | The same thing - one leg of a double-entry pair. `Posting` is the accounting term and stays inside the domain; `Movement` is what crosses the wire. |
| `Transfer` | `JournalEntry` | A transfer is the customer's intent; a journal entry is its accounting form. The domain models the latter. |
| `Money`, `Account`, `Hold` | same | No divergence. |

**Invariant 3 and mixed currency.** This package's invariant 3 allows "no mixed-currency entry without
an explicit FX leg", while the canonical model states single currency throughout with no conversion
anywhere. `JournalEntry` **rejects a mixed-currency entry outright**. That satisfies invariant 3
literally - no mixed-currency entry exists at all, so none exists without an FX leg - and matches the
model exactly, so neither document has to be contradicted. FX arrives with `payment-engine`, which is
deliberately out of initial scope.

### 1. Gradle skeleton

`settings.gradle.kts` registering `:services:ledger-core`, and
`services/ledger-core/build.gradle.kts` using the `java-library` plugin with a **Java 17 toolchain**,
JUnit 5, jqwik and AssertJ. Commit the Gradle wrapper.

One test that asserts the running toolchain reports Java 17, so the stratum pin is proved on every
build rather than assumed. `build(ledger): add gradle skeleton for ledger-core [TB-1006]`

### 2. Money

`Money` as an immutable value type: a `long` count of minor units plus a `CurrencyCode` carrying its
ISO 4217 scale, transcribed from the model's scale table.

- `plus`, `minus`, `negate`, `abs`, `isPositive`, `compareTo`.
- Mixing currencies throws. There is no conversion, and no overload that silently permits one.
- Arithmetic uses `Math.addExact` and friends, so overflow throws instead of wrapping. A ledger that
  wraps at `Long.MAX_VALUE` is worse than one that fails.
- An unknown currency code is rejected, not defaulted to two decimals.

Property tests: `plus` is commutative and associative; `minus` inverts `plus`; no operation on valid
inputs ever produces a silently wrong result. Unit tests cover PLN (scale 2), JPY (0) and BHD (3).
`feat(ledger): add money value type [TB-1006]`

### 3. Account types and sign conventions

`AccountType` enumerating `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`, each knowing its
normal balance, and a `Direction` of `DEBIT` or `CREDIT`.

The signed effect of a posting is a function of the two, and is explicit rather than implied: a
`LIABILITY` credited increases; an `ASSET` debited increases. A customer's current account is a
liability of the bank, and the test says so in those words.
`feat(ledger): add account types and sign conventions [TB-1006]`

### 4. Account

`Account` as an immutable record: account reference, customer reference, type, currency, status
(`OPEN`, `BLOCKED`, `CLOSED`) and overdraft policy. References are validated against the patterns in
the canonical model, so a malformed reference cannot enter the domain at all.

No balance field. A balance is derived from postings and belongs to task 6; storing it here would
create a second source of truth on day one.
`feat(ledger): add account aggregate [TB-1006]`

### 5. Postings and journal entries

`Posting` - one leg: account reference, direction, amount, always strictly positive.

`JournalEntry` - an atomic, immutable, balanced set. The factory rejects, each with its own test:

- an entry whose debits and credits do not sum equal;
- an entry of fewer than two postings;
- an entry mixing currencies;
- a posting of zero or negative amount.

**The property test lives here** and is the centre of this package: for any generated set of
postings, the factory either returns a balanced entry or rejects the input. There is no third
outcome, and no path that produces an unbalanced `JournalEntry`.
`feat(ledger): add balanced journal entries [TB-1006]`

### 6. Balances and holds

`Balance` carrying booked and available. `Hold` with reference, account, amount, status
(`PLACED`, `CAPTURED`, `RELEASED`, `EXPIRED`) and timestamps.

- Placing a hold reduces available and leaves booked untouched.
- Capturing reduces booked and clears the hold in one operation, so available is never reduced twice.
- Releasing restores available.
- `available == booked - sum(holds still PLACED)` is asserted as a property, not just an example.
- An account whose overdraft policy forbids it cannot be taken negative - this package's invariant 5.

`feat(ledger): add balances and holds [TB-1006]`

### 7. Reversal semantics

Reversing an entry produces a **new** entry with every direction flipped, the same amounts, and a
reference to the original. The original is not touched; there is no setter, no mutating method and no
way to reach one.

Tests: a reversal balances; reversing a reversal restores the original shape; the original instance
is unchanged after both.
`feat(ledger): add reversal semantics [TB-1006]`

### 8. Repository ports and the purity check

`AccountRepository`, `JournalEntryRepository` and `HoldRepository` as **interfaces only**, expressed
in domain types. No implementation, no SQL, no annotation - WP-07 supplies the adapters.

Plus a test that scans the domain sources and fails on any import of `org.springframework`,
`jakarta.*` or `javax.persistence`. WP-07 replaces it with a proper ArchUnit rule; until then the
constraint is enforced rather than merely written down.
`feat(ledger): add repository ports [TB-1006]`

## Definition of Done

- [ ] Every invariant below has at least one test that fails when the invariant is broken.
- [ ] Property-based tests assert that any generated journal entry either balances or is rejected.
- [ ] No framework import exists anywhere in the domain package.
- [ ] Test suite runs in under five seconds - it touches nothing external.

### The invariants

1. Every journal entry balances: sum of debits equals sum of credits, per currency.
2. Postings are append-only; corrections happen only by reversal entries referencing the original.
3. No mixed-currency entry without an explicit FX leg.
4. An account's balance equals the sum of its signed postings.
5. Accounts marked `no_overdraft` cannot go negative.

## Verification

`./gradlew :services:ledger-core:test` - unit and property tests green, with no container, database
or network involved.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-LED-001 Journal entries always balance | `JournalEntry` + property test |
| REQ-LED-002 Postings are immutable; corrections are reversals | `Posting`, reversal semantics |
| REQ-LED-003 Money is exact and currency-aware | `Money` |
| REQ-LED-004 Account type determines sign convention | `Account`, `AccountType` |
