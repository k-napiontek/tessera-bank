# customer-master

**Stratum 1** | **Java 8, WAR on Tomcat 8.5** | **Built by WP-10**

System of record for customers and account metadata, exposed over SOAP. The only component in the estate that holds personal data - deliberately, so that everything else is out of scope for GDPR erasure. See the [GDPR data map](../../docs/compliance/gdpr-data-map.md).

Implements [`contracts/wsdl/`](../../contracts/wsdl/). Built with Maven 3 under JDK 8, packaged as a WAR.

```bash
make jdk8          # which JDK 8 the build will use
make test-legacy   # the suite, including PL/SQL against real Oracle - needs Docker
make build-legacy  # target/customer-master.war
```

## What is here

| | |
|---|---|
| Build | Maven 3, inheriting [`platform/parent-pom`](../../platform/parent-pom/), `-Xlint:all -Werror` |
| Language | Java 8, JavaBean accessors, no lambdas where an anonymous class was the idiom |
| Tests | JUnit 4 - the era's tooling |
| Database | Oracle dialect: `VARCHAR2`, `NUMBER`, sequences, `REGEXP_LIKE` constraints |
| Business logic | PL/SQL packages, where a 2011 team put it |
| Data access | Plain JDBC over `CallableStatement`. No ORM, no framework, no pool of its own |

`src/main/resources/db/migration/` holds the versioned scripts, applied in the order
`scripts.list` gives. `V1` is the schema, `V2` is `PKG_ACCOUNT` (reads), `V3` is `PKG_POSTING`
(applying a posted transfer). There is no SQL in the Java at all: the DAO calls a procedure and maps
what comes back, which is exactly why migrating this component off Oracle is hard. That difficulty is
the point - see [TD-005](../../docs/technical-debt.md).

## Running the tests against real Oracle

Oracle is not distributable, so the tests run **Oracle Database 23ai Free** in a container
(`gvenzl/oracle-free:23-slim-faststart`, arm64 and amd64) through Testcontainers. Real Oracle and
real PL/SQL - a compatibility mode runs no PL/SQL at all, so the procedures would have been Java
methods wearing the name, and the tests would have proved the pretence rather than the thing. First
run pulls roughly 2GB; the container starts once per module.

Testcontainers in a 2011 tier is an anachronism and a deliberate one: the pinned stack constrains
what ships, not what runs the tests.

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
contract yet; that is a logged follow-up, not a decision.

## Personal data

The `customer` table holds a family name, a given name, a date of birth and a national identifier.
Every value comes from `SyntheticData`, which lives in **test** scope on purpose - code that
manufactures personal data has no business inside a deployable artefact. Names carry their ordinal
(`TESSERA-0001`) so no bare surname can occur, and identifiers are prefixed `SYN-`, a shape no
authority issues. Neither is a value that merely happens not to match a person; both are values that
cannot.

None of it crosses the wire. `canonical-v1.xsd` gives the estate a `customerRef` and nothing else,
which is what keeps every other component out of scope for an erasure request.
