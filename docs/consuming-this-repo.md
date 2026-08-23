# Consuming this repository

For the companion platform repositories. Per tier: how it builds, what it needs at runtime, what it
produces, and what shape its deployment takes.

This repository deliberately ships **no deployment artefacts** - no Dockerfile, no Compose file, no
Kubernetes manifest, no Helm chart, no Terraform, no pipeline
([ADR 0001](governance/adr/0001-source-only-repository.md)). That is not an omission to be corrected
by whoever reads this; it is the split a real institution operates, where a central platform team
owns the deployment templates and application teams own their source. **This document is the
handover.**

It states the contract each tier offers a platform: the command that builds it, the artefact that
falls out, what must be configured, what it needs to be running before it starts, and what it exposes
once it is. Where a component's own README already documents its configuration surface in detail,
this document links to it rather than restating it - a surface written twice is a surface that will
disagree with itself.

---

## Build, per tier

`make build` runs every one of these; `make help` lists them individually. Nothing here is a monorepo
build system: each tier builds with its own native toolchain, exactly as a polyglot organisation
works.

| Tier | Build | Toolchain | Artefact |
|---|---|---|---|
| Contracts | `bash contracts/validate.sh` | `xmllint`, Python 3, Node | None - the source of truth for every interface ([`contracts/`](../contracts/README.md)) |
| 0 [`mainframe/`](../mainframe/README.md) | `make build-mainframe` | GnuCOBOL 3.2 (`cobc -x -std=ibm`) | Two executables, `acctpost` and `eodrept`, plus the cycle in [`mainframe/jcl/`](../mainframe/jcl/README.md) |
| 1 [`legacy/`](../legacy/README.md) | `make build-legacy` | **JDK 8** and Maven 3 | `customer-master.war` and `backoffice.war`, for **Tomcat 8.5** |
| 2 [`integration/`](../integration/README.md) | `make build-integration` | **JDK 8** and Maven 3 | One executable Spring Boot 2.7.18 jar, `esb-adapter` |
| 3 [`services/`](../services/README.md) | `make build-services` | **JDK 17** and Gradle | Executable Spring Boot 3.2 jars: `ledger-api`, and `ledger-loader` as a fixture rather than a component |
| 4 [`edge/`](../edge/README.md) | `make build-edge` | **Go 1.25** (both modules declare it; an older toolchain fetches it unless `GOTOOLCHAIN` is pinned), Python 3.12 with `uv`, Node | A static `api-gateway` binary, a `fraud-scoring` package, a `web-banking` bundle of static assets |
| [`batch/`](../batch/README.md) | `make build-batch` | Python 3.12 with `uv` | Two entry points, `reporting` and `recon`, run by a scheduler |
| [`workload/`](../workload/README.md) | `make build-workload` | Go | Test fixtures. **Not components of the bank** and not deployed |

**The two JDKs are not interchangeable and the build enforces it.** Strata 1 and 2 are pinned to Java
8 by the Maven enforcer, and the parent POM refuses any other JDK; stratum 3 is pinned to Java 17.
`make jdk8` and `make jdk17` each report which one was found, or how to install it.

## What must be running before what

```
PostgreSQL ──> ledger-api ──> api-gateway ──> web-banking
                    │
                    ├──> Kafka ──> fraud-scoring
                    │        └──> esb-adapter ──> customer-master (Tomcat 8.5) ──> Oracle
                    │                     └──> MOVEMENT.DAT ──> the overnight cycle ──> recon
                    └──> reporting
```

- **`ledger-api` needs PostgreSQL** and applies its own Flyway migrations at boot. Nothing else may
  apply them.
- **`api-gateway` needs `ledger-api`'s base URL** and a PEM file of public keys; it refuses to boot if
  a private key is in it.
- **`esb-adapter` needs Kafka, `customer-master` and a filesystem path**, and the third is the one a
  deployment gets wrong: it appends fixed-width COMP-3 records to `MOVEMENT.DAT`, which the batch
  scheduler must mount as the same volume the overnight cycle reads. It also **reads the contracts
  directory at runtime** - the WSDL imports the canonical schema by a relative path, so the directory
  has to travel with the jar.
- **`customer-master` needs Oracle and a JNDI `DataSource`** bound by the container, not by the WAR.
- **The overnight cycle needs the movement file to have arrived**, and refuses to apply the same file
  twice - the guard is a SHA-256 recorded in `MOVEMENT.APPLIED`.
- **`recon` needs both cores**: the account master the cycle wrote and the PostgreSQL ledger.

## Configuration surface

Every component takes its configuration from the environment, and every one of them documents its own
variables. **This repository supplies development defaults only**; real values are the platform's.

| Component | Required at minimum | Documented in |
|---|---|---|
| `ledger-api` | The PostgreSQL URL and credentials | [`services/ledger-api/README.md`](../services/ledger-api/README.md) |
| `api-gateway` | `TB_GATEWAY_LEDGER_URL`, the JWT issuer, audience and key file | [`edge/api-gateway/README.md`](../edge/api-gateway/README.md) |
| `fraud-scoring` | `TB_FRAUD_BROKERS` | [`edge/fraud-scoring/README.md`](../edge/fraud-scoring/README.md) |
| `web-banking` | `VITE_GATEWAY_URL`, **at build time** - it is baked into the bundle, so a rebuild is a configuration change | [`edge/web-banking/README.md`](../edge/web-banking/README.md) |
| `esb-adapter` | Kafka bootstrap servers, the customer-master endpoint, the movement-file path, the contracts directory | [`integration/esb-adapter/README.md`](../integration/esb-adapter/README.md) |
| `customer-master` | A JNDI `DataSource` for Oracle, bound by the container against the WAR's `resource-ref` | [`legacy/customer-master/README.md`](../legacy/customer-master/README.md) |
| `backoffice` | The same JNDI `DataSource`, and two `context-param` directories in `web.xml` - where the break reports and the rejects land | [`legacy/backoffice/README.md`](../legacy/backoffice/README.md) |
| `reporting`, `recon` | The ledger's DSN in the environment; `recon` also the master, movement, output and metrics paths as arguments | [`batch/README.md`](../batch/README.md) |
| The overnight cycle | The business date and the input paths | [`mainframe/jcl/README.md`](../mainframe/jcl/README.md) |

**No component reads a secret from a file in this repository, and none may.** Credentials are the
platform's to supply and are never committed, including obviously fake ones - see
[`ways-of-working/data-classification.md`](ways-of-working/data-classification.md).

## Health, readiness and metrics

| Component | Liveness | Readiness | Metrics |
|---|---|---|---|
| `ledger-api` | `/actuator/health/liveness` | `/actuator/health/readiness` | `/actuator/prometheus` |
| `api-gateway` | On the admin port, `:9090` by default | Same port | Same port, and never the customer-facing one |
| `fraud-scoring` | - | - | A scrape endpoint on `TB_FRAUD_METRICS_PORT`, `9100` by default |
| `esb-adapter` | **None** | **None** | **None** |
| `customer-master` | **None** | **None** | **None** |
| `backoffice` | **None** | **None** | **None** |

**The three blanks are honest, not pending.** Stratum 1 and stratum 2 expose nothing: no actuator, no
Micrometer, not even a web starter in the adapter's case. Follow-ups F-100 and F-108 record it, and
adding one would mean modernising a component whose vintage is the point. A platform deploying these
two tiers must watch them **from outside** - the broker's consumer-group lag, the movement file's
length, the container's own connector metrics - which is exactly what the load fixtures under
`workload/` had to do to measure them at all.

## What a scheduler must own

Two things run on a clock rather than on a request, and neither belongs to an application process:

| Job | When | What it is |
|---|---|---|
| The end-of-day cycle | The batch window: minute 1 230 of the business date to 300 of the next, per [`tessera-day-v1.json`](../contracts/workload/tessera-day-v1.json) | Sort the movements, apply them to the account master, write the new master, the rejects and the EOD report. Runbook: [`runbooks/eod-cycle.md`](runbooks/eod-cycle.md) |
| The morning reconciliation | After the cycle, before the business day | Compare the COBOL master against the PostgreSQL ledger and write the break report the back office reads. Runbook: [`runbooks/reconciliation-break.md`](runbooks/reconciliation-break.md) |

**The cycle is generational and rerunnable.** It writes a new master rather than updating the current
one, so a failed cycle has applied nothing and the previous generation is the rollback. Re-running it
over the same inputs produces byte-identical outputs, because the run timestamp comes from the
business date rather than the clock.

## Prerequisites, all of them

Running the whole estate locally needs: **GnuCOBOL**, **JDK 8** *and* **JDK 17**, **Maven 3**,
**Gradle** (via the wrapper), **Go 1.25**, **uv**, **Node**, **PostgreSQL**, **Kafka**, **Oracle**, and
**Tomcat 8.5**. Docker covers the last four for development.

That list is genuinely painful, and it is the point: it is the pain that makes containerisation
valuable, which is the lesson the companion repositories exist to teach.

Two traps this repository has already hit, worth inheriting rather than rediscovering:

- **`openjdk@17` is keg-only on Homebrew**, so Gradle needs `JAVA_HOME=/opt/homebrew/opt/openjdk@17`
  unless 17 is the default JVM. Without it `./gradlew` fails with *Unable to locate a Java Runtime*,
  which reads like a broken build rather than a missing prerequisite.
- **Bare `mvn` resolves whatever JDK Homebrew installed last**, which on a developer machine is
  routinely a JDK far newer than 8. The enforcer then fails the build correctly but a long way from
  the cause, so every Maven recipe in the `Makefile` passes `JAVA_HOME` explicitly.

## The Oracle substitution, and what it means for stratum 1

`legacy/customer-master` is written in **Oracle-dialect SQL with the business logic in PL/SQL
packages**, where a 2011 team put it. Oracle is not distributable, so the tests run against **Oracle
Database 23ai Free** in a container ([ADR 0011](governance/adr/0011-oracle-substitute-for-stratum-1.md));
a compatibility mode was the alternative and runs no PL/SQL at all, which would have tested a
pretence.

For a platform this means two things. The tier is **locked to Oracle** - the dialect and the stored
procedures are the point, and porting them would delete the stratum. And the first test run pulls
roughly 2 GB, which is worth knowing before it happens in a pipeline with a short timeout. The
accepted risk is registered as TD-005 in [`technical-debt.md`](technical-debt.md).

## What this repository will never give you

No Dockerfile, no Compose file, no Kubernetes manifest, no Helm chart, no Terraform, no CI workflow.
If a task appears to require one, it belongs in the platform repository rather than here - that is
[ADR 0001](governance/adr/0001-source-only-repository.md), and it is deliberate.

What it does give you, and what the platform side should consume rather than reimplement: the
contracts in [`contracts/`](../contracts/README.md), the SLO catalogue and recorded baselines behind
[`ways-of-working/slo-catalogue.md`](ways-of-working/slo-catalogue.md), the runbooks in
[`runbooks/`](runbooks/eod-cycle.md), the environment ladder in
[`ways-of-working/environments.md`](ways-of-working/environments.md), and the branch-protection rules
declared in [`ways-of-working/branching-and-review.md`](ways-of-working/branching-and-review.md#branch-protection-to-apply).
