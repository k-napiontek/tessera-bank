# Tessera Bank

> A deliberately realistic banking estate - COBOL mainframe through Java 8 monolith to modern
> microservices - built as application source only. No Dockerfile, no CI: packaging and deployment
> live in companion platform repositories. A base for DevOps and platform-engineering work.

A *tessera* is a single tile of a Roman mosaic - many small pieces forming one picture, which is what
a multi-service banking estate is. Roman bankers also used *tesserae nummulariae* as tokens to
certify coin, so the name carries its own small piece of financial history. Tessera Bank is
fictional.

Most "bank" example projects are a single modern service with a `balance` column. Real banks are not
like that, and the difference is the entire point of this repository. A real bank is an
**archaeological site**: layers of technology, each frozen at the moment its budget ran out, all
running in production simultaneously and all required for a single customer transfer to complete.

This repository models that estate, and the regulated way of working that surrounds it.

---

## Read this first: the versions here are old on purpose

Parts of this repository are pinned to **end-of-life** technology. Java 8. Spring Boot 2.7.18.
Tomcat 8.5. COBOL. SOAP. That is not neglect, and it is not an oversight waiting to be fixed.

It is a reproduction of what the industry actually runs:

- Roughly **90%** of US banking core software is legacy, typically COBOL or PL/SQL executing
  overnight batch cycles on a mainframe.
- **33%** of banking, insurance and financial services organisations still run Tomcat 8.5, which is
  out of community support.
- Spring Boot 2.x was the last line to support Java 8, so those organisations are pinned to Java 8 +
  Spring Boot 2.7 + Tomcat 8.5 as a single immovable block. Upgrading one component forces upgrading
  all of them, which is precisely why nobody does.

The deliberate technical debt is recorded the way a bank records it - as **accepted risk with a named
owner, a compensating control and a review date** - in [`docs/technical-debt.md`](docs/technical-debt.md)
and [ADR 0002](docs/governance/adr/0002-deliberate-legacy-strata.md).

**Do not "helpfully" upgrade the legacy strata.** See [`CLAUDE.md`](CLAUDE.md).

---

## The estate

Directories are organised by era, so the strata are visible in the tree itself.

| Directory | Stratum | Vintage | Technology |
|---|---|---|---|
| [`mainframe/`](mainframe/) | 0 | ~1995 | COBOL-85, GnuCOBOL, JCL, fixed-width files, packed decimal |
| [`legacy/`](legacy/) | 1 | ~2011 | Java 8, Servlet/JSP, JAX-WS SOAP, WAR on Tomcat 8.5, Oracle dialect |
| [`integration/`](integration/) | 2 | ~2019 | Java 8, Spring Boot 2.7.18, JMS, XSLT |
| [`services/`](services/) | 3 | ~2023 | Java 17, Spring Boot 3.2, PostgreSQL, Kafka |
| [`edge/`](edge/) | 4 | ~2025 | Go, Python 3.12, TypeScript + React |
| [`batch/`](batch/) | - | ~2025 | Python 3.12 - regulatory reporting; reconciliation spans strata 0 and 3 |
| [`workload/`](workload/) | 4 | ~2025 | Go 1.25, standard library only - a load-model fixture, not a component of the bank |
| [`contracts/`](contracts/) | - | all | Copybooks, WSDL/XSD, OpenAPI, AsyncAPI, and the outbound and workload formats - one folder per era |

Stratum 3 is Java 17, not the newest release. Even the modern tier of a real bank runs a few years
behind; making it current would undo the point.

Full detail: [`docs/architecture/estate-map.md`](docs/architecture/estate-map.md).

## The spine: one transfer, through every era

Every component exists because this single flow needs it. Nothing here is decorative.

```
  web-banking (React)
        |
  api-gateway (Go)                      authn, rate limiting, correlation id
        |
  ledger-core (Java 17, Boot 3.2)       double-entry postings -> PostgreSQL
        |                               audit chain + transactional outbox
        +---------> Kafka --------------+
        |                               |
        |                        fraud-scoring (Python)
        v
  esb-adapter (Java 8, Boot 2.7)        JSON -> XSLT -> canonical XML
        |                               and COMP-3 fixed-width encoding
        +--- SOAP ---> customer-master (Java 8, WAR on Tomcat 8.5)
        |
        +--- fixed-width movement file --->  mainframe (COBOL)
                                                  |
                                             EOD batch cycle
                                             sort -> match-merge -> report
                                                  |
                                       recon: COBOL master vs. ledger
                                                  |
                                       backoffice (JSP + jQuery)
```

## How work is done here

This repository is worked on the way a regulated institution works: change requests with ticket IDs,
one work package at a time, its own branch, properly-sized commits, a pull request carrying a
completed change record, and a documented Definition of Done.

- **Where the work is planned:** [`docs/plan/`](docs/plan/)
- **What is done and what is next:** [`docs/plan/STATUS.md`](docs/plan/STATUS.md)
- **How an AI agent must work here:** [`docs/plan/PROTOCOL.md`](docs/plan/PROTOCOL.md)
- **Ways of working:** [`docs/ways-of-working/`](docs/ways-of-working/)
- **Regulatory mapping (DORA, GDPR, PSD2):** [`docs/compliance/`](docs/compliance/)

Where a control is *not* enforced in this repository, it is registered rather than claimed - see
[`docs/ways-of-working/control-exceptions.md`](docs/ways-of-working/control-exceptions.md).
Claiming a control you do not enforce is what fails an audit.

## Why there is no Dockerfile

This repository is **application source only**. There is no Dockerfile, no Compose file, no
Kubernetes manifest, no Terraform, and no CI workflow. That is a deliberate architectural boundary,
recorded in [ADR 0001](docs/governance/adr/0001-source-only-repository.md).

Packaging and deployment live in separate companion platform repositories, which consume this one and
deploy it in different ways. One realistic codebase, many deployment experiments - which is the whole
reason this estate exists.

Governance configuration *does* live here (`CODEOWNERS`, PR templates, quality-gate configuration),
because in a real bank a central platform team owns the pipeline templates while application teams
declare their own standards. That split is mirrored exactly.

Runtime prerequisites per tier are documented in
[`docs/consuming-this-repo.md`](docs/consuming-this-repo.md).

## Status

**All thirty-three of the plan's rows are `Done`** - WP-10, WP-11, WP-18, WP-24 and WP-25 each count
more than once, all being split in the plan. Every stratum is built, one transfer crosses all four
eras, the two cores are reconciled every morning, the estate has been driven at volume and broken on
purpose, and the incident process has been used rather than only written.
[`docs/plan/STATUS.md`](docs/plan/STATUS.md) is the authority; this is the shape of it.

| Stratum | What exists |
|---|---|
| **Contracts** | The four era families - COBOL copybooks with a column map, canonical XSD, customer-master WSDL, ledger-core OpenAPI, Kafka AsyncAPI, all derived from [`canonical-data-model.md`](docs/architecture/canonical-data-model.md) - plus four that cross the eras rather than joining two of them: the outbound regulatory extract, the reconciliation break report, the workload model and the SLO catalogue. Every one validated by `contracts/validate.sh` |
| **0 - `mainframe/`** | The tier, complete: `ACCTPOST.CBL`, the balanced-line match-merge, with six rejection reasons and balancing control totals; `EODREPT.CBL` and the four-step overnight cycle, runnable locally with `make eod` and **byte-identical on a rerun**, because the run is dated by the business date rather than by the clock. A COMP-3 encoder, a deterministic synthetic data generator, and copybooks that are contracts four packages depend on. |
| **3 - `services/`** | The ledger, end to end: the `ledger-core` domain (`Money`, `Account`, `JournalEntry`, `Balance`, `Hold`, pure Java 17 with no framework on its compile classpath), PostgreSQL persistence with deterministic lock ordering, a REST API with required idempotency and RFC 9457 problems, an append-only hash-chained audit trail, a transactional outbox relayed to Kafka, and metrics, structured JSON logging and health probes. Beside them, and **not part of the bank**: `ledger-loader`, a bulk loader that stands a production-shaped ledger up from the workload model with `COPY` - writing the audit chain the reports bound their figures by, refusing the transfers the ledger itself would have refused, and then checked by `BalanceReconciliation` and `AuditChain.verify()` rather than by its own arithmetic. |
| **4 - `edge/`** | `api-gateway` in Go: bearer-token authentication with the algorithm pinned, coarse authorisation by scope, a route table checked against the OpenAPI document in both directions, per-caller rate limiting, correlation ids shared with the ledger, structured JSON logs, Prometheus metrics on a second port, and a proxy whose retry is bounded and only ever replays what is safe to replay. `fraud-scoring` in Python: consumes the ledger's transfer events, scores them against an explainable rule set whose every rule is a pure function of one event, and publishes a decision - reproducible from a recorded version that covers the thresholds as well as the code. `web-banking` in TypeScript and React: the customer application, mobile first, showing booked and available as two figures with the held share drawn between them, paging a statement whose every page is checked to foot, and making a transfer under one idempotency key per attempt that every retry reuses. Money is a `bigint` read from the source text of the JSON number. Its colours are held to WCAG by a test that parses the token sheet, and it adds no dependency to do any of it. |
| **`workload/`** | The bank day as a versioned contract, the deterministic engine that turns it into a schedule of intended send times, **and the driver that executes it**. An **open** arrival process - a non-homogeneous Poisson process over the model's own diurnal, weekday, payday and month-end curve - because a closed model throttles itself exactly when the system slows and the latency comes out flattering ([ADR 0016](docs/governance/adr/0016-the-workload-model-is-open.md)). Four cohorts over 1.2 million synthetic customers, references drawn to the ledger's own patterns read from its OpenAPI document, and a run manifest that records both dials so no figure can be read as a throughput this estate produced. The driver behaves like a customer application rather than a load tool: one idempotency key per event reused by every retry, a 429 counted as a refusal and never retried immediately, a 5xx as an unknown outcome rather than a failure, latency measured from the **intended** send time, and its own totals reconciled against the ledger's `ledger_transfers_total` at the end of every run. `scripts/estate-up.sh` boots PostgreSQL, Kafka, the ledger, the fraud scorer and the gateway and drives a compressed day at them, and can **degrade the estate while it runs**: seven conditions declared as a contract in `contracts/workload/`, each naming the objectives it should move and - the load-bearing half - the objectives that must stay flat. WP-25 will drive the same schedule at the older strata. |
| **`batch/`** | `recon` in Python, the control that makes strangler-fig migration survivable: every morning it compares the COBOL account master against the PostgreSQL ledger, distinguishes a **timing difference from genuine drift** ([ADR 0015](docs/governance/adr/0015-the-cut-off-is-the-movement-file.md)) and never corrects a break it finds. Driven at a day's volume it compared 80 001 accounts and matched 80 001, at no tolerance. Beside it `reporting`: daily position, movement summary and a fixed-width regulatory extract, generated from the ledger and **reproducible** - a rerun for a past date at the recorded position produces byte-identical output, because a report is cut at an audit sequence rather than at a timestamp. Control totals reconcile to the ledger independently. |
| **1 - `legacy/`** | Two WARs on a real Tomcat 8.5, and `backoffice` is the second: JSP and jQuery, the screens where an operator reads the morning's breaks and works the overnight rejects, declaring what it needs in `web.xml` the way a 2011 application did so one artefact deploys everywhere unchanged. A timing difference offers no action, and the refusal is enforced in the PL/SQL rather than in the page. First, `customer-master`, the system of record, complete: a Java 8 Maven module whose build refuses any other JDK, an Oracle-dialect schema, and the business logic in PL/SQL packages where a 2011 team put it. Tested against **real Oracle 23ai Free** in a container, because a compatibility mode runs no PL/SQL and would have tested a pretence. On top of it, a **WSDL-first JAX-WS endpoint** - SOAP 1.1, document/literal wrapped, generated from the authored contract and never the reverse, with every response validated against the canonical XSD. Packaged as a WAR and **really deployed to a real Tomcat 8.5** by its own test, which calls all three operations over HTTP with a generated client and asserts that the contract the container publishes is the one that was authored. |
| **2 - `integration/`** | `esb-adapter`, complete: a Spring Boot 2.7.18 module on Java 8 - the last Boot line that supports it, which is the point - consuming the ledger's Kafka event, transforming it to canonical XML by an **XSLT file** whose output is validated against `canonical-v1.xsd` before it moves, refusing any currency the mainframe's packed decimal cannot represent, and calling `customer-master` over SOAP with a client generated from the same WSDL that component generated its server from. A message that cannot be carried goes to a **dead-letter channel this component owns a contract for**; one that merely could not be delivered is not acknowledged at all, so the broker redelivers and the partition waits. It then encodes the movement into **COMP-3 packed-decimal records** and appends them to the file tonight's COBOL cycle reads - and nothing is written there unless the SOAP call succeeded, enforced by statement order rather than by comment. The end-to-end test brings up a real Kafka, a real Oracle and a real Tomcat 8.5 with customer-master's own WAR on it, and redelivers the event to prove the file is byte-identical. |
| **Workload** | Built, and used. The bank day as a versioned contract, a driver that executes it at volume against every stratum, a production-shaped ledger to execute it against, an SLO catalogue with a recorded normal under `workload/baselines/`, and failure injection on top: seven declared conditions ([ADR 0017](docs/governance/adr/0017-a-scenario-is-its-own-contract.md)), a schema migration applied while money moved, and a soak. The audit chain's throughput ceiling is a measured figure rather than a hunch - and a second ledger instance does not move it. The whole estate has been driven at once: **24 023 transfers across four eras in one day**, with the backlog forming at the single-threaded 2019 hop and nowhere else. |
| **Governance** | The process, exercised rather than described. A deliberate fault was injected into the live estate across two business dates, detected from the operator's screen, worked through the documented incident procedure and written up as [INC-001](docs/incidents/INC-001-transfers-discarded-at-the-era-boundary.md) - which is honest about the two transfers the estate lost silently and about the fixture defects in the exercise itself. Every requirement resolves to an artefact and a test in the [traceability matrix](docs/compliance/traceability-matrix.md), and `make lint` now fails on a broken internal link, a surviving documentation stub or an invented requirement id. |

The ledger runs: `./gradlew :services:ledger-api:bootRun` against any PostgreSQL, with the gateway in
front of it (`go -C edge/api-gateway run ./cmd/gateway`) and `fraud-scoring` consuming what it
publishes; `reporting` cuts the day's figures off the same database; the overnight COBOL cycle runs
with `make eod`. **One transfer crosses all four eras.** A payment published as a 2023 Kafka event
becomes canonical XML by XSLT, reaches a 2011 SOAP endpoint on a real Tomcat 8.5 over real Oracle, is
encoded into two COMP-3 packed-decimal records, and is applied to the COBOL account master by the real
GnuCOBOL overnight cycle - asserted end to end by `FourEraTransferIT`, which then redelivers the event
and asserts the movement file is byte-identical. `batch/recon` checks the two cores against each other
the next morning, and `legacy/backoffice` is the screen an operator works the breaks from.

```bash
make test     # every tier that has something to run
make status   # what is done and what comes next
```

Stratum 3 needs a JDK 17 and stratum 1 a JDK 8 - `make jdk17` and `make jdk8` each name the one
they found, or how to install it. The mainframe tier needs GnuCOBOL and the edge tier Go and uv.
Docker is needed by the tests that use a real PostgreSQL, a real Kafka or a real Oracle. See
[`CLAUDE.md`](CLAUDE.md) for the per-stratum commands.

## Licence and intent

Licensed under the [MIT License](LICENSE).

An educational and portfolio project. Tessera Bank is fictional and all data in this repository is
synthetic. The MIT "AS IS, without warranty" clause matters here rather than being boilerplate: parts
of this repository are pinned to end-of-life dependencies on purpose, so this code is **not** fit for
production use. See [`SECURITY.md`](SECURITY.md) and
[`docs/technical-debt.md`](docs/technical-debt.md).
