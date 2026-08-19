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

Each task is roughly one commit. Nine commits is within the 3-10 guideline in `PROTOCOL.md`. The
persistence substitute is settled by [ADR 0010](../../governance/adr/0010-oracle-substitute-for-stratum-1.md):
real Oracle Free 23ai under Testcontainers, written to the 2011 feature set.

1. **Module skeleton and the JDK 8 lock.** `legacy/customer-master/pom.xml` - `war` packaging,
   source and target `1.8`, Servlet 3.0 API `provided`, JUnit 4.13.2, and `maven-enforcer-plugin`
   with `requireJavaVersion [1.8,1.9)` so the build refuses any other JDK instead of quietly emitting
   newer bytecode. Test: `BytecodeVersionTest` reads the major version out of a compiled class under
   `target/classes` and asserts **52**. That is the check that actually catches a wrong JDK, and it
   is what ticks the "no Java 9+ construct" box - compiling under 8 makes a newer API unavailable
   rather than merely discouraged.
2. **WSDL-first generation.** Wire `jaxws-maven-plugin` `wsimport` to
   [`contracts/wsdl/customer-master-v1.wsdl`](../../../contracts/wsdl/customer-master-v1.wsdl) and
   the canonical XSD it imports, generating into `target/generated-sources/wsimport`. Nothing under
   `src/main/java` may duplicate a generated type. Test: `GeneratedPortTypeTest` reflects over
   `CustomerMasterPortType` and asserts the three operations - `getAccount`,
   `getAccountsByCustomer`, `notifyTransferPosted` - and the `ServiceFault` fault class, with the
   signatures the WSDL declares.
3. **Oracle fixture and the versioned script runner.** A JUnit 4 `@ClassRule` over
   `gvenzl/oracle-free:23-slim-faststart`, plus a runner that applies `src/main/sql/*.sql` in name
   order - which is how the Definition of Done's "applied by script" is satisfied, in tests and by an
   operator alike. Test: the runner applies a two-script fixture in order, and **fails loudly on a
   bad script rather than skipping it** - a silently skipped migration is the failure this task
   exists to prevent.
4. **Schema, version 1.** `src/main/sql/V001__schema.sql` - `CUSTOMER`, `ACCOUNT` and
   `TRANSFER_APPLIED`. Sequences driving a `BEFORE INSERT` trigger, never identity columns; `NUMBER`
   and `VARCHAR2` throughout, per ADR 0010. `ACCOUNT` mirrors `tb:Account` field for field.
   `TRANSFER_APPLIED.transfer_ref` carries a unique constraint - that constraint *is* the idempotency
   of `NotifyTransferPosted`, not a check in Java in front of it. Test: `SchemaTest` asserts the
   constraint set, and that a second insert of the same `transfer_ref` raises `ORA-00001`.
5. **The stored procedures.** `src/main/sql/V002__package.sql` - `CUSTOMER_MASTER_PKG` as
   `PACKAGE` and `PACKAGE BODY`, with `GET_ACCOUNT`, `GET_ACCOUNTS_BY_CUSTOMER` and `APPLY_TRANSFER`.
   `APPLY_TRANSFER` posts both movements and returns whether the transfer had already been applied,
   which is the `alreadyApplied` flag the WSDL documents as an expected outcome of at-least-once
   delivery. Business logic lives here, where a 2011 team put it. Test: `ApplyTransferTest` calls the
   procedure twice with the same `transferRef` and asserts the second call reports `alreadyApplied`
   and writes no second movement.
6. **JDBC layer and synthetic seed data.** A DAO calling the package through `CallableStatement` -
   no ORM, no query built in Java that the package should own. Seed data comes from a **synthetic
   generator**, in the manner of `mainframe/data/generate.py`; never from anything resembling a real
   customer. Test: the DAO maps every `tb:Account` field back out of the database unchanged,
   including a `lastMovementDate` that is absent.
7. **The endpoint and the WAR.** The JAX-WS implementation of the generated port type, `web.xml` and
   `sun-jaxws.xml`, assembled into a WAR. Business failures surface as `ServiceFault`, and - per the
   WSDL's own annotation - **a fault message never carries identity**, only a code, a message and a
   correlation id. Test: `Endpoint.publish` serves the implementation in-process and a `wsimport`
   client calls all three operations against it, including the fault path.
8. **Contract conformance (WP-02).** Marshal each SOAP response and validate it against
   [`contracts/xsd/canonical-v1.xsd`](../../../contracts/xsd/canonical-v1.xsd), asserted in the suite
   rather than checked by hand. Cover the empty `GetAccountsByCustomer` result, which `minOccurs="0"`
   permits and a naive implementation returns as a null element.
9. **Documentation.** Fill `docs/compliance/gdpr-data-map.md`, whose stub banner names this package as
   its owner. Name the Oracle substitution in `docs/consuming-this-repo.md` - the compensating control
   [TD-005](../../technical-debt.md) promised and has not delivered. Update
   `legacy/customer-master/README.md` and the traceability matrix rows for REQ-CM-001, REQ-CM-002 and
   REQ-EST-001.

### Two constraints the tasks above assume

**Identity never crosses the wire.** `tb:Account` carries `customerRef` and no name, so no operation
in this WSDL can return identity. Task 6 must not widen it, and task 9 records why the boundary is
where it is.

**"Holds personal data" means the columns, not the contents.** `legacy/customer-master/README.md`
calls this "the only component in the estate that holds personal data", while `CLAUDE.md` forbids
personal data anywhere and `mainframe/data/generate.py` states its records carry "no names, no
addresses and no identifiers of any kind". Both hold: `CUSTOMER` carries the identity *columns* a
2011 bank would have, so the erasure and crypto-shredding design in the GDPR data map is about
something real, and every value in them is synthetic and generated.

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
