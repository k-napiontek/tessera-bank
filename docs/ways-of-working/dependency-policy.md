# Dependency policy

How third-party dependencies enter this estate, and what is done about the ones that go bad.

**This is DORA ICT third-party risk expressed at the level an engineer actually works at.** The
regulation asks an institution to know what it depends on, on what terms, and what happens when one
of those dependencies fails or is found to be vulnerable. Every rule below is one of those three
questions in a form that can be applied to a pull request.

---

## Sourcing

**The policy a bank operates: every dependency comes from an internal proxy**, never directly from a
public registry. The proxy is where licence scanning, vulnerability scanning and an allow-list are
applied once, for every build, and it is what makes a dependency-confusion attack a non-event.

**This repository does not operate one.** Builds pull from Maven Central, npm, PyPI and the Go module
proxy directly, because running Nexus or Artifactory for a personal project is disproportionate. That
gap is registered as
[CE-003](control-exceptions.md#ce-003---dependencies-are-not-proxied-through-an-internal-repository),
with its compensating controls and its residual risk - exposure to registry compromise, accepted for
a repository that deploys nowhere.

## Licences

| Licence | Position |
|---|---|
| Apache-2.0, MIT, BSD (2- and 3-clause), EPL | **Approved.** No review needed |
| OFL-1.1 | **Approved for font assets only** - see below |
| LGPL, MPL, other weak copyleft | **Review required** before use, recorded in the pull request |
| GPL | **Review required**, and expected to fail for anything linked into a deployed artefact |
| AGPL | **Refused.** No review, no exception |
| No licence, or an unclear one | **Refused.** An unlicensed dependency is not free to use, it is undecided |

**OFL-1.1 applies to font assets only**, added by WP-19 for the typeface `edge/web-banking` serves
from its own origin. The licence is permissive for use and embedding; its one real condition is that
the font may not be sold on its own and that a derivative may not keep the reserved name. Neither
constrains anything this estate does.

The licence check is on **what is distributed**, not on what is convenient: a GPL tool run in a build
is a different question from a GPL library linked into a WAR, and the second is the one that binds
the institution.

## Adding a dependency

Four things, in this order:

1. **A ticket.** The dependency is part of a change, and the change has a `TB-XXXX` id.
2. **A justification recorded in the pull request** - what it does, why the standard library or an
   existing dependency does not, and what it pulls in transitively. "It is popular" is not a
   justification; the transitive set is usually the actual decision.
3. **A licence check** against the table above.
4. **A pinned version and an updated lock file**, per the table below.

The bar rises with the stratum's blast radius. A test-scope dependency in `workload/` is a routine
change; a runtime dependency on a money path is at least **normal** and often **major** under
[`change-management.md`](change-management.md). Adding one to strata 0, 1 or 2 is a version change in
a pinned stack and needs an explicit instruction and an ADR.

**The strongest position is the one taken most often here: no dependency at all.** `workload/` has
none - standard library only. `edge/api-gateway` has two direct ones. `edge/web-banking` adds nothing
beyond React and Vite, and holds its colour tokens to WCAG with a test it wrote rather than a library
it imported. Every dependency not added is a dependency that cannot be exploited, cannot go
unmaintained and cannot need remediating at 03:00.

## Pinning, per toolchain

| Toolchain | How versions are pinned | Lock file |
|---|---|---|
| Maven (strata 1 and 2) | The corporate parent POM pins every plugin and dependency version; the enforcer pins the JDK range itself | None - Maven's model is the pin |
| Gradle (stratum 3) | The Spring Boot BOM plus explicit versions for anything outside it | **None committed** - see the gap below |
| Go | `go.mod` with a `go.sum` of checksums | `go.sum` (`workload/` has none, having no dependencies) |
| Python (`uv`) | `pyproject.toml` with a resolved lock | `uv.lock`, and builds run `uv sync --locked` |
| npm | `package.json` with a resolved tree | `package-lock.json`, and builds run `npm ci` |
| COBOL | There is nothing to pin. That is one of the tier's advantages | - |

**An unpinned plugin is a dependency on whatever the registry held that morning**, which is why the
parent POM pins plugin versions explicitly and says so in a comment. The gap worth naming: **stratum
3 commits no Gradle dependency lock**. Versions resolve from the Boot BOM, which is deterministic
today and is not the same guarantee a lock file gives. Enabling `dependencyLocking` would be the
stronger control and is not operated.

## Vulnerabilities and composition analysis

**Software composition analysis on every build is the policy, and this repository runs none.** There
is no pipeline here to run it in ([ADR 0001](../governance/adr/0001-source-only-repository.md)) and
no scanner configured under [`../../quality/`](../../quality/README.md) - follow-up F-03. Stating it
plainly is the point: a policy section claiming a scan nobody runs is exactly the invented coverage
this repository refuses elsewhere.

What the policy is, for whoever does run it:

| Severity | Action |
|---|---|
| Critical | Remediate before the change merges. No exception |
| High | Remediate, or register the exception in [`../technical-debt.md`](../technical-debt.md) with an owner and a review date |
| Medium, low | Remediate on the next routine update of that dependency |

**Findings against strata 0, 1 and 2 are expected output, not defects.** Java 8, Spring Boot 2.7.18
and Tomcat 8.5 are end-of-life on purpose, they will light up every scanner ever pointed at them, and
the accepted risk is registered as TD-001 to TD-005 in [`../technical-debt.md`](../technical-debt.md)
with the compensating controls. **A scanner finding is not authority to upgrade a pinned stratum** -
that needs an explicit instruction from the repository owner and an ADR, per
[`../../CLAUDE.md`](../../CLAUDE.md) and [ADR 0002](../governance/adr/0002-deliberate-legacy-strata.md).
The realistic remediation for a legacy stratum is usually compensating rather than corrective:
isolate it, bound what it can reach, and watch it.

## The vendor component register

DORA's third-party risk pillar wants a register of what the institution depends on. At the level this
repository can honestly answer, the register is the **runtime** dependencies - the ones an outage or
a compromise at the vendor would be felt through:

| Component | Used by | Position |
|---|---|---|
| Oracle Database | `legacy/customer-master` | **Not substitutable.** The dialect and the PL/SQL are the stratum. TD-005; the test substitute is [ADR 0011](../governance/adr/0011-oracle-substitute-for-stratum-1.md) |
| Apache Tomcat 8.5 | `legacy/` WARs | End of community support, pinned deliberately. TD-003 |
| PostgreSQL | `services/ledger-*`, `batch/` | Substitutable in principle; the locking behaviour the ledger depends on is not |
| Apache Kafka | The event path from stratum 3 to stratum 2 | Ordering and redelivery semantics are load-bearing |
| Spring Framework and Spring Boot | Strata 2 and 3, on two major lines at once | TD-002 for the 2.7 line |
| GnuCOBOL | Stratum 0 | Stands in for the mainframe compiler; the copybooks are the contract, not the compiler |
| The JDKs, Go, Python, Node toolchains | Everything | Listed with versions in [`../consuming-this-repo.md`](../consuming-this-repo.md) |

The full transitive list is what each lock file already holds, and a register that restates it by hand
would be wrong within a week. **This table records the dependencies whose failure is an operational
event**, which is the question DORA is actually asking.
