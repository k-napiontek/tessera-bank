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
| [`batch/`](batch/) | - | - | Reconciliation and regulatory reporting |
| [`contracts/`](contracts/) | - | all | Copybooks, WSDL/XSD, OpenAPI, AsyncAPI - one folder per era |

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

Ten of eighteen work packages are `Done`. [`docs/plan/STATUS.md`](docs/plan/STATUS.md) is the
authority; this is the shape of it.

| Stratum | What exists |
|---|---|
| **Contracts** | All four families: COBOL copybooks with a column map, canonical XSD, customer-master WSDL, ledger-core OpenAPI, Kafka AsyncAPI - all derived from [`canonical-data-model.md`](docs/architecture/canonical-data-model.md) and validated by `contracts/validate.sh` |
| **0 - `mainframe/`** | `ACCTPOST.CBL`, the balanced-line match-merge, with six rejection reasons and balancing control totals. A COMP-3 encoder and a deterministic synthetic data generator. |
| **3 - `services/`** | The ledger, end to end: the `ledger-core` domain (`Money`, `Account`, `JournalEntry`, `Balance`, `Hold`, pure Java 17 with no framework on its compile classpath), PostgreSQL persistence with deterministic lock ordering, a REST API with required idempotency and RFC 9457 problems, an append-only hash-chained audit trail, a transactional outbox relayed to Kafka, and metrics, structured JSON logging and health probes. |
| **4 - `edge/`** | `api-gateway` in Go: bearer-token authentication with the algorithm pinned, coarse authorisation by scope, a route table checked against the OpenAPI document in both directions, per-caller rate limiting, correlation ids shared with the ledger, structured JSON logs, Prometheus metrics on a second port, and a proxy whose retry is bounded and only ever replays what is safe to replay. |
| **1, 2** | Nothing yet. `legacy/`, `integration/` and `batch/` hold READMEs only. |

The ledger runs: `./gradlew :services:ledger-api:bootRun` against any PostgreSQL, with the gateway in
front of it (`go -C edge/api-gateway run ./cmd/gateway`), and the overnight COBOL cycle runs with
`make eod`. The two halves still do not join: nothing carries a posting from the ledger to the
mainframe, because the integration and legacy strata hold READMEs only. What exists is the contracts,
the mainframe batch core, the ledger that everything else is built against, and the edge in front
of it.

```bash
make test     # every tier that has something to run
make status   # what is done and what comes next
```

Running the Java tier needs a JDK 17, the mainframe tier needs GnuCOBOL, and the edge tier needs Go. See
[`CLAUDE.md`](CLAUDE.md) for the per-stratum commands.

## Licence and intent

Licensed under the [MIT License](LICENSE).

An educational and portfolio project. Tessera Bank is fictional and all data in this repository is
synthetic. The MIT "AS IS, without warranty" clause matters here rather than being boilerplate: parts
of this repository are pinned to end-of-life dependencies on purpose, so this code is **not** fit for
production use. See [`SECURITY.md`](SECURITY.md) and
[`docs/technical-debt.md`](docs/technical-debt.md).
