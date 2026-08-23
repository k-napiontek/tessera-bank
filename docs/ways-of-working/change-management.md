# Change management

How a change is classified, approved and scheduled. Adapted from ITIL practice as banks operate it,
and written to be usable rather than aspirational: where this repository does not operate a control,
this document says so and names the register entry that records it.

[`sdlc.md`](sdlc.md) is the gate-by-gate lifecycle. This document answers the two questions that
lifecycle keeps deferring: **how risky is this change, and when may it be applied?**

---

## Change classes

Risk is classified when the change is raised, not at merge time. The class decides the approval path,
and it is recorded in the change record itself.

| Class | What it is | Approval | Scheduling |
|---|---|---|---|
| **Standard** | Pre-approved, low risk, well understood, done before | None beyond the normal gates | Any time |
| **Normal** | The default. New behaviour, a bug fix, a documented refactor | Review before merge | Outside a freeze |
| **Major** | Architecturally significant, or it touches money movement | Architecture review **first**, then review before merge | Outside a freeze, scheduled deliberately |
| **Emergency** | Restoring service, or preventing imminent loss | Proceeds on the incident commander's authority | Immediately; retrospective review within 48 hours |

**The class is a claim about blast radius, not about diff size.** A one-line change to the sign
convention in `AccountType` is major; a thousand lines of generated fixture data is standard. Getting
this backwards is the most common way a change-management process becomes theatre.

Worked against this repository's own history, so the classes mean something concrete:

- **Standard** - a patch-level dependency bump inside a stratum's pinned line; regenerating synthetic
  data from the same seed; adding a follow-up entry to `STATUS.md`.
- **Normal** - almost everything here. A work package that adds a component, a runbook, a contract
  test.
- **Major** - a change to money arithmetic, to the double-entry invariants, to the audit chain's
  canonical form, or to any interface contract in [`../../contracts/`](../../contracts/README.md).
  Also **any version change in strata 0, 1 or 2**, which additionally requires an explicit
  instruction from the repository owner and an ADR, per [`../../CLAUDE.md`](../../CLAUDE.md) and
  [ADR 0002](../governance/adr/0002-deliberate-legacy-strata.md).
- **Emergency** - none has ever been raised here. The one incident on record,
  [INC-001](../incidents/INC-001-transfers-discarded-at-the-era-boundary.md), was a deliberate
  exercise; its remediation would have been an emergency change had the estate been live.

## The change record

**The pull request is the change record.** Not a summary of it, not a pointer to one kept elsewhere -
[the template](../../.github/pull_request_template.md) is the artefact an auditor samples, and an
empty section in it is a finding.

| The record must state | Where the template asks |
|---|---|
| What changed, and why | *Change* |
| The ticket that authorised it | *Change* - `TB-XXXX` |
| Risk class and blast radius | *Risk* |
| Whether it touches money movement, authentication or personal data | *Risk* - a `yes` obliges a security review |
| Test evidence, as real output | *Test evidence* |
| How to reverse it, and whether that is lossless | *Rollback* |
| Who authored and who approved | *Approvals* |

## The Change Advisory Board

In a bank the CAB is where normal and major changes are approved and scheduled. Composition:
application owner, operations, infrastructure, security, and - for anything touching the core
banking platform - a representative of the business function that owns the process. It meets on a
fixed cadence, and its decision is a scheduling decision as much as an approval: *this change, this
window, with this rollback plan, and this person on call when it lands*.

**There is no CAB here, and there is no second approver at all.** One actor authors, verifies and
merges. This is registered as
[CE-001](control-exceptions.md#ce-001---four-eyes-review-is-not-enforced), with its compensating
controls; the load-bearing one is that **work stops after every package** and the repository owner
reviews asynchronously before the next one starts. The approval is real and it is late, which is a
weaker control than a board and is not the same as no control.

## Freeze calendar

A bank freezes change when the cost of being wrong spikes:

| Freeze | Why |
|---|---|
| Month-end | Position reporting, interest accrual, statement generation |
| Quarter-end and year-end | Regulatory and statutory reporting, external audit |
| Regulatory reporting dates | A missed or restated submission is a supervisory matter |
| The nightly batch window | The core is mid-cycle; the bank's position is being rewritten |

Only the last of those is real in this repository, and it is genuinely real. The bank day is a
versioned contract, [`tessera-day-v1.json`](../../contracts/workload/tessera-day-v1.json), and it
puts the batch window at **minute 1 230 of the business date through minute 300 of the next** - the
overnight cycle, then the morning reconciliation. Inside that window the account master is being
rewritten and `batch/recon` is comparing two cores against each other.

**So: no change to a schema, a copybook, a record layout or the movement-file format may be applied
inside the batch window.** [`../runbooks/schema-change-under-traffic.md`](../runbooks/schema-change-under-traffic.md)
defers that question to this document, and this is the answer. What that runbook adds is the other
half - some migrations are unsafe *at any hour*, and it names which, with measured figures rather
than reasoning.

The month-end and quarter-end freezes are not operated here because nothing reports to anybody. They
are specified so the companion platform repositories inherit them rather than invent them.

## Emergency change

An emergency change proceeds without prior approval and acquires it afterwards. Three rules keep that
from becoming a loophole:

1. **It needs an incident.** No incident record, no emergency change - the class is not a way to skip
   the queue. [`incident-management.md`](incident-management.md) is the procedure.
2. **Retrospective review within 48 hours**, at which the change is either normalised or reverted.
3. **The record is written to the same standard**, including test evidence and a rollback plan. Under
   pressure this is exactly what gets skipped, which is why it is the one thing the retrospective
   checks first.

## Rollback is a precondition, not an afterthought

**A change with no stated way back is not approvable.** The change record has a *Rollback* section
and it asks two questions: how the change is reversed, and whether reversal is lossless. `revert the
commit` is a complete answer for most changes here and a false one for some, so the honest position
per stratum:

| Stratum | Reversing a change | Lossless? |
|---|---|---|
| 0 `mainframe/` | `STEP020` writes a **new master generation** rather than updating the current one, so the previous generation is the rollback. A failed cycle has applied nothing | Yes |
| 1 `legacy/` | Redeploy the previous WAR. A schema change to the Oracle-dialect DDL needs its own down-path, written with it | Schema changes: not automatically |
| 2 `integration/` | Redeploy the previous jar. An in-flight message is redelivered rather than lost | Yes |
| 3 `services/` | Revert and redeploy. **A Flyway migration does not roll back** - reversal is a new forward migration, which is why expand-migrate-contract is the shape used | No, by construction |
| 4 `edge/` | Revert and redeploy; the tier is stateless | Yes |

The stratum-3 row is the one that matters and it is why WP-24b measured a migration under live
traffic instead of assuming one. **Reversing the fault and recovering from it are two different
questions** - INC-001 records an exercise where the fault was reversed cleanly and not one affected
account cleared, and `incident-management.md` now says so in as many words.

---

## What this repository does not have

Stated plainly, because a change-management document is exactly where invented coverage hides:

- **No CAB, and no independent approver** - CE-001.
- **No independent test or release function** - [CE-002](control-exceptions.md#ce-002---no-independent-test-or-release-function).
- **No production, and therefore no production freeze**, no maintenance windows and no on-call
  rota. The batch window above is a property of the estate's own day, not of an operating schedule.
- **No automated enforcement of any of it.** There is no pipeline in this repository by design
  ([ADR 0001](../governance/adr/0001-source-only-repository.md)); the branch-protection rules that
  would enforce the review gate are declared in
  [`branching-and-review.md`](branching-and-review.md#branch-protection-to-apply) for whoever
  operates the remote.
