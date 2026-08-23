# Technology radar

The approved technology list, in the familiar adopt / trial / assess / hold form.

**In a bank this is a governance control rather than a blog post.** It constrains what may be
introduced without an architecture review, and it is the artefact that answers *who decided we run
this?* - a question that gets asked after an incident, when the answer "someone added it in a pull
request in 2019" is not good enough.

It is also the first entry in the [DORA control map](../compliance/dora-control-map.md)'s ICT risk
management pillar, because the regulation's expectation is that an institution knows what it runs and
why.

---

## The rings

| Ring | What it means here |
|---|---|
| **Adopt** | The default choice for its job in the stratum it belongs to. No approval needed to use it |
| **Trial** | Permitted, **with an ADR** recording where it is used and what it is being evaluated against |
| **Assess** | Interesting, not permitted yet. Introducing one requires architecture review first |
| **Hold** | Excluded on purpose, with the reason. Introducing one is a **major** change and needs an explicit decision |

## The special case that runs through everything

**Strata 0, 1 and 2 are `hold` by industry standards and `adopt` here, deliberately.** COBOL-85,
Java 8, Tomcat 8.5, JAX-WS SOAP and an Oracle dialect are not accidents of neglect; they are the
reason this repository exists, and roughly 90% of US banking core software is legacy of exactly this
shape. [ADR 0002](adr/0002-deliberate-legacy-strata.md) records the decision and
[`../technical-debt.md`](../technical-debt.md) registers each one as accepted risk with an owner, a
compensating control and a review date.

The practical rule, from [`../../CLAUDE.md`](../../CLAUDE.md): **any version change in strata 0, 1 or
2 requires an explicit instruction from the repository owner and an ADR.** There are no exceptions,
including "while I was in there anyway" and "the scanner flagged it". A radar that let a dependency
bot move those rings would delete the estate this radar describes.

---

## Adopt

Per stratum, because the ring depends on the era. The authority for which directory is which stratum
is [`../architecture/estate-map.md`](../architecture/estate-map.md).

**Stratum 0 - `mainframe/`, ~1995**

| Technology | For |
|---|---|
| COBOL-85 on GnuCOBOL, compiled `-std=ibm` | Batch programs. The IBM dialect, because `COMP-3` is an IBM extension that strict ANSI rejects |
| Fixed-width records and copybooks | Every record layout. The copybooks in [`../../contracts/copybook/`](../../contracts/copybook/README.md) are contracts, not source |
| `COMP-3` packed decimal | Money on the mainframe. Never binary, never text |
| Sequential file organisation and a match-merge | The overnight cycle. Never `LINE SEQUENTIAL` - a packed field can contain `0x0A` |

**Stratum 1 - `legacy/`, ~2011**

| Technology | For |
|---|---|
| Java 8, Maven 3, WAR packaging | The whole stratum. The parent POM refuses any other JDK |
| Servlet 3.0 and JSP with jQuery | The back-office screens |
| JAX-WS, document/literal wrapped SOAP, WSDL-first | The customer-master interface ([ADR 0013](adr/0013-contract-first-soap-for-the-customer-master.md)) |
| Oracle-dialect SQL with PL/SQL packages | Business logic where a 2011 team put it |
| Tomcat 8.5 | The runtime, deployed to by the tests themselves |

**Stratum 2 - `integration/`, ~2019**

| Technology | For |
|---|---|
| Java 8 with Spring Boot 2.7.18 | The last Boot line that supports Java 8, which is the point |
| Spring Kafka with manual acknowledgement | Consuming the ledger's events |
| XSLT as a file | Canonical JSON to canonical XML, validated against the XSD before it moves |

**Stratum 3 - `services/`, ~2023**

| Technology | For |
|---|---|
| Java 17 with Spring Boot 3.2, Gradle | The ledger. Not the newest release - even a bank's modern tier runs a few years behind |
| PostgreSQL with Flyway | Persistence and migrations, applied by the service at boot and by nothing else |
| **Spring Data JDBC and hand-written SQL** | The locking paths. **No JPA, no Hibernate, no lazy loading** - the statements that move money are readable in one place |
| jqwik | Property-based tests over the ledger invariants |
| ArchUnit | Enforcing the hexagonal boundaries as a test rather than as a convention |
| Testcontainers | Integration tests against real PostgreSQL. An in-memory database takes no `SELECT ... FOR UPDATE` row locks |
| Micrometer with the Prometheus registry | Metrics, per the [SLO catalogue](../ways-of-working/slo-catalogue.md) |

**Stratum 4 - `edge/` and `workload/`, ~2025**

| Technology | For |
|---|---|
| Go 1.25, standard library first | The gateway and the workload engine. Two direct dependencies in the gateway, none in `workload/` |
| Python 3.12 with `uv` and `ruff` | The fraud scorer and the batch tier |
| TypeScript, React and Vite | `web-banking`, which adds no runtime dependency beyond them |
| Prometheus client libraries | The scrape endpoints |

## Trial

Permitted where an ADR records the choice. Each of these is used in exactly one place and is being
judged there.

| Technology | Where, and what it is being judged on |
|---|---|
| OpenTelemetry tracing, via the Micrometer bridge | `ledger-api` produces spans and propagates W3C `traceparent`. **It ships nothing** - there is no exporter, deliberately, because a source repository cannot name a backend |
| Oracle Database 23ai Free in a container | The stratum-1 test substitute ([ADR 0011](adr/0011-oracle-substitute-for-stratum-1.md)). Judged on whether the PL/SQL and the dialect behave as the real thing |
| Bulk load by `COPY` | `services/ledger-loader`, a fixture rather than a component of the bank, used to stand a production-shaped database up |

## Assess

Not permitted yet. Introducing one is an architecture-review decision, not a pull request.

| Technology | Why it is only assessed |
|---|---|
| ISO 20022 messaging (`services/payment-engine`) | On the estate map and deliberately out of initial scope, so the shape stays honest |
| ISO 8583 and a cards tier | Same |
| JMS | The plan promised a JMS tier and this estate has never had one - **F-95** records it rather than letting the README imply otherwise |
| Java 21 for stratum 3 | Stratum 3 is a ~2023 tier. Moving it would undo the vintage, and it is not a bug fix |
| Kotlin, GraphQL, a service mesh | Nothing here needs them, and each would add a toolchain to a repository that already carries seven |

## Hold

Excluded on purpose. Each has a reason that is a rule somewhere else in this repository.

| On hold | Why |
|---|---|
| **Floating-point money, anywhere** | Money is minor units plus an ISO 4217 code, with the scale resolved per currency. `double` for an amount is a defect, not a style |
| **JPA or Hibernate on a money path** | Lazy loading and generated SQL hide the statement that takes the lock. The ledger's concurrency guarantee depends on the statement being visible |
| **Any version bump in strata 0-2** | ADR 0002. Needs an explicit instruction and an ADR of its own |
| **AGPL dependencies** | Refused outright by [`../ways-of-working/dependency-policy.md`](../ways-of-working/dependency-policy.md); copyleft generally requires review |
| **Personal data in any form** | [`../ways-of-working/data-classification.md`](../ways-of-working/data-classification.md). Synthetic only, including in fixtures and error messages |
| **Deployment artefacts in this repository** | [ADR 0001](adr/0001-source-only-repository.md). No Dockerfile, no manifest, no pipeline |
| **Auto-correcting a reconciliation break** | A break is investigated, never corrected by the tool that found it. `REQ-REC-003` |
| **A second implementation of a canonical form** | The audit chain's hash and the sign convention exist once. Two implementations agree until the day they do not, and that day is silent |

---

## How an entry moves

1. **Into Trial** - an ADR recording the choice, what it replaces and how it will be judged. The
   Definition of Done's ADR box is the gate.
2. **Trial to Adopt** - used in at least one merged package, with its tests, and nothing outstanding
   against it. Recorded here.
3. **Anything to Hold** - a decision that stands on its own, with the reason written down. Something
   already in use moving to Hold implies a removal plan and an entry in
   [`../technical-debt.md`](../technical-debt.md) until it is gone.

**Who decides.** Architecture review, which in this repository is the repository owner - there is no
review board, and that gap is registered as
[CE-002](../ways-of-working/control-exceptions.md#ce-002---no-independent-test-or-release-function).
The radar is updated in the same change that introduces the technology, never afterwards: a radar
maintained retrospectively is a list of what got in.
