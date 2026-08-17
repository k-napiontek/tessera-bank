# WP-02 - Canonical data model and contracts

| | |
|---|---|
| **Ticket** | TB-1002 |
| **Branch** | `feat/TB-1002-contracts` |
| **Stratum** | n/a - spans all eras |
| **Depends on** | WP-01 |
| **Status** | `Not started` |

## Objective

Define every interface in the estate before any implementation exists - and, first, define the
business concepts those interfaces express, exactly once.

The transfer flow crosses four decades of integration style, so its contracts do too: a COBOL
copybook, a WSDL with its XSD types, an OpenAPI document and an AsyncAPI document. Written
independently they would drift, and the drift would not surface until WP-11 tried to encode COMP-3
bytes that WP-03 had laid out differently. The **canonical data model** prevents that: one definition,
from which all four contracts are derived, and against which four downstream packages check
themselves.

## In scope

- **`docs/architecture/canonical-data-model.md`** - the single definition of `Money`, `Account`,
  `Movement` and `Transfer`, field by field, with each field's representation in all four eras shown
  side by side, and the ISO 4217 currency-scale table.
- `contracts/copybook/` - `ACCTREC`, `MOVEREC`, `REJREC` fixed-width layouts with a column map.
- `contracts/xsd/` + `contracts/wsdl/` - canonical XML types and the customer-master SOAP interface.
- `contracts/openapi/` - the ledger-core REST API.
- `contracts/asyncapi/` - Kafka event contracts (transfer posted, fraud decision).
- `contracts/validate.sh` and `contracts/check-copybook-offsets.py` - the validation the Definition of
  Done depends on.
- Conformance requirements added to WP-03, WP-08, WP-10 and WP-11.

## Out of scope

- Any implementation of any contract.
- Code generation, generators or build plugins. A validation script is tooling, not a build plugin.
- Compiling the copybook - that needs GnuCOBOL and belongs to WP-03. Here the copybook is checked by
  column arithmetic only. **This split is deliberate; do not treat the missing compile as an
  oversight.**
- ISO 20022 payment contracts and ISO 8583 card contracts - both belong to services deliberately out
  of initial scope.
- Detailing WP-03, WP-08, WP-10 or WP-11. Task 8 adds a conformance line to each; it does not write
  their task lists.

## Constraints

- **The canonical model comes first.** Every contract is derived from it, and any disagreement is
  resolved by changing the model, then the contracts - never by patching one contract.
- Each contract must be idiomatic for its era: COBOL-85 fixed format; WSDL document/literal wrapped
  as a 2011 bank would have written it; OpenAPI 3.1; AsyncAPI 3.0.
- **Money is minor units plus an ISO 4217 code everywhere.** Never a decimal string, never a float.
  In the copybook it is `PIC S9(13)V99 COMP-3` - 8 bytes packed, sign nibble `0x0C` positive and
  `0x0D` negative.
- Currency scale is resolved from ISO 4217, so PLN and EUR are 2 decimals, JPY 0 and BHD 3. One
  table, used by every tier.
- Errors in the REST contract follow RFC 9457 Problem Details.
- The AsyncAPI contract must **state** that delivery is at-least-once, because the transactional
  outbox relay may republish. Consumers must not be left to discover this.

## Tasks

Each task is roughly one commit. Eight commits is within the 3-10 guideline in `PROTOCOL.md`.

1. **Canonical data model.** Write `docs/architecture/canonical-data-model.md`: `Money`, `Account`,
   `Movement`, `Transfer`; a field table per concept; the cross-era mapping table; the ISO 4217 scale
   table; and the COMP-3 byte layout spelled out with a worked example including a negative amount
   and zero.
2. **Copybooks.** `contracts/copybook/ACCTREC.CPY`, `MOVEREC.CPY`, `REJREC.CPY`, plus a column map
   giving every field its start position, length and picture clause.
3. **XSD.** `contracts/xsd/` - canonical XML types for account, movement and transfer, derived from
   the model.
4. **WSDL.** `contracts/wsdl/` - the customer-master interface, document/literal wrapped, importing
   the XSD types rather than redefining them.
5. **OpenAPI.** `contracts/openapi/ledger-core.yaml` - accounts, balance, statement, transfers,
   holds, reversals; RFC 9457 error responses; `Idempotency-Key` documented as required on every
   money-moving operation.
6. **AsyncAPI.** `contracts/asyncapi/ledger-events.yaml` - transfer-posted and fraud-decision
   channels, with at-least-once delivery stated in the contract.
7. **Validation.** `contracts/check-copybook-offsets.py` (asserts field offsets and record lengths
   match the canonical model) and `contracts/validate.sh` (runs every check below). Update the six
   contract-folder READMEs to name the model as their source.
8. **Wire the conformance checks.** Add one line to the Definition of Done and one to the
   Verification of WP-03, WP-08, WP-10 and WP-11, per the table below. Update
   `docs/compliance/traceability-matrix.md`.

### Conformance checks added by task 8

| Package | Check |
|---|---|
| WP-03 | Generated master and movement records match the canonical byte layout, asserted by hex dump |
| WP-08 | OpenAPI contract test explicitly traced to the canonical model |
| WP-10 | SOAP responses validate against the canonical XSD |
| WP-11 | COMP-3 encoder output compared byte for byte against the WP-03 fixtures |

## Definition of Done

- [ ] `docs/architecture/canonical-data-model.md` defines every concept used by more than one tier.
- [ ] All four contract families exist and pass their validators.
- [ ] Every field in every contract traces to a field in the canonical model - no contract invents a
      concept.
- [ ] The COMP-3 worked example covers a positive amount, a negative amount and zero.
- [ ] `contracts/validate.sh` exits 0 and is runnable from a clean checkout.
- [ ] WP-03, WP-08, WP-10 and WP-11 each carry their conformance check.
- [ ] Traceability matrix updated.
- [ ] [Definition of Done](../../ways-of-working/definition-of-done.md) satisfied.

## Verification

Tools confirmed present on the development machine.

```bash
# XML: well-formedness and schema validity
xmllint --noout contracts/xsd/*.xsd contracts/wsdl/*.wsdl

# OpenAPI 3.1
npx --yes @redocly/cli lint contracts/openapi/*.yaml

# AsyncAPI 3.0
npx --yes @asyncapi/cli validate contracts/asyncapi/*.yaml

# Copybook field offsets against the canonical model
python3 contracts/check-copybook-offsets.py

# Everything at once
bash contracts/validate.sh
```

Expected: all exit 0. Then the check that actually matters - open the canonical model beside each of
the four contracts and confirm the same concept carries the same semantics in each. A validator
proves a contract is well-formed; only this proves the four agree.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-INT-001 Every interface is defined by a contract before implementation | `contracts/` |
| REQ-INT-002 Each era's contract is idiomatic to that era | copybook / WSDL / OpenAPI / AsyncAPI |
| REQ-INT-006 Business concepts are defined once and shared across eras | `canonical-data-model.md` |
| REQ-INT-007 Contract conformance is checked, not assumed | `validate.sh`, WP-03/08/10/11 checks |
