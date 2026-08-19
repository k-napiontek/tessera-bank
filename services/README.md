# services - the modern tier

**Stratum 3** | **Vintage ~2023** | **Java 17, Spring Boot 3.2, PostgreSQL, Flyway, Kafka, Gradle** | **Built by WP-06 to WP-09**

The new double-entry ledger - the strangler fig growing around the mainframe core. Real-time, idempotent, audited, and reconciled against the old core every morning.

**Java 17, not the newest release.** The modern tier of a real bank runs two to four years behind current, not on the bleeding edge. Making this current would be less realistic, not more.

## Contents

| Directory | Holds |
|---|---|
| `ledger-core/` | The double-entry ledger domain. Pure Java, no framework on its classpath at all. |
| `ledger-persistence/` | PostgreSQL adapters behind the ledger's ports: schema, locking, reconciliation. |
| `ledger-api/` | The REST adapter: controllers, wire types, RFC 9457 errors, idempotency. The only Spring Boot application here. |
| `payment-engine/` | ISO 20022 outbound payments. On the map, out of initial scope. |

## Architecture

Hexagonal, with the boundaries enforced by ArchUnit tests rather than by convention. The domain layer
has zero framework imports and is testable in milliseconds without a database.

**The boundary is a module boundary, not a package convention.** `ledger-core` has no framework on its
compile classpath, so a Spring import there fails to compile - the compiler enforces the rule and a test
is not the only thing standing between the domain and a framework. `ledger-persistence` holds both, and
its ArchUnit rules police the direction of the dependency.

## What exists now

WP-06 built the domain: `Money`, `Account`, `JournalEntry`, `Posting`, `Hold`, `Balance` and the three
ports, driven out by unit and property tests. WP-07 built the persistence behind those ports - Flyway
schema, JDBC adapters, deterministic lock ordering and a reconciliation routine - against real
PostgreSQL via Testcontainers.

WP-08 composed them and put the result behind HTTP. The **use cases** live in `ledger-core` beside
the domain, driven by fakes with no database at all: opening an account, transferring, reversing,
placing and capturing holds, and a cursor-paged statement. They reach infrastructure through a
`UnitOfWork` port, so the transaction boundary is an application concern while the deterministic
lock ordering stays in the adapter where WP-07 put it. `ledger-api` holds the web adapter and the
idempotency filter that makes a retry safe.

Follow-up **F-22** is closed: `Transfer` consults `Balance.afterEffect` inside the lock, so an entry
can no longer take a forbidden-overdraft account below zero.

```bash
make docker        # the persistence and API tests need a running daemon
make test-services # all three modules
```

The audit chain, the transactional outbox, metrics and structured logging are WP-09.

