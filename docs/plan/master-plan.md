# Master plan

The orientation document. Read this to understand what Tessera Bank is, why it is shaped this way,
and in what order it gets built.

- **What is done and what is next:** [`STATUS.md`](STATUS.md)
- **How work is executed:** [`PROTOCOL.md`](PROTOCOL.md)
- **The work packages:** [`wp/`](wp/)

---

## 1. Purpose

A realistic banking codebase, built to serve as a DevOps practice environment and a public portfolio
piece.

The problem with practising DevOps on a toy application is that toy applications have no interesting
failure modes. Zero-downtime deployment, schema migration under load, secret rotation, blast-radius
control and dependency remediation only become real problems when the software is non-trivial,
stateful, old, and worked on under regulatory constraint. Banking supplies all four honestly rather
than artificially.

This repository is the **application source**. Companion platform repositories consume it and deploy
it in different ways. One realistic codebase, many deployment experiments.

## 2. The central idea: strata, not a stack

A real bank is not one technology stack. It is an archaeological site - layers of technology, each
frozen at the moment its budget ran out, all running in production at once, and all required for a
single customer transfer to complete.

This is not a stylistic choice. It reflects the industry:

- Roughly **90%** of US banking core software is legacy, typically COBOL or PL/SQL running overnight
  batch on a mainframe.
- **33%** of banking, insurance and financial services organisations still run Tomcat 8.5, which is
  out of community support.
- Spring Boot 2.x was the last line to support Java 8, so those organisations are pinned to
  Java 8 + Spring Boot 2.7 + Tomcat 8.5 as one immovable block. Upgrading any part forces upgrading
  all of it, which is exactly why nobody does.
- Every generation of integration coexists: SOAP beside REST, JMS beside Kafka, Oracle stored
  procedures beside PostgreSQL, ISO 8583 beside ISO 20022, nightly batch beside real-time services.
- Modernisation happens by **strangler fig**, not replacement: new services grow around the old core
  while it keeps running, and the two are reconciled against each other every morning.

| Stratum | Directory | Vintage | Technology |
|---|---|---|---|
| 0 | `mainframe/` | ~1995 | COBOL-85, GnuCOBOL, JCL, fixed-width records, COMP-3 packed decimal |
| 1 | `legacy/` | ~2011 | Java 8, Servlet/JSP, JAX-WS SOAP, Maven 3, WAR on Tomcat 8.5, Oracle dialect |
| 2 | `integration/` | ~2019 | Java 8, Spring Boot 2.7.18, Spring Integration, JMS, XSLT |
| 3 | `services/` | ~2023 | Java 17, Spring Boot 3.2, PostgreSQL, Flyway, Kafka, Gradle |
| 4 | `edge/` | ~2025 | Go, Python 3.12, TypeScript + React |

Stratum 3 is Java 17, not the newest release. Even the modern tier of a real bank runs a few years
behind, and making it current would undo the point.

As a DevOps fixture this is far more valuable than one modern stack: **seven build systems across
four eras**, each with its own packaging model, dependency manager, test runner and CVE profile -
which is precisely the mess a platform engineer inherits.

## 3. The spine: one internal transfer, through every era

Every component exists because this single flow needs it. Nothing in the estate is decorative.

1. **`edge/web-banking`** (React) - the customer submits a transfer.
2. **`edge/api-gateway`** (Go) - authentication, rate limiting, correlation id, routes inward.
3. **`services/ledger-core`** (Java 17, Boot 3.2) - validates, resolves the idempotency key, applies
   double-entry postings to PostgreSQL, appends to the audit chain, writes to a transactional outbox.
   **This is the production-grade component of the repository.**
4. **`edge/fraud-scoring`** (Python) - consumes the event from Kafka, scores it, publishes a decision.
5. **`integration/esb-adapter`** (Java 8, Boot 2.7) - the bridge between eras and the most
   interesting engineering here: consumes the Kafka event, transforms canonical JSON to canonical XML
   by XSLT, calls the monolith over SOAP, and encodes a fixed-width movement record in **COMP-3
   packed decimal** for the mainframe.
6. **`legacy/customer-master`** (Java 8, WAR on Tomcat 8.5) - system of record for customers and
   account metadata, WSDL-first SOAP, Oracle-dialect SQL with stored procedures.
7. **`mainframe/`** (COBOL) - the nightly end-of-day cycle: sort the movements, match-merge them
   against the account master, write the new master, the rejects file and the EOD report.
8. **`batch/recon`** - next morning, compare the COBOL account master against the PostgreSQL ledger
   and report drift. Every real bank runs this; without it, strangler-fig migration is unsafe.
9. **`legacy/backoffice`** (JSP + jQuery) - where an operator reads the recon report and works the
   rejects.
10. **`batch/reporting`** (Python) - regulatory and management reporting off the ledger.

On the estate map but deliberately out of initial scope, so the shape stays honest:
`services/payment-engine` (ISO 20022 outbound payments) and a cards tier (ISO 8583).

## 4. Where the depth goes

Breadth without depth produces a repository that looks hollow to anyone who opens a file. The
resolution: **one flow through every tier, with the ledger built to production standard and the older
tiers real but narrow.**

`services/ledger-core` is the serious code:

- **Money** as minor units plus an ISO 4217 currency, never floating point, with the scale resolved
  per currency so JPY (0 decimals) and BHD (3 decimals) are both correct.
- **Accounts** typed `ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE`. A customer's current account
  is a **liability** of the bank; cash and reserves are **assets**. Getting this right is what
  separates a real ledger from a `balance` column.
- **Journal entries** atomic, balanced and immutable; corrections only ever by reversal entries that
  reference the original. No `UPDATE`, no `DELETE`.
- **Balances** both booked and available, with holds reducing available and later captured or
  released.
- **Concurrency** by pessimistic `SELECT ... FOR UPDATE` in deterministic account-id order, so
  concurrent transfers cannot deadlock - proven by a test running N threads around a ring of accounts
  and asserting total value is conserved.
- **Idempotency** via a required `Idempotency-Key` on every money-moving endpoint, with a
  unique-constrained store of key, request fingerprint and response. Replay returns the original;
  the same key with a different body returns 409.
- **Audit** as an append-only log including a hash of the previous row, making tampering detectable.
- **Outbox**, so the domain event is written in the same transaction as the postings and relayed
  afterwards - the correct answer to dual-write consistency.
- **Tests**: domain units with no Spring context, property-based tests on the balancing invariant,
  Testcontainers integration against real PostgreSQL, the concurrency test, an OpenAPI contract test,
  and ArchUnit tests enforcing the hexagonal boundaries.

## 5. Regulatory realism

Because infrastructure lives elsewhere, "regulated" here means what the **code and the process**
model, not what a pipeline enforces. The framing is EU-first: **DORA** (applicable since January
2025), **GDPR** and **PSD2**.

In the code: double-entry invariants that cannot be violated, an append-only hash-chained audit
trail, idempotent money movement, correct money arithmetic, and explicit data-retention paths.

In the process: four-eyes approval, segregation of duties, traceability from requirement to test,
change records with rollback plans, an environment ladder with named sign-off, and controlled
dependencies. See [`../ways-of-working/`](../ways-of-working/) and [`../compliance/`](../compliance/).

Two documents carry the honesty that makes the rest credible:

- [`../ways-of-working/control-exceptions.md`](../ways-of-working/control-exceptions.md) registers
  controls that are documented but **not enforced** in this repository, with their compensating
  controls.
- [`../technical-debt.md`](../technical-debt.md) records every deliberately outdated component as
  accepted risk with an owner, a compensating control and a review date.

Registering a control gap is what regulated engineering looks like. Claiming a control you do not
enforce is what fails an audit.

## 6. Build order

Twenty-five work packages, one pull request each, executed strictly one at a time. The full table
with current status lives in [`STATUS.md`](STATUS.md); the dependency column enforces the ordering,
so the ledger cannot be built before its domain exists and the ESB adapter cannot be built before
both the ledger and the monolith are done.

Broad shape: foundation and contracts, then the mainframe tier, then the ledger in four slices, then
the legacy and integration tiers where the eras meet, then the edge, then reconciliation, and finally
a deliberate incident exercise that proves the process works.

**A workload strand, WP-20 to WP-25, hangs off the contracts rather than off the ledger.** It builds
what section 1 assumes and the estate does not yet have: demand. A bank's day declared as a versioned
model, a driver that executes it at volume, a production-shaped database to execute it against, an
SLO catalogue with a recorded baseline, and failure injection - because "schema migration under load"
is named above as a reason this repository exists, and nothing here currently puts anything under
load. The strand sits off the critical path and is deliberately not a prerequisite for anything on
it, but the incident exercise is worth considerably more after it than before.

## 7. Boundaries

**In this repository:** application source, contracts, governance configuration, documentation.

**Not in this repository:** Dockerfile, Compose, Kubernetes, Helm, Terraform, CI workflows. Those
belong to the companion platform repositories - see
[ADR 0001](../governance/adr/0001-source-only-repository.md).

Running the estate locally requires GnuCOBOL, JDK 8 *and* JDK 17, Maven, Gradle, Go, uv, Node,
PostgreSQL, Kafka, a JMS broker and Tomcat 8.5. That is genuinely painful - and it is exactly the
pain that makes containerisation valuable, which is the lesson the companion repositories exist to
teach. Prerequisites per tier: [`../consuming-this-repo.md`](../consuming-this-repo.md).
