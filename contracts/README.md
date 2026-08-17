# contracts - interfaces, one folder per era

**Spans all strata** | **Built by WP-02**

Every interface in the estate, defined before it is implemented, in the contract language native to its era. The transfer flow crosses four decades of integration style, so its contracts do too - the same business concepts expressed four ways, differing by era rather than by modelling.

## Contents

| Directory | Era | Format | Consumers |
|---|---|---|---|
| `copybook/` | ~1995 | COBOL fixed-width record layouts | `mainframe/`, `integration/` |
| `wsdl/` `xsd/` | ~2011 | SOAP contracts, canonical XML | `legacy/`, `integration/` |
| `openapi/` | ~2023 | OpenAPI 3.1 | `services/`, `edge/` |
| `asyncapi/` | ~2023 | AsyncAPI 3.0 (Kafka) | `services/`, `integration/`, `edge/` |

## The source

All four families derive from one document:
[`docs/architecture/canonical-data-model.md`](../docs/architecture/canonical-data-model.md).

It defines `Money`, `Account`, `Movement`, `Transfer`, `Hold` and `FraudDecision` once, and shows
each field's representation in all four eras side by side. Every field in every contract here traces
back to it, and no contract invents a concept of its own.

## The rules

1. **The contract changes before the implementation does.** A contract test enforces the agreement
   and fails the build when they drift. This is the only mechanism preventing four
   independently-built tiers from quietly disagreeing about what a transfer is.
2. **The model changes before the contract does.** When a contract and the canonical model disagree,
   the model is corrected first and every contract derived from it is corrected together. Patching
   one contract to match another is how the four drift apart.

## Validating

```bash
bash contracts/validate.sh
```

Runs XML well-formedness, the OpenAPI and AsyncAPI linters, and
[`check-copybook-offsets.py`](check-copybook-offsets.py), which asserts that every copybook field
still sits where the canonical model says it does.

A green run proves each contract is well-formed. It does **not** prove the four agree with each
other - only reading them beside the canonical model proves that.

