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

To be detailed before execution.

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
