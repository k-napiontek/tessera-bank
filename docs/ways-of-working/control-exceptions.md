# Control exceptions register

Controls that this repository **documents but does not enforce**, each with the reason, the
compensating controls, an owner and a review date.

This register exists because the alternative is worse. A repository that describes four-eyes review,
segregation of duties and change management, while in practice having one actor do everything, is
making a claim its own commit history disproves. An auditor's first move is to sample changes and
check the claim against the evidence, and a control claimed but not operated is a more serious
finding than a control honestly declared absent.

Registering the gap is what regulated engineering looks like. Claiming the control is what fails the
audit.

---

## CE-001 - Four-eyes review is not enforced

| | |
|---|---|
| **Control** | Maker-checker: the author of a change never approves or merges it |
| **Standard** | Segregation of duties; SOX change-management control; DORA ICT change management |
| **Status** | **Not enforced** |
| **Owner** | Karol Napiontek |
| **Raised** | 2026-08-17 |
| **Review** | On any move to multi-contributor working |

**Why.** This is a solo project. The AI agent authors the change and merges it once its own
verification passes. There is no second party in the loop at merge time, so the control cannot
operate as designed.

**Compensating controls.**

1. Every change lands through a pull request carrying a completed change record - what changed, why,
   risk class, test evidence, rollback plan. The audit trail exists even though the approval does
   not.
2. Merge is conditional on verification: the work package's verification commands must have been run
   and passed, with real output pasted into the PR. A red package is not merged.
3. Work **stops after every package** and reports. The repository owner reviews asynchronously, and
   nothing proceeds to the next package without an explicit instruction - so review happens, just
   after merge rather than before it.
4. Every change is small by policy (roughly 3-10 commits, under ~400 lines per commit), so
   after-the-fact review is genuinely possible rather than theatrical.

**Residual risk.** A defect can reach `main` without any human having read it. Accepted, because
`main` here deploys to nothing: this repository has no runtime, no users and no data. The risk would
become unacceptable the moment a companion platform repository deploys from it automatically.

---

## CE-002 - No independent test or release function

| | |
|---|---|
| **Control** | Testing and release performed by parties independent of development |
| **Standard** | SOX change management; segregation of duties |
| **Status** | **Not enforced** |
| **Owner** | Karol Napiontek |
| **Raised** | 2026-08-17 |
| **Review** | On any move to multi-contributor working |

**Why.** One actor writes the code, writes the tests and performs the merge. The environment ladder
described in [`environments.md`](environments.md) documents how a real institution separates these
roles; this repository has one environment and one actor.

**Compensating controls.** Test-driven development, so tests are written before the implementation
rather than shaped to fit it. Property-based tests over the ledger invariants, which are not written
against a specific implementation. Contract tests that fail when implementation and contract drift.
Verification evidence recorded in the change record.

**Residual risk.** Tests may encode the same misunderstanding as the implementation. Partly mitigated
by property-based testing, which asserts invariants rather than examples, but not eliminated.

---

## CE-003 - Dependencies are not proxied through an internal repository

| | |
|---|---|
| **Control** | All dependencies retrieved from an internal proxy, never directly from public registries |
| **Standard** | DORA ICT third-party risk; supply-chain integrity |
| **Status** | **Not enforced** |
| **Owner** | Karol Napiontek |
| **Raised** | 2026-08-17 |
| **Review** | If a companion platform repository introduces a proxy |

**Why.** Running Nexus or Artifactory for a personal project is disproportionate. Builds pull from
Maven Central, npm, PyPI and the Go module proxy directly.

**Compensating controls.** [`dependency-policy.md`](dependency-policy.md) states the policy a real
bank would operate, so the intended control is documented even where it is not operated. Dependencies
are version-pinned and lock files committed. The approved-licence list still applies at review time.

**Residual risk.** Exposure to public registry compromise and dependency-confusion attacks. Accepted
for a repository that deploys nowhere.

---

## How to use this register

When adding a control to any document in [`../ways-of-working/`](.) or
[`../compliance/`](../compliance/), ask whether it is actually operated here. If it is not, add it to
this register instead of describing it as though it were. If a control later becomes enforced, move
it out of this register and record the change in [`../plan/STATUS.md`](../plan/STATUS.md).
