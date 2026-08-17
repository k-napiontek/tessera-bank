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

## The rule

**The contract changes before the implementation does.** A contract test enforces the agreement and
fails the build when they drift. This is the only mechanism preventing four independently-built tiers
from quietly disagreeing about what a transfer is.

