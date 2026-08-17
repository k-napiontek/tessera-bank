# The estate map

What runs where, from which decade, and why it still exists.

A real bank is not a technology stack. It is an archaeological site: layers of technology, each
frozen at the moment its budget ran out, all running in production simultaneously, and all required
for a single customer transfer to complete. This document is the excavation.

---

## Stratum 0 - the mainframe core (~1995)

**Directory:** [`mainframe/`](../../mainframe/) | **Built by:** WP-03, WP-04, WP-05

| | |
|---|---|
| Language | COBOL-85, compiled with GnuCOBOL |
| Data | Fixed-width sequential files, COMP-3 packed decimal |
| Job control | JCL, with a shell runner for local execution |
| Processing model | Overnight batch, single sequential pass |

**What it does.** Holds the account master - the definitive record of what every account contains -
and applies each day's movements to it overnight through a balanced-line match-merge.

**Why it still exists.** Because it works, because it has been correct for thirty years, and because
no bank board has ever approved the risk of replacing the thing that knows how much money everyone
has. Roughly 90% of US banking core software is still legacy of this kind. The pool of engineers who
understand it shrinks every year, which raises the cost of both keeping it and leaving it.

**What would break if you replaced it.** Everything downstream assumes the master file's format,
its overnight cut-off, and its arithmetic. The rewrite is not the hard part; proving the rewrite
agrees with thirty years of edge cases is - which is why [`batch/recon`](../../batch/recon/) exists.

---

## Stratum 1 - the Java EE monolith (~2011)

**Directory:** [`legacy/`](../../legacy/) | **Built by:** WP-10, WP-15

| | |
|---|---|
| Language | Java 8 |
| Web | Servlet 3.0, JSP, jQuery |
| Services | JAX-WS SOAP, WSDL-first |
| Build | Maven 3, packaged as a WAR |
| Runtime | Tomcat 8.5 - out of community support |
| Data | Oracle SQL dialect, business logic in stored procedures |

**What it does.** System of record for customers and account metadata, and the internal operations
screens used to work reconciliation breaks and rejects.

**Why it still exists.** It was built when SOAP was the correct enterprise answer, and it has been
extended continuously ever since. 33% of financial services organisations still run Tomcat 8.5.
Spring Boot 2.x was the last line to support Java 8, so Java 8 + Boot 2.7 + Tomcat 8.5 forms a single
immovable block: upgrading any one component forces upgrading all of them, together with the
stored-procedure layer underneath. That is a programme of work, not a ticket.

**What would break if you replaced it.** The SOAP contract has consumers nobody has fully inventoried
- which is the usual reason these systems survive.

---

## Stratum 2 - the integration tier (~2019)

**Directory:** [`integration/`](../../integration/) | **Built by:** WP-11

| | |
|---|---|
| Language | Java 8 |
| Framework | Spring Boot 2.7.18, Spring Integration |
| Messaging | JMS, and Kafka on the modern side |
| Transformation | XSLT against canonical XSD |

**What it does.** Bridges the eras. Consumes modern Kafka events, transforms them to canonical XML,
calls the 2011 SOAP service, and encodes fixed-width COMP-3 movement records for the mainframe.

**Why it still exists.** It is the layer that made modernisation possible without touching the core.
It is also the layer nobody wants to own, because understanding it requires knowing all four eras at
once.

**What would break if you replaced it.** The strangler fig has no trunk without it.

---

## Stratum 3 - the modern services (~2023)

**Directory:** [`services/`](../../services/) | **Built by:** WP-06 to WP-09

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2, Spring Data JDBC |
| Data | PostgreSQL, Flyway migrations |
| Messaging | Kafka, via a transactional outbox |
| Architecture | Hexagonal, enforced by ArchUnit |

**What it does.** The new double-entry ledger - the strangler fig growing around the mainframe core.
Real-time, idempotent, audited, and reconciled daily against the old core.

**Why Java 17 and not the newest release.** Because that is where real banks are. The modern tier of
a bank is typically two to four years behind current, not on the bleeding edge. Making this current
would be less realistic, not more.

---

## Stratum 4 - the edge (~2025)

**Directory:** [`edge/`](../../edge/) | **Built by:** WP-12, WP-13, WP-14

| | |
|---|---|
| `api-gateway` | Go - authentication, rate limiting, correlation |
| `fraud-scoring` | Python 3.12 - asynchronous risk scoring off Kafka |
| `web-banking` | TypeScript + React - the customer application |

**What it does.** Everything customer-facing and everything newest. Written in whatever each team
chose, which is why three languages appear in one tier.

---

## Cross-cutting

**[`batch/`](../../batch/)** - reconciliation between the COBOL master and the PostgreSQL ledger, and
regulatory reporting. Built by WP-16 and WP-17.

**[`contracts/`](../../contracts/)** - one folder per era: copybooks, WSDL and XSD, OpenAPI, AsyncAPI.
The same business concepts expressed four ways, differing by era rather than by modelling. Built by
WP-02.

---

## Why this is a good DevOps fixture

Seven build systems across four eras, each with its own packaging model, dependency manager, test
runner and CVE profile:

| Toolchain | Tier | Produces |
|---|---|---|
| GnuCOBOL + Make | `mainframe/` | executables and data files |
| Maven 3 + JDK 8 | `legacy/` | a WAR for a servlet container |
| Maven/Gradle + JDK 8 | `integration/` | an executable JAR |
| Gradle + JDK 17 | `services/` | an executable JAR |
| Go modules | `edge/api-gateway` | a static binary |
| uv + pyproject | `edge/fraud-scoring`, `batch/reporting` | a Python application |
| npm + Vite | `edge/web-banking` | static assets |

This is precisely the mess a platform engineer inherits, and it is far more instructive than one
uniform modern stack.

## The rule

**Do not modernise strata 0, 1 or 2.** See [`../../CLAUDE.md`](../../CLAUDE.md) and
[ADR 0002](../governance/adr/0002-deliberate-legacy-strata.md). The accepted risks are registered in
[`../technical-debt.md`](../technical-debt.md).
