# Requirements traceability matrix

> **STUB.** Outline only. Filled by **every work package**.

Requirement to design to code to test, for the whole estate. This is the artefact an auditor samples: every requirement must resolve to an implementation and to a test that would fail without it. Each work package updates it as part of its Definition of Done.

## Planned contents

- REQ-LED-\* ledger invariants and money handling
- REQ-API-\* interface behaviour, idempotency, error contracts
- REQ-MF-\* mainframe batch processing
- REQ-INT-\* cross-era integration
- REQ-AUD-\*, REQ-EVT-\* audit trail and event delivery
- REQ-DP-\* data protection
- REQ-OPS-\*, REQ-REC-\*, REQ-REP-\* operations, reconciliation, reporting
- REQ-GOV-\*, REQ-ARC-\*, REQ-EST-\*, REQ-DORA-\* governance, architecture, estate fidelity, resilience

---

## WP-02 - canonical data model and contracts

Filled by [WP-02](../plan/wp/WP-02-contracts.md), ticket TB-1002.

**Status values.** `Met` - satisfied and verified now. `Contract` - the contract enforces it, and the
implementation that must also satisfy it does not exist yet. `Planned` - named here so it cannot be
forgotten, verified by a later package.

| Requirement | Description | Design | Verified by | Status |
|---|---|---|---|---|
| REQ-INT-001 | Every interface is defined by a contract before implementation exists | [`contracts/`](../../contracts/) | `bash contracts/validate.sh` exits 0 with no implementation in the repository | Met |
| REQ-INT-002 | Each era's contract is idiomatic to that era | copybook (1995), WSDL/XSD (2011), OpenAPI (2023), AsyncAPI (2023) | COBOL-85 fixed format checked by column; document/literal wrapped checked by the WSDL naming rule; `@redocly/cli lint`; `@asyncapi/parser` | Met |
| REQ-INT-006 | Business concepts are defined once and shared across every era | [`canonical-data-model.md`](../architecture/canonical-data-model.md) | Cross-era representation table, section 9 - every field in all four eras side by side | Met |
| REQ-INT-007 | Contract conformance is checked, not assumed | [`validate.sh`](../../contracts/validate.sh), [`check-copybook-offsets.py`](../../contracts/check-copybook-offsets.py) | The checker was shown to fail on a resized field and on a record-length change before being accepted | Met |
| REQ-LED-001 | Money is minor units plus an ISO 4217 code, never floating point | Canonical model section 2 | The OpenAPI document contains no `number` or float type; the XSD rejects `1234567.89` as an amount | Contract |
| REQ-LED-002 | Currency scale is resolved per currency from ISO 4217 | Canonical model section 2, scale table | Scale table carries JPY (0) and BHD (3) so a hard-coded 2 fails rather than passes quietly | Contract |
| REQ-LED-003 | Postings are balanced, double-entry and immutable | Canonical model section 8, invariants 1, 2 and 5 | The XSD rejects a transfer carrying one movement; OpenAPI `movements` is `minItems: 2, maxItems: 2` | Contract |
| REQ-LED-004 | Corrections are reversals that reference the original, never mutations | Canonical model section 5, `reversesTransferRef` | `POST /transfers/{transferRef}/reversals` returns a new transfer; no endpoint mutates a posted one | Contract |
| REQ-API-001 | Every money-moving operation is idempotent | Canonical model section 8, invariant 7 | `Idempotency-Key` required on all five money-moving operations in the OpenAPI document | Contract |
| REQ-API-002 | Errors follow RFC 9457 Problem Details | `Problem` schema, `application/problem+json` | Every error response in the OpenAPI document uses it | Contract |
| REQ-MF-001 | Packed-decimal amounts are byte-identical across tiers | Canonical model section 2, COMP-3 | Worked examples verified against an encoder: positive, negative, zero and maximum | Contract |
| REQ-MF-002 | Fixed-width records keep their layout for the life of the file | Canonical model section 9, framing rules | `check-copybook-offsets.py` asserts field offsets and record lengths | Met |
| REQ-EVT-001 | Event delivery is at-least-once, and consumers are told so | [`ledger-events.yaml`](../../contracts/asyncapi/ledger-events.yaml) | Stated in `info.description` and per channel, with the de-duplication key named | Met |
| REQ-DP-002 | The ledger holds account references and no customer identity | Canonical model sections 3 and 10 | No contract carries a name, address, national identifier or IBAN | Met |

### Verified by a later package

| Requirement | Description | Verified by |
|---|---|---|
| REQ-INT-003 | Generated mainframe data matches the canonical byte layout | WP-03 |
| REQ-INT-004 | The implementation of the REST API matches its contract | WP-08 |
| REQ-INT-005 | SOAP responses validate against the canonical XSD | WP-10 |
| REQ-MF-003 | The COMP-3 encoder agrees with the mainframe byte for byte | WP-11 |
