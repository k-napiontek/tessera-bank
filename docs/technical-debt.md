# Technical debt register

Deliberately outdated components, recorded the way a bank records them: **accepted risk with a named
owner, a compensating control and a review date.**

Nothing here is an oversight. Every entry reproduces a condition the banking industry genuinely
operates under, and that reproduction is the purpose of this repository. See
[ADR 0002](governance/adr/0002-deliberate-legacy-strata.md) for the decision and
[`../SECURITY.md`](../SECURITY.md) for what it means for anyone reading this code.

**These are not to be fixed.** See the legacy-strata rule in [`../CLAUDE.md`](../CLAUDE.md).

---

## Accepted risks

### TD-001 - Java 8 in strata 1 and 2

| | |
|---|---|
| **Component** | `legacy/`, `integration/` |
| **Status** | Accepted, deliberate |
| **Owner** | Karol Napiontek |
| **Review** | Annually, or on change of project purpose |

Java 8 reached end of public updates for commercial use in 2019. It remains in extremely wide
banking use because Spring Boot 2.x was the last line to support it, binding Java 8, Spring Boot 2.7
and Tomcat 8.5 into one block that must be upgraded together.

**Why accepted.** Reproducing this constraint is the reason stratum 1 and 2 exist. Removing it would
leave the repository teaching a version-lag problem it no longer demonstrates.

**Compensating controls.** Isolated to strata 1 and 2; deploys nowhere; holds no real data; clearly
signposted in `README.md`, `SECURITY.md` and `CLAUDE.md`.

### TD-002 - Spring Boot 2.7.18 in stratum 2

| | |
|---|---|
| **Component** | `integration/esb-adapter` |
| **Status** | Accepted, deliberate |
| **Owner** | Karol Napiontek |
| **Review** | Annually |

Spring Boot 2.7 reached end of open-source support in November 2023 and accumulates known CVEs
continuously. Moving to 3.x requires Java 17 and substantial code change, which is exactly why
organisations remain on it.

**Why accepted.** As TD-001. This is the single most representative piece of technical debt in
European banking.

**Compensating controls.** As TD-001. SCA findings against this component are expected and are not
defects.

### TD-003 - Tomcat 8.5 as the target runtime for stratum 1

| | |
|---|---|
| **Component** | `legacy/customer-master` |
| **Status** | Accepted, deliberate |
| **Owner** | Karol Napiontek |
| **Review** | Annually |

Out of community support. 33% of banking, insurance and financial services organisations still run
it, because moving requires upgrading the JDK and the frameworks above it simultaneously.

**Why accepted.** As TD-001.

**Compensating controls.** As TD-001. The container itself is never committed here - only the WAR
targeting it.

### TD-004 - SOAP and JAX-WS for the customer-master interface

| | |
|---|---|
| **Component** | `legacy/customer-master`, `contracts/wsdl/` |
| **Status** | Accepted, deliberate |
| **Owner** | Karol Napiontek |
| **Review** | Not scheduled - permanent by design |

**Why accepted.** SOAP is not a vulnerability, it is a generation. Banks run enormous SOAP estates
and integration engineers are expected to work with them. Replacing it with REST would delete a skill
the repository is meant to exercise.

### TD-005 - Oracle SQL dialect without an Oracle database

| | |
|---|---|
| **Component** | `legacy/customer-master` |
| **Status** | Accepted, deliberate |
| **Owner** | Karol Napiontek |
| **Review** | Annually |

Stratum 1 is written in Oracle dialect with business logic in stored procedures, as a 2011 bank would
have. Oracle itself is not distributable, so local execution uses a substitute.

**The substitute, named.** **Oracle Database 23ai Free**, run in a container from
`gvenzl/oracle-free:23-slim-faststart` through Testcontainers. Real Oracle and real PL/SQL, not a
compatibility mode: the packages in `legacy/customer-master/src/main/resources/db/migration/`
genuinely compile and execute, and the tests fail when they do not. `make test-legacy` needs a
running Docker daemon and pulls roughly 2GB on the first run.

This entry said "a compatible substitute" from WP-01 until WP-10a without naming one, and the choice
is not a detail. An H2 in Oracle-compatibility mode was the alternative and runs **no PL/SQL at
all**, so the stored procedures would have had to become Java methods wearing the name - at which
point the two conditions this entry exists to reproduce, the dialect lock-in and the
stored-procedure layer, would both have been reproduced by something else pretending.

**Why accepted.** The dialect lock-in and the stored-procedure logic are the realistic parts, and
they make a later migration exercise genuinely difficult in the way real migrations are.

The choice is recorded in full, alternatives included, in
[ADR 0011](governance/adr/0011-oracle-substitute-for-stratum-1.md) - which also states the weakness
this substitution carries: **23ai accepts syntax 11g would reject**, so the dialect discipline is
held by review and by a stated feature restriction, not by the database.

**Compensating controls.** The substitution is named here, in ADR 0011 and in
[`../legacy/customer-master/README.md`](../legacy/customer-master/README.md).
[`consuming-this-repo.md`](consuming-this-repo.md) is still a stub owned by WP-18 and should repeat
it when it is written.

---

## Not accepted - fix these

Debt that is *not* deliberate goes here, and is fixed rather than registered. Empty is the correct
state.

| # | Component | Issue | Raised | Owner | Target |
|---|---|---|---|---|---|
| - | - | none currently | - | - | - |

---

## Rules for this register

1. An entry here is a **decision**, not an excuse. It needs a reason, an owner, a compensating
   control and a review date.
2. Accidental debt does not belong in the accepted table. It goes in "Not accepted" and gets fixed.
3. A scanner finding against an accepted entry is expected output, not a defect. A finding against
   anything else is a defect.
4. Review dates are real. An expired review is itself a finding.
