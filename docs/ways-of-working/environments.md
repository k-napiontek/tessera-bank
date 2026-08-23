# Environments

The environment ladder, what is tested at each rung, who signs off, and what data each may hold.

**The data rules here are a GDPR and DORA obligation, not a convention.** Production data flowing
downward into a test environment is one of the most common personal-data breaches in banking, and it
almost never looks like a breach at the time - it looks like a realistic test.

This repository ships no deployment artefacts ([ADR 0001](../governance/adr/0001-source-only-repository.md)),
so the ladder below is a **specification for the companion platform repositories** rather than
something this repository operates. What this repository has is one rung and one actor, and the last
section says so without dressing it up.

---

## The ladder

| Rung | Purpose | What is tested | Data | Sign-off to promote in |
|---|---|---|---|---|
| **DEV** | Where a change is built | Unit, property, contract; the tier's own suite | **Synthetic** | None - the engineer |
| **SIT** | Where the tiers meet | System integration across strata; the four-era transfer; the overnight cycle end to end | **Synthetic** | Automated suite green |
| **UAT** | Where the business accepts it | Acceptance against the requirement, by the function that owns the process | **Masked** | Business owner, named |
| **PREPROD** | Production-shaped | Performance, capacity, resilience, failure injection, migration under load | **Masked** | Operations and security |
| **PROD** | Live | Nothing. Verification only | **Real** | Change Advisory Board |

Promotion is **upward only and one rung at a time**. A build promoted from SIT to PROD is a build
that was never accepted by anybody, and the audit question - *who signed this off* - has no answer.

The artefact promoted is the **same artefact**: the WAR, the jar, the container image built once at
DEV and carried up. Rebuilding per rung means the thing in production is not the thing that was
tested, which is the failure mode the promotion model exists to prevent.

## What each rung actually needs

| Rung | Needs |
|---|---|
| DEV | A laptop with the toolchains in [`../consuming-this-repo.md`](../consuming-this-repo.md), Docker for the tests that use a real database |
| SIT | Every stratum running at once: PostgreSQL, Kafka, Oracle, Tomcat 8.5, the JDK 8 and JDK 17 tiers, GnuCOBOL for the cycle |
| UAT | SIT plus a business-shaped dataset and the back-office screens |
| PREPROD | Production-shaped **data volumes**, not just production-shaped topology - see below |
| PROD | Whatever the platform repositories deploy, plus the batch scheduler that owns the EOD cycle and the morning reconciliation |

**PREPROD is the rung most often built wrong**, because it is built to production's shape and
populated with a fixture. Every query in this repository once ran against about three accounts, and
the plans they get at that size say nothing about the plans they get at a year of postings -
[`../architecture/query-plans-at-volume.md`](../architecture/query-plans-at-volume.md) is what
happened when that assumption was tested. A recorded normal captured against a fixture is a recorded
normal *of the fixture*.

The workload strand exists to make a rung like this meaningful: the bank day declared as a versioned
contract, a driver that executes it at volume, a production-shaped ledger to execute it against, an
[SLO catalogue](slo-catalogue.md) with a recorded baseline under
[`../../workload/baselines/`](../../workload/baselines/), and failure injection on top.

## Data rules

**Production data never flows downward. There is no exception, including "just this once to
reproduce a defect".**

| Rung | How it gets data |
|---|---|
| DEV, SIT | **Synthetic generation only** - the generators in this repository, deterministic from a seed |
| UAT, PREPROD | **Masked**: pseudonymised at extraction, with the mapping destroyed rather than kept |
| PROD | Its own |

Masking is a one-way transformation performed **at the point of extraction**, inside production's
trust boundary. A dump taken first and masked afterwards has already put real data on a lower rung;
the copy that matters is the one that existed in between.

The classification these rules serve is [`data-classification.md`](data-classification.md):
personal data is **Restricted** and nothing in this repository may contain it. Where the estate does
hold identity - `legacy/customer-master`, whose 2011 schema requires it - the fixtures fill it with a
marker rather than a manufactured person, so **there is nothing to anonymise because there was never
an identity**. That is a stronger position than a well-masked one, and it is why the reconciliation,
the drivers and every report are keyed by account reference. The erasure path, which the ledger's
append-only design genuinely complicates, is [`../compliance/gdpr-data-map.md`](../compliance/gdpr-data-map.md).

## Access

| Rung | Who has write access | Who has read access to data |
|---|---|---|
| DEV, SIT | The engineers who own the tier | Anyone - it is synthetic |
| UAT | Release management; engineers on request, time-boxed | The business function that owns the process |
| PREPROD | Release management and operations | Operations; engineers on request, time-boxed |
| PROD | **Nobody, routinely.** Change lands through the pipeline; human access is break-glass, logged and reviewed | Operations, for the minimum the role requires |

Break-glass access is the control that matters at the top of the ladder: it must be possible, it must
be attributable to a named person, and it must generate a record that somebody actually reads.
Segregation of duties says the person who wrote the change is not the person who applies it in
production - which this repository does not operate, and registers as
[CE-002](control-exceptions.md#ce-002---no-independent-test-or-release-function).

---

## What this repository actually has

**One environment and one actor.** DEV is a laptop; there is no SIT, no UAT, no PREPROD and no
production. Nothing here is deployed anywhere, which is also why the residual risk in
[`control-exceptions.md`](control-exceptions.md) is accepted at all.

What stands in for SIT is a set of scripts that compose the estate rather than reimplement it:
`workload/scripts/estate-up.sh` boots PostgreSQL, Kafka, the ledger, the fraud scorer and the gateway
and drives a compressed bank day at them; `legacy-up.sh` boots Oracle and Tomcat 8.5 with the
`customer-master` WAR on it; `four-era-day.sh` runs `esb-adapter` between the two so a Kafka event
becomes canonical XML, a SOAP call and a COMP-3 record; `migration.sh` and `soak.sh` do to that
estate what a PREPROD exercise would. They are **test fixtures, not components of the bank**, and
they are the closest thing here to a rung above DEV.

Two substitutions are load-bearing and are recorded rather than glossed:

- **Oracle** is Oracle Database 23ai Free in a container ([ADR 0011](../governance/adr/0011-oracle-substitute-for-stratum-1.md)),
  because Oracle is not distributable. A compatibility mode was the alternative and runs no PL/SQL at
  all, which would have tested a pretence.
- **Tomcat 8.5** is downloaded and started by the test itself, so the WAR is deployed to the real
  container it is pinned to rather than to an embedded stand-in.

Both are named in [`../technical-debt.md`](../technical-debt.md) with their compensating controls.
