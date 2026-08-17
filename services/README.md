# services - the modern tier

**Stratum 3** | **Vintage ~2023** | **Java 17, Spring Boot 3.2, PostgreSQL, Flyway, Kafka, Gradle** | **Built by WP-06 to WP-09**

The new double-entry ledger - the strangler fig growing around the mainframe core. Real-time, idempotent, audited, and reconciled against the old core every morning.

**Java 17, not the newest release.** The modern tier of a real bank runs two to four years behind current, not on the bleeding edge. Making this current would be less realistic, not more.

## Contents

| Directory | Holds |
|---|---|
| `ledger-core/` | The double-entry ledger. The production-grade component of this repository. |
| `payment-engine/` | ISO 20022 outbound payments. On the map, out of initial scope. |

## Architecture

Hexagonal, with the boundaries enforced by ArchUnit tests rather than by convention. The domain layer
has zero framework imports and is testable in milliseconds without a database.

