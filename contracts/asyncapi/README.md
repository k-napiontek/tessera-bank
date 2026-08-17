# AsyncAPI contracts

**~2023** | **Built by WP-02**

Kafka event contracts: transfer posted, fraud decision.

Delivery is **at-least-once** - the transactional outbox relay may republish. That is stated in the contract rather than assumed, because every consumer must handle duplicates idempotently.

