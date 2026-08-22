# integration - the bridge between eras

**Stratum 2** | **Vintage ~2019** | **Java 8, Spring Boot 2.7.18, Spring Kafka, JAX-WS SOAP client, XSLT** | **Built by WP-11**

The layer that made modernisation possible without touching the core, and the layer nobody wants to own, because understanding it requires knowing all four eras at once. Without it the strangler fig has no trunk.

## Contents

| Directory | Holds |
|---|---|
| `esb-adapter/` | Kafka to XSLT to SOAP to COMP-3. **Complete**: WP-11a built the 2019-to-2011 hop, WP-11b the 2011-to-1995 one |

## Constraints

- **Spring Boot 2.7.18 on Java 8.** This is the exact version block the industry is pinned to, and
  reproducing it is why this stratum exists. Spring Boot 2.x was the last line supporting Java 8.
- The **COMP-3 encoder** must be tested against real bytes from `mainframe/`, not against its own
  understanding of the format. Positive values end `0x0C`, negative `0x0D`.
- Delivery is at-least-once, so duplicate handling must be idempotent and tested with an actual
  redelivery.
- **This tier does not sort the movement file.** `STEP010` of the overnight cycle does, and that is
  the entire reason that step exists.
- Nothing is written to the movement file unless the SOAP call succeeded. The two hops are ordered,
  not parallel, and the work package was split on that line.

## The one transfer that crosses everything

`FourEraTransferIT` is the only test in this repository that takes a single transfer through every
era: a Kafka event becomes canonical XML, then a SOAP call into a really-deployed `customer-master`
on Tomcat 8.5 over real Oracle, then two COMP-3 records in a fixed-width file, then a real GnuCOBOL
overnight cycle that applies them to the account master. It asserts the balance moved by the same
amount in 2011 and in 1995.

That is what the [master plan's section 3](../docs/plan/master-plan.md) describes, and until WP-11b
nothing had done it.

## Do not modernise

See [`CLAUDE.md`](../CLAUDE.md) and [ADR 0002](../docs/governance/adr/0002-deliberate-legacy-strata.md).

