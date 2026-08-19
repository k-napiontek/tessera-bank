# WP-10 - customer-master

| | |
|---|---|
| **Ticket** | TB-1010 |
| **Branch** | `feat/TB-1010-customer-master-foundation` (10a), `feat/TB-1010-customer-master-soap` (10b) |
| **Stratum** | 1 - Java 8, ~2011 |
| **Depends on** | WP-02 |
| **Status** | `In progress` - see `STATUS.md` for the per-half status |

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

Detailed 2026-08-19. The package lands as **two halves on one ticket**, WP-10a and WP-10b, each its
own branch and pull request, tracked as two rows in `STATUS.md`. Detailed out, this package spans a
build system, a database, a stored-procedure layer, a SOAP endpoint, a WAR and a deployment test;
`STATUS.md` records after WP-09 that the right answer at this size is to split the package in the
plan rather than the pull request. This file stays a single document because six others link to it.

Four decisions were taken with the repository owner before any code, and each changes what gets
built:

- **The Oracle substitute is real Oracle.** `gvenzl/oracle-free:23-slim-faststart` publishes an arm64
  image, so the PL/SQL packages genuinely compile and run under Testcontainers. TD-005 has said
  "a compatible substitute" since WP-01 without naming one; task 9 names it. An H2 Oracle-compatibility
  mode was the alternative and was refused: it cannot run PL/SQL at all, so the stored-procedure layer
  - the realistic half of this stratum - would have been Java aliases pretending to be procedures.
- **The customer table holds identity fields, filled only by a generator.** Family name, given name,
  date of birth and a national identifier, all synthetic. This is what makes the GDPR data map a
  document about something. No such field crosses the SOAP contract: `canonical-v1.xsd` gives the
  wire a `customerRef` and nothing else.
- **The WAR is really deployed to Tomcat 8.5.** Cargo fetches the container into `target/` at test
  time and the integration test calls the endpoint over HTTP. Nothing container-shaped is committed,
  so ADR 0001 holds, and the Definition of Done's deployment box can be ticked honestly rather than
  inferred from a `mvn package` that succeeded.
- **customer-master keeps its own balances.** Forced by the contract - `tb:Account` makes
  `bookedBalance` and `availableBalance` mandatory and `GetAccount` returns a `tb:Account` - and
  stratum 1 cannot call stratum 3 to fetch them. So `NotifyTransferPosted` applies both legs to
  stored balances, which is what the WSDL means by "acknowledged, not double-applied". This is a
  second source of truth for money, deliberately: it is the drift `batch/recon` exists to detect.
  Recorded as **ADR 0010**, not left to be discovered.

`availableBalance` therefore equals `bookedBalance` at this tier, because holds live only in the
ledger and no notification carries one. Stated on the response and in the README rather than emitted
as a number that quietly means something else. Logged as a follow-up.

### WP-10a - build, schema and PL/SQL

Branch `feat/TB-1010-customer-master-foundation`. Nine tasks, roughly one commit each, test-first
throughout.

1. Detail this task list, record the decisions above, split the `STATUS.md` row into 10a and 10b,
   correct the stale "Next actionable package" blockquote, close the JDK 8 half of F-10, and set 10a
   `In progress`.
2. **Corporate parent POM.** `platform/parent-pom/pom.xml`, packaging `pom`: pinned plugin versions
   and dependency management, with the source and target level expressed **per stratum profile**
   rather than forced across the estate - strata 1 and 2 are Java 8 while stratum 3 is 17, and the
   parent's own README says it must express that per module. `maven-enforcer-plugin` fails the build
   on anything but JDK 8, so a developer whose `JAVA_HOME` points at 17 or 26 is told why.
3. **Maven module and the JDK 8 gate.** `legacy/customer-master/pom.xml`, WAR packaging, inheriting
   the parent. JUnit 4 - the era's tooling, not JUnit 5. `ToolchainTest` asserts the JVM running the
   suite reports `1.8`, the same control `services/ledger-core` uses to pin 17, so a toolchain change
   fails the build rather than passing unnoticed. `make jdk8`, `make build-legacy` and
   `make test-legacy` follow the existing `jdk17` probe pattern, with
   `/usr/libexec/java_home -v 1.8` among the candidates because a Zulu JDK is on no Homebrew path.
4. **Oracle schema, versioned.** `customer`, `account` and `applied_transfer`, plus the two
   sequences, as numbered scripts applied by a runner. Oracle dialect throughout: `VARCHAR2`,
   `NUMBER`, `DATE`, named check constraints for the status and type enumerations. **Money is
   `NUMBER(15,0)` minor units** - the same rule as every other tier, in the dialect that would most
   readily have accepted a `FLOAT`. The test applies the scripts to a real Oracle and asserts the
   constraints refuse what the canonical model forbids, so the database enforces the patterns rather
   than trusting the Java above it.
5. **Synthetic identity generator.** The only source of any name, date of birth or national
   identifier in this repository, and the seed script is its output rather than a hand-written file.
   A test asserts every generated reference matches the canonical pattern and that the identifiers
   are drawn from ranges no issuing authority uses.
6. **`PKG_ACCOUNT`.** `get_account` and `get_accounts_by_customer` in PL/SQL. An unknown account
   raises the exception that becomes `ACCT_NOT_FOUND`; an unknown **customer** returns an empty
   list, because the WSDL says in as many words that an empty list is an answer and not a fault. Both
   halves are tested, because the difference between them is the kind of thing a reasonable
   implementer gets wrong in the same direction twice.
7. **`PKG_POSTING.apply_transfer`.** Idempotent on `transfer_ref` through a unique constraint, not
   through a prior `SELECT`: two calls racing must lose one at the database. Applies both legs, sets
   `last_movement_date`, and reports `already_applied`. The duplicate test re-invokes the procedure
   for real - a test that passes a flag asserting duplication would be satisfied by a constant. A leg
   naming an unknown account, and a leg against a `CLOSED` account, both raise.
8. **DAO layer.** Plain JDBC over `CallableStatement`, no ORM, written the way 2011 wrote it. Tests
   against the real container.
9. **Documentation and landing.** Module README, parent-pom README, **TD-005 updated to name the
   Oracle substitute**, ADR 0010, traceability, `STATUS.md`, pull request, merge.

### WP-10b - SOAP endpoint, WAR and deployment

Branch `feat/TB-1010-customer-master-soap`. Eight tasks.

1. Set 10b `In progress` and carry forward whatever 10a learned.
2. **WSDL-first code generation.** `wsimport` over `contracts/wsdl/customer-master-v1.wsdl` into
   `target/generated-sources`; nothing generated is committed, and a test asserts that. The WSDL
   imports the canonical XSD by relative path, so the plugin is pointed at the repository-root
   `contracts/` directory the way `ledger-api` passes `tessera.contracts.dir` to its own tests.
3. **`GetAccount` and `GetAccountsByCustomer`**, implemented against the generated interface. The
   fault path is tested for leakage on an account whose customer has identity on file, because the
   WSDL says an error path is the second most common place personal data escapes a system.
4. **`NotifyTransferPosted`**, tested with an actual redelivery: the balance moves once and the
   second call answers `alreadyApplied`.
5. **XSD conformance.** Every SOAP response marshalled and validated against
   `contracts/xsd/canonical-v1.xsd`, which is the check WP-02 task 8 wired into this package.
   Demonstrated to fail with a required element removed.
6. **WAR and the Tomcat 8.5 deployment test.** Servlet 3.0 descriptor, JAX-WS RI endpoint
   declaration, and Cargo fetching Tomcat 8.5 into `target/` to deploy it. The integration test calls
   the running endpoint with the generated client, and **asserts the WSDL the container publishes
   matches the one that was authored** - a WSDL produced by reflecting over Java classes is not a
   contract, and this is where that drift would first appear.
7. **GDPR data map.** Fill `docs/compliance/gdpr-data-map.md` against its own outline, and state
   plainly which half is described and which is implemented. Crypto-shredding is a feature, not a
   paragraph; if it is not built, it is a follow-up rather than a tick.
8. **Documentation and landing.** Module README, the traceability section for this package, the
   Definition of Done ticked with the naming test as evidence, `STATUS.md`, pull request, merge.

## Definition of Done

- [ ] `mvn package` produces a deployable WAR under JDK 8.
- [ ] The SOAP endpoint matches the WSDL, verified by a generated client.
- [ ] Stored procedures are versioned and applied by script.
- [ ] No Java 9+ construct appears anywhere in the module.
- [ ] Every SOAP response validates against `contracts/xsd/canonical-v1.xsd` in the test suite.
- [ ] Checked against [`../../ways-of-working/definition-of-done.md`](../../ways-of-working/definition-of-done.md).

## Verification

```bash
make jdk8                                       # names the JDK 8 this tier will use
make test-legacy                                # unit tests, and PL/SQL against real Oracle
mvn -f legacy/customer-master/pom.xml verify    # adds the Tomcat 8.5 deployment test (10b)
bash contracts/validate.sh                      # the contracts still agree with the model
make test                                       # every other tier still green
```

`make test-legacy` needs a running Docker daemon: the schema and the PL/SQL are exercised against
Oracle Database 23ai Free through Testcontainers, for the same reason the ledger's persistence tests
use real PostgreSQL. A dialect proved against a substitute that accepts everything is proved against
nothing.

**Contract conformance (WP-02).** Every SOAP response must validate against
[`../../../contracts/xsd/canonical-v1.xsd`](../../../contracts/xsd/canonical-v1.xsd), asserted in
the test suite rather than checked by hand.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-CM-001 Customer and account metadata have a single system of record | `customer-master` |
| REQ-CM-002 The interface is contract-first SOAP | WSDL-first JAX-WS endpoint |
| REQ-EST-001 Stratum 1 is authentically dated in style and stack | Java 8, Servlet, Maven 3 |
