# WP-10 - customer-master

| | |
|---|---|
| **Ticket** | TB-1010 |
| **Branch** | `feat/TB-1010-customer-master` |
| **Stratum** | 1 - Java 8, ~2011 |
| **Depends on** | WP-02 |
| **Status** | `Not started` |

## Objective

Build the system of record for customers and account metadata as a bank would have built it in 2011:
a Java 8 web application packaged as a WAR for Tomcat 8.5, exposing a WSDL-first SOAP interface over
Oracle-dialect SQL with stored procedures. Nothing here is modern, and that is the requirement.

## In scope

- Maven 3 build producing a WAR, targeting Java 8 and Servlet 3.0.
- JAX-WS endpoint implementing `contracts/wsdl/`, generated WSDL-first.
- Customer and account metadata persistence in Oracle SQL dialect, with business logic in stored
  procedures where a 2011 team would have put it.
- Schema and stored procedures as versioned SQL scripts.
- Unit and integration tests appropriate to the era's tooling.

## Out of scope

- Any Spring Boot, any Java 9+ language feature, any REST endpoint.
- The back-office UI - WP-15.
- Containerisation or an application server configuration - platform repositories.

## Constraints

- **Java 8 only.** No `var`, no records, no sealed types, no text blocks, no `List.of`. Write the
  Java a competent 2011 engineer would have written, because a reader must be able to date this code
  from its style alone.
- **WSDL-first.** The contract is authored, then the code is generated from it. Never the reverse.
- Oracle dialect deliberately, so the migration friction to PostgreSQL is real and visible when a
  later exercise attempts it.
- Do not modernise. See the legacy-strata rule in `CLAUDE.md`; changing any version here needs an
  explicit instruction and an ADR.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] `mvn package` produces a deployable WAR under JDK 8.
- [ ] The SOAP endpoint matches the WSDL, verified by a generated client.
- [ ] Stored procedures are versioned and applied by script.
- [ ] No Java 9+ construct appears anywhere in the module.

## Verification

Build with JDK 8, deploy to Tomcat 8.5, and call the endpoint with a WSDL-generated client. Confirm
the response validates against the schema. Run a lint check asserting the source level is 8 and no
newer construct is present.

**Contract conformance (WP-02).** Every SOAP response must validate against
[`../../../contracts/xsd/canonical-v1.xsd`](../../../contracts/xsd/canonical-v1.xsd), asserted in
the test suite rather than checked by hand.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-CM-001 Customer and account metadata have a single system of record | `customer-master` |
| REQ-CM-002 The interface is contract-first SOAP | WSDL-first JAX-WS endpoint |
| REQ-EST-001 Stratum 1 is authentically dated in style and stack | Java 8, Servlet, Maven 3 |
