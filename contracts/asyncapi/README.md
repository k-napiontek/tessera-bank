# AsyncAPI contracts

**~2023** | **Built by WP-02, extended by WP-11a**

| Document | Owner | Channels |
|---|---|---|
| [`ledger-events.yaml`](ledger-events.yaml) | `services/ledger-core` | `tessera.ledger.transfer-posted.v1`, `tessera.fraud.decision.v1` |
| [`esb-adapter-events.yaml`](esb-adapter-events.yaml) | `integration/esb-adapter` | `tessera.esb.transfer-posted.dlt.v1` |

**Two documents, because a contract is framed from its owner's point of view.** `ledger-events.yaml`
describes what the ledger sends and receives; everything on it is something that happened to the
bank. A dead letter is something that happened to a *consumer*, so it lives in a document that
consumer owns - otherwise the producer's contract changes every time somebody downstream adds a
failure mode.

That split is the estate acting on what F-34 and F-51 both record: an error surface belongs in a
contract, not in a README.

Delivery is **at-least-once** - the transactional outbox relay may republish. That is stated in the contract rather than assumed, because every consumer must handle duplicates idempotently.

**Source:** [`canonical-data-model.md`](../../docs/architecture/canonical-data-model.md). Every schema traces to a concept defined there.

Messages are keyed by `transferRef`, so events for one transfer keep their order. There is no
ordering guarantee between different transfers, and none is needed.
