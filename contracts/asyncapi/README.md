# AsyncAPI contracts

**~2023** | **Built by WP-02**

Kafka event contracts: transfer posted, fraud decision.

Delivery is **at-least-once** - the transactional outbox relay may republish. That is stated in the contract rather than assumed, because every consumer must handle duplicates idempotently.

**Source:** [`canonical-data-model.md`](../../docs/architecture/canonical-data-model.md). Every schema traces to a concept defined there.

Messages are keyed by `transferRef`, so events for one transfer keep their order. There is no
ordering guarantee between different transfers, and none is needed.
