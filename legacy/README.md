# legacy - the Java EE monolith

**Stratum 1** | **Vintage ~2011** | **Java 8, Servlet 3.0/JSP, JAX-WS SOAP, Maven 3, WAR on Tomcat 8.5, Oracle dialect** | **Built by WP-10, WP-15**

The system of record for customers and account metadata, and the internal operations screens. Built when SOAP was the correct enterprise answer, extended continuously ever since, and still running because the cost of replacing it has never cleared a business case.

## Contents

| Directory | Holds |
|---|---|
| `customer-master/` | Customer and account metadata over WSDL-first SOAP, packaged as a WAR and deployed to a real Tomcat 8.5 by its own test. Complete: WP-10a built the schema and the PL/SQL, WP-10b the endpoint and the WAR |
| `backoffice/` | JSP + jQuery operator screens for recon breaks and rejects |

## Constraints

- **Java 8 only.** No `var`, no records, no sealed types, no text blocks, no `List.of`. Write the
  Java a competent 2011 engineer would have written - a reader should be able to date this code from
  its style alone.
- **WSDL-first.** The contract is authored, the code is generated from it. Never the reverse.
- Oracle dialect with business logic in stored procedures, as a 2011 team would have done.
- Tomcat 8.5 is out of community support. That is registered as
  [accepted risk](../docs/technical-debt.md), not a defect.

## Do not modernise

See [`CLAUDE.md`](../CLAUDE.md) and [ADR 0002](../docs/governance/adr/0002-deliberate-legacy-strata.md).
Java 8 + Spring Boot 2.7 + Tomcat 8.5 is one immovable block, which is exactly the point.

