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

To be detailed before execution.

## Definition of Done

- [ ] Migrations apply cleanly to an empty database and are idempotent on re-run.
- [ ] The concurrency test runs N threads transferring around a ring of accounts and asserts total
      value is conserved, with no lost update and no deadlock.
- [ ] The reconciliation routine reports zero drift over a generated dataset.
- [ ] ArchUnit tests pass, including the domain-has-no-framework-imports rule.

## Verification

`./gradlew :services:ledger-core:test` with Docker available. Assert specifically: the ring-transfer
concurrency test conserves total value; reconciliation reports zero drift; a deliberately corrupted
balance row is detected by reconciliation.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-LED-005 Concurrent transfers cannot lose an update or deadlock | deterministic lock ordering |
| REQ-LED-006 Materialised balances are verifiable, not assumed | reconciliation routine |
| REQ-LED-007 Postings cannot be updated or deleted | schema constraints |
| REQ-ARC-001 Domain layer is free of framework dependencies | ArchUnit tests |
