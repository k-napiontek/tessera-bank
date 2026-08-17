# ledger-core

**Stratum 3** | **Java 17, Spring Boot 3.2** | **Built by WP-06, WP-07, WP-08, WP-09**

The double-entry ledger. Money as minor units with per-currency scale, never floating point. Accounts typed ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE - a customer's current account is a **liability** of the bank. Immutable balanced journal entries, corrected only by reversals. Deterministic lock ordering. Idempotency keys. Hash-chained audit. Transactional outbox.

This is the deepest code in the repository and the part that has to be right. See
[`docs/plan/master-plan.md`](../../docs/plan/master-plan.md) section 4 and the WP-06 to WP-09 work
packages.

## What exists now

WP-06 has landed the **domain only**: pure Java 17, no framework on the compile classpath at all.
Persistence (WP-07), the REST API (WP-08) and the audit chain and outbox (WP-09) are still to come,
so nothing here is wired to a database or an endpoint yet.

| Package | Holds |
|---|---|
| `bank.tessera.ledger.domain` | `Money`, `CurrencyCode`, `Account`, `AccountType`, `Direction`, `Posting`, `JournalEntry`, `Balance`, `Hold`, and the reference types |
| `bank.tessera.ledger.port` | Repository interfaces the domain owns. No implementations - WP-07 supplies those. |

### Building

```bash
./gradlew :services:ledger-core:test
```

Requires **JDK 17** - the stratum 3 pin, asserted by `ToolchainTest` on every build rather than
assumed. On a machine where 17 is not the default, point `JAVA_HOME` at it.

The suite touches no database, no container and no network, and runs in about a second. That is a
consequence of the zero-framework rule rather than a happy accident, and `DomainPurityTest` keeps it
that way by failing on any framework import.

### Two decisions worth knowing

**`Account` carries no balance.** A balance is derived from postings. Storing one on the aggregate
would create a second source of truth on day one - precisely the drift `batch/recon` exists to
detect between this ledger and the mainframe.

**`Money` arithmetic throws on overflow.** `Math.addExact`, not `+`. A ledger that silently wraps at
`Long.MAX_VALUE` is worse than one that fails, because the failure is discoverable and the wrap is
not.

