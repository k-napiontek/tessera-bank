# DORA control map

The EU Digital Operational Resilience Act - **Regulation (EU) 2022/2554, applicable since 17 January
2025** - mapped to artefacts that genuinely exist in this repository.

**Where a requirement is out of scope for a source repository, this document says so rather than
inventing coverage.** That is not modesty. A control map is sampled by picking a row and asking to
see the artefact, and a row pointing at something that does not exist is a worse finding than an
honest gap - it converts an unknown into a false assurance, which is the failure mode the
[Definition of Done](../ways-of-working/definition-of-done.md)'s honesty clause exists to prevent.

Two mechanical guarantees back that up. Every artefact named below is a **link**, and
`quality/docs-check.py` fails the build if any internal link resolves to nothing, so a row cannot
survive the document it points at being deleted or renamed. And no row may cite a requirement id that
is not in the [catalogue](traceability-matrix.md).

## How to read a status

| Status | Meaning |
|---|---|
| **Covered** | The artefact exists here and does the thing the article asks for |
| **Partial** | Something real exists and it does not discharge the obligation on its own. The row says what is missing |
| **Out of scope** | This is not a source repository's to satisfy. The row says whose it is: the **platform** repositories, or the **institution** that would operate the estate |

---

## Pillar 1 - ICT risk management (Chapter II, Articles 5-16)

| Article | What it asks | Artefact here | Status |
|---|---|---|---|
| 5 - Governance | The management body owns and is accountable for the ICT risk framework | [`../../.github/CODEOWNERS`](../../.github/CODEOWNERS) expresses ownership per stratum; the handles are placeholders (F-04) | **Out of scope** - institution |
| 6 - Risk management framework | A documented framework, reviewed periodically | [`../governance/tech-radar.md`](../governance/tech-radar.md), the [ADRs](../governance/adr/README.md), [`../technical-debt.md`](../technical-debt.md) with owners and review dates, [`../ways-of-working/control-exceptions.md`](../ways-of-working/control-exceptions.md) | **Partial** - no periodic review cycle operates |
| 7 - ICT systems and tools | Reliable, technologically resilient, with capacity to handle demand | [`../architecture/estate-under-load.md`](../architecture/estate-under-load.md) and [`../architecture/query-plans-at-volume.md`](../architecture/query-plans-at-volume.md) - measured, not asserted | **Covered** for what is built |
| 8 - Identification | Identify and classify all ICT assets and dependencies | [`../architecture/estate-map.md`](../architecture/estate-map.md); the vendor component register in [`../ways-of-working/dependency-policy.md`](../ways-of-working/dependency-policy.md); lock files per toolchain | **Covered** |
| 9 - Protection and prevention | Confidentiality, integrity, availability; segregation; access control | Append-only postings, a **hash-chained audit trail** (`REQ-AUD-001`), idempotent money movement (`REQ-API-001`), edge authentication (`REQ-EDG-001`), [`../ways-of-working/data-classification.md`](../ways-of-working/data-classification.md) | **Partial** - encryption, key management and network segmentation are the platform's |
| 10 - Detection | Mechanisms to detect anomalous activity promptly | [`../ways-of-working/slo-catalogue.md`](../ways-of-working/slo-catalogue.md), the ledger's metrics, `batch/recon` as a detection source - listed in [`../ways-of-working/incident-management.md`](../ways-of-working/incident-management.md#detection) | **Partial** - and the gap is named below |
| 11 - Response and recovery | Continuity plans, response and recovery procedures | The [runbooks](../runbooks/eod-cycle.md), [`../ways-of-working/incident-management.md`](../ways-of-working/incident-management.md); a generational account master, so a failed cycle has applied nothing | **Partial** - no continuity plan; there is nothing to keep running |
| 12 - Backup and restoration | Backup policies, restoration methods, tested | The master generation is a genuine restoration path and is tested by the cycle's own rerun | **Out of scope** - platform |
| 13 - Learning and evolving | Post-incident review feeding back into the framework | [INC-001](../incidents/INC-001-transfers-discarded-at-the-era-boundary.md); the Follow-ups register in [`../plan/STATUS.md`](../plan/STATUS.md); the exercise **changed the procedure it was run against** | **Covered** |
| 14 - Communication | Crisis communication plans, to clients and authorities | Who does what is in [`../ways-of-working/incident-management.md`](../ways-of-working/incident-management.md#who-does-what) | **Out of scope** - institution; there are no clients to notify |

## Pillar 2 - ICT-related incident management (Chapter III, Articles 17-23)

| Article | What it asks | Artefact here | Status |
|---|---|---|---|
| 17 - Management process | A process to detect, manage and notify incidents | [`../ways-of-working/incident-management.md`](../ways-of-working/incident-management.md) - **written before the exercise and then used in anger**, which is the difference between a process and a document | **Covered** |
| 18 - Classification | Classify by criteria: clients affected, duration, geographical spread, data losses, criticality, economic impact | [Classification criteria](../ways-of-working/incident-management.md#classification-criteria), each row stating **what this estate can genuinely evidence** for that criterion | **Covered** |
| 19 - Reporting of major incidents | Initial, intermediate and final reports to the competent authority, on the clock | [Reporting timelines](../ways-of-working/incident-management.md#reporting-timelines) | **Partial** - the clock is documented; there is no authority to report to |
| 20-22 - Harmonisation, centralisation, supervisory feedback | Supervisory machinery | - | **Out of scope** - supervisory |
| 23 - Payment-related operational incidents | The same regime applies to operational or security payment-related incidents | [INC-001](../incidents/INC-001-transfers-discarded-at-the-era-boundary.md) is exactly this class: two transfers in the ledger's audit chain that do not exist for the mainframe | **Covered** as an exercise |

## Pillar 3 - Digital operational resilience testing (Chapter IV, Articles 24-27)

| Article | What it asks | Artefact here | Status |
|---|---|---|---|
| 24 - General requirements | A testing programme, proportionate, covering critical systems | [`../ways-of-working/test-strategy.md`](../ways-of-working/test-strategy.md) | **Covered** |
| 25 - Testing of tools and systems | Vulnerability assessments, scenario-based tests, performance testing, end-to-end testing | Seven declared scenarios in [`../../contracts/workload/`](../../contracts/workload/README.md) with captures under [`../../workload/baselines/`](../../workload/baselines/README.md); a [migration under live traffic](../runbooks/schema-change-under-traffic.md); a soak; the incident exercise | **Partial** - no vulnerability assessment and no composition analysis run here (F-03) |
| 26 - Threat-led penetration testing | TLPT every three years for significant entities, on live production systems | - | **Out of scope** - there is no live production system |
| 27 - Requirements for testers | Independence of the testers | - | **Out of scope** - [CE-002](../ways-of-working/control-exceptions.md#ce-002---no-independent-test-or-release-function) registers the absence |

## Pillar 4 - ICT third-party risk (Chapter V, Articles 28-44)

| Article | What it asks | Artefact here | Status |
|---|---|---|---|
| 28 - General principles | Third-party risk managed as part of the ICT framework; a register of information | The vendor component register and the sourcing policy in [`../ways-of-working/dependency-policy.md`](../ways-of-working/dependency-policy.md) | **Partial** - a register of components, not of contracts |
| 28(8) - Exit strategies | An exit strategy for each critical provider | The register states substitutability directly: **Oracle is not substitutable** - the dialect and the PL/SQL are the stratum ([ADR 0011](../governance/adr/0011-oracle-substitute-for-stratum-1.md), TD-005) | **Partial** - the dependency is stated, the exit is not planned |
| 29 - Concentration risk | Assess before contracting; account for it | Named in the register; the estate has one unavoidable concentration and says so | **Partial** |
| 30 - Contractual provisions | Mandatory contractual terms with providers | - | **Out of scope** - institution |
| 31-44 - Oversight of critical providers | The supervisory oversight framework | - | **Out of scope** - supervisory |

## Pillar 5 - Information and intelligence sharing (Chapter VI, Article 45)

| Article | What it asks | Artefact here | Status |
|---|---|---|---|
| 45 - Sharing arrangements | Voluntary exchange of cyber threat intelligence within trusted communities | - | **Out of scope** - an institutional arrangement, not a property of source code |

---

## The gaps that are real rather than structural

Most **Out of scope** rows above are structural: this repository has no production, no customers and
no supervisor, so the obligation cannot land here. Three gaps are different - they would matter to
anyone deploying this estate, and none of them is fixed by a platform repository alone.

1. **Detection is uneven, and the incident exercise proved it.** `legacy/customer-master` and
   `integration/esb-adapter` expose **no metrics at all** - no actuator, no Micrometer, and in the
   adapter's case not even a web starter (F-100, F-108). The two strata that carry a transfer between
   1995 and 2023 are the two that cannot be watched from inside. INC-001's detection came from the
   next morning's reconciliation report, which makes it a **lagging** detector for a whole class of
   fault, and the estate has no leading one.
2. **No software composition analysis runs anywhere.** The policy is written and the scanner is not
   configured (F-03). For an estate deliberately pinned to end-of-life components, that is the pillar
   1 control whose absence is least comfortable - even though every finding it produced against
   strata 0 to 2 would be expected output rather than a defect.
3. **The reconciliation's false-positive population compounds.** By the escalation thresholds in
   [`../runbooks/reconciliation-break.md`](../runbooks/reconciliation-break.md), every morning in this
   estate is already an incident (F-113, F-114) - **which is how a control gets turned off**. A
   detection control that cries wolf discharges Article 10 on paper and defeats it in practice.

## What would move a row

**Covered** here means the artefact exists and does what the article asks, at the scope a source
repository has. It does not mean an institution deploying this estate could point at this document
and stop: every **Partial** row names what is missing, and the platform repositories inherit the rest
through [`../consuming-this-repo.md`](../consuming-this-repo.md).

A row moves when the artefact does, in the same change - never afterwards. A control map maintained
retrospectively is a description of what somebody remembered.
