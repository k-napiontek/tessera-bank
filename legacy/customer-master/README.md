# customer-master

**Stratum 1** | **Java 8, WAR on Tomcat 8.5** | **Built by WP-10**

System of record for customers and account metadata, exposed over SOAP. The only component in the estate that holds personal data - deliberately, so that everything else is out of scope for GDPR erasure. See the [GDPR data map](../../docs/compliance/gdpr-data-map.md).

Implements [`contracts/wsdl/`](../../contracts/wsdl/). Built with Maven 3 under JDK 8, packaged as a WAR.

```bash
make jdk8          # which JDK 8 the build will use
make test-legacy   # the whole suite: PL/SQL on real Oracle, and the WAR on a real Tomcat 8.5
make build-legacy  # target/customer-master.war, without touching a database
```

`make test-legacy` runs `mvn verify`, not `mvn test`, because the deployment test needs a WAR and a
WAR does not exist until `package`. Needs Docker, and network on the first run.

## What is here

| | |
|---|---|
| Build | Maven 3, inheriting [`platform/parent-pom`](../../platform/parent-pom/), `-Xlint:all -Werror` |
| Language | Java 8, JavaBean accessors, no lambdas where an anonymous class was the idiom |
| Tests | JUnit 4 - the era's tooling |
| Database | Oracle dialect: `VARCHAR2`, `NUMBER`, sequences, `REGEXP_LIKE` constraints |
| Business logic | PL/SQL packages, where a 2011 team put it |
| Data access | Plain JDBC over `CallableStatement`. No ORM, no framework, no pool of its own |
| Interface | WSDL-first JAX-WS, SOAP 1.1, document/literal wrapped. See [ADR 0013](../../docs/governance/adr/0013-contract-first-soap-for-the-customer-master.md) |
| Packaging | A WAR with the JAX-WS RI in `WEB-INF/lib`, deployed to Tomcat 8.5 by the test |

`src/main/resources/db/migration/` holds the versioned scripts, applied in the order
`scripts.list` gives. `V1` is the schema, `V2` is `PKG_ACCOUNT` (reads), `V3` is `PKG_POSTING`
(applying a posted transfer), `V4` is the operator audit trail's tables and `V5` is `PKG_OPERATOR`
with the trigger that makes the trail append-only. There is no SQL in the Java at all: the DAO calls
a procedure and maps what comes back, which is exactly why migrating this component off Oracle is
hard. That difficulty is the point - see [TD-005](../../docs/technical-debt.md).

**One convention holds across all five scripts: plain DDL and PL/SQL never share a `/`-delimited
chunk.** `SqlScript` is robust either way, but this estate has three other readers of these files
and one of them was not - a trigger declared after a `CREATE TABLE` in the same chunk was split on
its own semicolons and took a different stratum's whole test suite down with `ORA-00900`. Adding a
package or a trigger means adding a script, not appending to one. See **F-61** in `STATUS.md`.

`V4` and `V5` exist because stratum 1 had no audit trail at all until WP-15, and `backoffice` is the
first component here that lets a person change something. `operator_audit` cannot be updated or
deleted - a trigger raises `ORA-20010` - and every row is written by `PKG_OPERATOR` inside the same
transaction as the change it describes.

## Reused by `backoffice`

`maven-jar-plugin` attaches a `classes` artefact alongside the WAR so that
[`legacy/backoffice`](../backoffice/README.md) can depend on the DAO and the domain types rather
than reimplement them. The SOAP endpoint package is excluded from it: the back office has no
business holding a copy of the web service, and a jar that carried it would let a second module
start answering the contract. A second implementation of account lookup is precisely the drift the
estate's reconciliation exists to detect, so building one here to avoid a POM change would have been
the expensive kind of convenience.

## The SOAP endpoint

**Generated from the contract, never the other way round.** `wsimport` runs over
[`contracts/wsdl/customer-master-v1.wsdl`](../../contracts/wsdl/customer-master-v1.wsdl) at
`generate-sources` and produces `CustomerMasterPortType` into `target/generated-sources/wsimport`.
`CustomerMasterEndpoint` implements that interface, so it cannot compile unless it answers exactly
the operations the contract declares. **Nothing generated is committed**, and `GeneratedCodeTest`
fails the build if it ever is.

The WSDL is read from `contracts/` **in place**, because it imports the canonical schema as
`../xsd/canonical-v1.xsd` - a path relative to the WSDL's own location. A copy without its sibling
directory fails generation with an error naming the schema rather than the copy.

Three things about the build are worth knowing before changing it:

- **Generated sources are compiled separately.** The parent POM forces `-Xlint:all -Werror` on every
  module, and `wsimport` emits one unfixable warning: the generated fault class extends `Exception`
  and declares no `serialVersionUID`. Rather than weaken the gate for the code a person wrote, the
  generated tree compiles in its own execution with `[serial]` alone suppressed. `BytecodeVersionTest`
  still holds every resulting class to Java 8 bytecode.
- **`NotifyTransferPosted` has an awkward signature** - `void` with two `Holder` out-parameters -
  because its response wrapper carries two elements, and that is what JAX-WS wrapper style does with
  two. It was left as generated. Adding a binding customisation to make it prettier would be the code
  deciding what the contract says.
- **The WAR carries the authored contract**, copied at build time into `WEB-INF/wsdl/wsdl/` and
  `WEB-INF/wsdl/xsd/`. The doubled `wsdl` in that path is not a typo: the RI publishes documents from
  `WEB-INF/wsdl`, and the contract's own `wsdl/` and `xsd/` shape has to survive inside it so the
  relative import still resolves.

## Deploying it, in a test

`CustomerMasterDeploymentIT` fetches Tomcat 8.5.100 into `target/`, binds a JNDI `DataSource` onto
the Oracle container, deploys `customer-master.war`, and calls all three operations over HTTP with a
client generated from the same contract. `mvn package` succeeding is not the same statement as "the
WAR deploys", and the difference is where a missing listener class, an unbound JNDI name or a
JAX-WS runtime that disagrees with the one in `rt.jar` all live.

Two findings from that test are recorded because both are counter-intuitive:

- Take the contract out of the WAR and the RI does not fall back to generating one - the endpoint
  fails to publish at all.
- The `wsdl` attribute in `sun-jaxws.xml` and `wsdlLocation` on `@WebService` are both redundant
  while exactly one document sits under `WEB-INF/wsdl`; the RI finds it either way. They stay because
  a reader should not have to infer which document is served from a directory listing.

Nothing container-shaped is committed and nothing survives `mvn clean`, so
[ADR 0001](../../docs/governance/adr/0001-source-only-repository.md) holds.

## Running the tests against real Oracle

Oracle is not distributable, so the tests run **Oracle Database 23ai Free** in a container
(`gvenzl/oracle-free:23-slim-faststart`, arm64 and amd64) through Testcontainers. Real Oracle and
real PL/SQL - a compatibility mode runs no PL/SQL at all, so the procedures would have been Java
methods wearing the name, and the tests would have proved the pretence rather than the thing. First
run pulls roughly 2GB; the container starts once per module.

Testcontainers in a 2011 tier is an anachronism and a deliberate one: the pinned stack constrains
what ships, not what runs the tests.

`mvn verify` starts Oracle **twice**, once for surefire and once for failsafe, because they are
separate JVMs and the container is shared per JVM. That is accepted rather than worked around:
switching the container to Testcontainers' reuse mode would leave Oracle running after every build.

## Two things that look like defects and are not

**This component keeps its own balances.** The estate now holds the same money in the COBOL master,
the ledger and here. That is not an oversight - it is the condition `batch/recon` (WP-16) exists to
reconcile, and the contract requires it besides: `tb:Account` makes `bookedBalance` mandatory and
stratum 1 has no way to ask stratum 3 for one. See
[ADR 0010](../../docs/governance/adr/0010-customer-master-holds-its-own-balances.md).

**`availableBalance` equals `bookedBalance`.** A hold lives in the ledger and no notification carries
one, so this component cannot know what is held. The element is mandatory in the schema, so the
choice is between a number it can defend and one it invents. **Do not present this figure to a
customer as spendable** - `web-banking` reads the ledger, which knows about holds.

## Applying a posted transfer

`NotifyTransferPosted` reports a movement the ledger has **already made**, and `PKG_POSTING` mirrors
it. Three properties are worth knowing:

- **Idempotent on `transfer_ref`**, through a primary key rather than through a prior `SELECT`.
  Upstream delivery is at-least-once, so two deliveries race; the loser gets `DUP_VAL_ON_INDEX` and
  applies nothing. A read-then-write version passes every sequential test and applies a customer's
  payment twice under contention, which is why `PkgPostingConcurrencyTest` runs eight deliveries at
  once.
- **Account status is not consulted.** A `CLOSED` or `BLOCKED` account is applied to like any other,
  because refusing a completed posting does not unmake it - it only leaves this master permanently
  wrong and hides the disagreement from reconciliation. A block belongs before a payment.
- **Sign follows the account type, not the word "debit".** A customer current account is a
  `LIABILITY` and falls on a debit; the bank's own cash is an `ASSET` and rises on one. Written as
  minus-for-debit, this package balances perfectly and reports half the estate's money with the
  wrong sign.

## Fault codes

`ACCT_NOT_FOUND` is the only code the WSDL names. `PKG_POSTING` raises three more -
`ACCT_CURRENCY_MISMATCH`, `AMOUNT_NOT_POSITIVE`, `SAME_ACCOUNT` - carried as Oracle error numbers
20001 to 20004 and mapped to codes **by number, never by message text**. They are not in any
contract yet; that is a logged follow-up, not a decision - **F-51**, and
[ADR 0013](../../docs/governance/adr/0013-contract-first-soap-for-the-customer-master.md)'s closing
note scores it against what the 2011 decision promised.

A message that is malformed rather than wrong - a `NotifyTransferPosted` carrying some number of
movements other than two - is refused as a programming error and becomes a SOAP **server** fault, not
a `ServiceFault`. The WSDL says that element is for business faults, and inventing a code for the
other kind would put a word on the wire that no contract declares.

## Personal data

The `customer` table holds a family name, a given name, a date of birth and a national identifier.
Every value comes from `SyntheticData`, which lives in **test** scope on purpose - code that
manufactures personal data has no business inside a deployable artefact. Names carry their ordinal
(`TESSERA-0001`) so no bare surname can occur, and identifiers are prefixed `SYN-`, a shape no
authority issues. Neither is a value that merely happens not to match a person; both are values that
cannot.

None of it crosses the wire. `canonical-v1.xsd` gives the estate a `customerRef` and nothing else,
which is what keeps every other component out of scope for an erasure request.
`CustomerMasterEndpointReadTest` asserts that - on the success path and on the fault path, because an
error path is the second most common place personal data escapes a system.
