# ledger-core

**Stratum 3** | **Java 17, Spring Boot 3.2** | **Built by WP-06, WP-07, WP-08, WP-09**

The double-entry ledger. Money as minor units with per-currency scale, never floating point. Accounts typed ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE - a customer's current account is a **liability** of the bank. Immutable balanced journal entries, corrected only by reversals. Deterministic lock ordering. Idempotency keys. Hash-chained audit. Transactional outbox.

This is the deepest code in the repository and the part that has to be right. See
[`docs/plan/master-plan.md`](../../docs/plan/master-plan.md) section 4 and the WP-06 to WP-09 work
packages.

