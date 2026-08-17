# integration - the bridge between eras

**Stratum 2** | **Vintage ~2019** | **Java 8, Spring Boot 2.7.18, Spring Integration, JMS, XSLT** | **Built by WP-11**

The layer that made modernisation possible without touching the core, and the layer nobody wants to own, because understanding it requires knowing all four eras at once. Without it the strangler fig has no trunk.

## Contents

| Directory | Holds |
|---|---|
| `esb-adapter/` | Kafka to XSLT to SOAP, and COMP-3 fixed-width output for the mainframe |

## Constraints

- **Spring Boot 2.7.18 on Java 8.** This is the exact version block the industry is pinned to, and
  reproducing it is why this stratum exists. Spring Boot 2.x was the last line supporting Java 8.
- The **COMP-3 encoder** must be tested against real bytes from `mainframe/`, not against its own
  understanding of the format. Positive values end `0x0C`, negative `0x0D`.
- Delivery is at-least-once, so duplicate handling must be idempotent and tested with an actual
  redelivery.

## Do not modernise

See [`CLAUDE.md`](../CLAUDE.md) and [ADR 0002](../docs/governance/adr/0002-deliberate-legacy-strata.md).

