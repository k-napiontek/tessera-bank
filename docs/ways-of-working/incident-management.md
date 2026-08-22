# Incident management

How an incident is detected, classified, contained, resolved and learned from in this estate.

**A procedure that has never been used is a document, not a process.** This one is exercised for real
in [WP-18a](../plan/wp/WP-18-incident-exercise.md), against a deliberate fault, worked as written
rather than as convenient - and where it turns out to be wrong or unusable, that is recorded here
rather than quietly corrected afterwards. Everything below was written **before** that exercise ran;
what the exercise changed is at the end, under [What the first exercise changed](#what-the-first-exercise-changed).

---

## What counts as an incident

An incident is an unplanned event that has harmed, or could harm, the correctness of the bank's
records or a customer's ability to use it.

Two things follow from that wording and both matter here:

- **A control that fires is not an incident.** `batch/recon` reporting `TIMING` differences, the
  end-of-day cycle writing rejects, the gateway refusing an unauthenticated request - these are the
  estate working. Treating them as incidents trains people to ignore the report, which is the failure
  mode [ADR 0015](../governance/adr/0015-the-cut-off-is-the-movement-file.md) already warns about.
- **A control that is silent is not evidence of health.** Absence of output and a clean night look
  identical from outside. That is why the reconciliation always writes a report, and why *no report
  at all for a business date* is itself an incident.

The runbooks hold the specific thresholds at which a condition becomes an incident.
[`reconciliation-break.md`](../runbooks/reconciliation-break.md) is the one with the most developed
set, and this document does not restate them - a threshold written twice is a threshold that will
disagree with itself.

## Severity

**Money movement and data loss are the escalating factors.** Everything else - how many components
are involved, how loud it is, who noticed - moves severity at most one step. A quiet defect that
misstates a balance outranks a noisy outage that does not.

| | Severity | Meaning | Response |
|---|---|---|---|
| **P1** | Critical | Money is wrong, or records are lost or unrecoverable. A customer's balance does not reflect what happened to their money, and the estate cannot correct itself | Immediate. Containment before diagnosis. Reporting clock starts at detection |
| **P2** | Major | Money movement is blocked, or a control that protects money is not operating. Nothing is yet wrong; nothing is protecting it either | Same business day. Contain, then diagnose |
| **P3** | Minor | Degraded service with money movement intact and every control operating - latency outside objective, a tier slow, a report late | Next business day |
| **P4** | Low | No customer or record impact. A cosmetic defect, a noisy log, a runbook that is out of date | Tracked as ordinary work |

**A P2 becomes a P1 the moment it is shown that money is actually wrong**, not when it is suspected.
Suspicion is what P2 is for; the whole point of separating them is that containment for "we might be
wrong" and for "we are wrong" is the same action, and it should not wait for certainty.

**Two rules that override the table.**

- Any break classified `MISSING_IN_LEDGER` is at least **P2** whatever its value: the master holds an
  account the ledger does not, which means the two cores disagree about whether a customer exists.
- Any condition that has survived a full cycle unexplained is at least **P2**, whatever it was first
  classified as. A difference that outlives the process that was supposed to clear it is not timing.

## Detection

### What actually detects things here

| Source | What it catches | Where it surfaces |
|---|---|---|
| **The reconciliation report** | The two cores disagreeing about a balance or an account | `BREAKS-<date>.json`, read in `legacy/backoffice` |
| **The end-of-day cycle** | Records the mainframe refused, and a cycle that did not complete | `REJECTS.DAT`, the step return codes, `EODREPT` |
| **The SLO catalogue** | An objective missed over a window | [`contracts/slo/`](../../contracts/slo/), evaluated by `workload-report` |
| **The ledger's own metrics** | Outbox backlog, pool exhaustion, posting failures | `/actuator/prometheus` |
| **An operator** | Everything the above does not | The back office, or a direct report |

### What does not detect things here, deliberately

**This repository declares objectives; it does not page.**
[ADR 0012](../governance/adr/0012-slo-catalogue-boundary.md) draws that line on purpose: the SLI, the
target, the threshold and the error budget live here, and the alert that fires at 3 a.m. is
configured in the platform repositories that deploy this source. A repository that carried both would
be the second place the estate says what good looks like.

The consequence is honest and worth stating plainly: **in this repository, detection latency is the
latency of a human reading a report.** In a deployed estate it is the alert. Any figure this
repository quotes for time-to-detect is therefore a property of the exercise and not of the bank, and
the RCA says so wherever it quotes one.

### The detection gap this estate is known to have

`batch/recon` counts a posting towards what the master ought to hold when its reference is in the
movement file **or** its value date is earlier than the business date. A fault that stops postings
*reaching* the movement file therefore produces no break on the day it happens - the postings are in
neither set - and surfaces a full cycle later, when their value dates fall behind the business date.

**The reconciliation is blind for one cycle to anything that blocks the integration hop.** That is
not a defect in the reconciliation; it is what makes the timing/drift distinction exact. It does mean
the recon report is a *lagging* detector for this class, and that the estate has no leading one.

## The response

### Triage

1. **Classify severity** against the table above, from what is known rather than what is suspected.
2. **Establish the blast radius**: which accounts, which business dates, which components. Record the
   figures before touching anything - they are the only "before" that will exist.
3. **Decide whether money is still moving.** If it is, and correctness is in doubt, containment comes
   before diagnosis.

### Containment

**Contain before you understand.** The goal is to stop the estate making the problem larger, not to
fix it. Containment actions available here, cheapest first:

- Stop the driver, or whatever is offering load.
- Stop the relay or the consumer that is propagating the bad state, accepting the backlog. A backlog
  is recoverable; a wrong balance replicated into a second core is not.
- Hold the overnight cycle. It is the step that makes today's disagreement permanent in the master.

**Never contain by deleting.** No message is dropped, no offset is reset past an unprocessed record,
no row is deleted to make a report clean. The estate's guarantees are built on records that survive -
[ADR 0014](../governance/adr/0014-the-movement-file-is-its-own-unique-constraint.md) exists because a
crash between two systems must be recoverable by asking what is there, and a record deleted to tidy
up destroys exactly that.

### Resolution

Resolution is a change, and it goes through change management like any other. An incident does not
suspend the controls; it changes their **risk class** to `emergency`, which means the review is
retrospective rather than prior. It does not mean there is no review.

### Verification of recovery

**A recovery is not verified by the absence of the symptom.** It is verified by re-running the
control that found the problem and getting a clean result:

- A reconciliation break is cleared when the reconciliation is run again and agrees.
- A blocked hop is cleared when the backlog drains to zero *and* the records it was holding are shown
  to have arrived.
- A rejected batch is cleared when the cycle is re-run and the rejects file is empty or explained.

Stating recovery without re-running the control is stating an expectation.

## Who does what

In a bank these are separate people: an incident manager who runs the response, a technical lead who
diagnoses, an operator who works the back office, a compliance officer who owns the regulatory clock.

**In this repository one actor holds every role, and that is registered rather than dressed up.** See
[CE-001](control-exceptions.md#ce-001---four-eyes-review-is-not-enforced). The roles are named here
anyway, because the procedure has to be transferable to an estate that has them, and because naming
them is what makes the gap visible:

| Role | Owns | Held here by |
|---|---|---|
| Incident manager | The response, the clock, the decision to escalate | The repository owner |
| Technical lead | Diagnosis, containment actions, the fix | The repository owner |
| Operator | The back office, the evidence, working the breaks | The repository owner |
| Compliance | Classification against DORA, the reporting clock | The repository owner |

## DORA classification and reporting

DORA (Regulation (EU) 2022/2554) has applied since January 2025 and requires major ICT-related
incidents to be classified and reported to the competent authority.

> **This section is the repository's reading of the regulation and its technical standards, not legal
> advice, and the regulation and its RTS are authoritative.** A compliance position invented in a
> source repository is worth less than no position at all, which is the same reasoning that left the
> retention period in `technical-debt.md` open rather than guessed.

### Classification criteria

DORA classifies on the criteria below. What this estate can genuinely evidence for each is stated
beside it, because a criterion nothing can measure is a box nobody can honestly tick.

| Criterion | What this estate can evidence |
|---|---|
| Clients and financial counterparts affected | Account references touched, from the reconciliation report |
| Data losses - availability, authenticity, integrity, confidentiality | The audit chain and the reconciliation; integrity is the one this estate measures best |
| Criticality of services affected | The spine in [`master-plan.md`](../plan/master-plan.md) names which components a transfer needs |
| Duration and service downtime | Wall clock from detection; **not** from onset, which this estate usually cannot establish |
| Geographical spread | Out of scope - a single synthetic estate |
| Economic impact | Out of scope - no real money moves here |
| Reputational impact | Out of scope for a source repository |

**An incident is major when the thresholds in the RTS are crossed, not when it feels serious.** The
severity model above is operational and drives the response; DORA classification is regulatory and
drives the clock. They are different questions and an incident can be P1 and not major, or major and
P2.

### Reporting timelines

| Report | When |
|---|---|
| Initial notification | Within 4 hours of classifying the incident as major, and no later than 24 hours from becoming aware of it |
| Intermediate report | Within 72 hours of the initial notification |
| Final report | No later than one month after the intermediate report |

**The clock starts at awareness, not at onset.** For the detection gap described above that
distinction is the whole game: an incident whose effects began a cycle before anyone could see them
is reported from the moment it was seen, and the RCA is where the gap between the two is stated.

## Root cause analysis

### Blameless means blameless about people

It does not mean blameless about process. An RCA that concludes "human error" and stops has found
nothing; an RCA that will not say the runbook was wrong is protecting a document at the expense of
the next incident.

**The RCA must be honest about what the exercise or incident revealed, including anything
embarrassing.** A sanitised RCA teaches nothing and undermines the credibility of every other
document in this repository.

### The template

```markdown
# RCA: <one line, what happened>

| | |
|---|---|
| **Incident** | INC-<id> |
| **Severity** | P<n>, and whether it was reclassified |
| **DORA** | major / not major, and against which criterion |
| **Detected** | <when, and by what> |
| **Contained** | <when, and by what action> |
| **Resolved** | <when, and how verified> |

## What happened
Plain narrative, in order, with timestamps. What was believed at each point, not only what was true.

## Impact
Accounts, business dates, money. Figures, with the evidence they came from.

## Why it happened
The mechanism, to the point where it stops being interesting. Not "a bug" - which line, which
assumption, which two correct rules that could not both hold.

## Why it was not caught sooner
The detection story, honestly. What should have caught it, and why it did not.

## What went wrong in the response
Procedure steps that were unusable, evidence that did not exist, tools that were reached for and were
not there. **This section being empty is a claim, and it is usually a false one.**

## What changes
| # | Change | Owner | Tracked as |
|---|---|---|---|
Each one either lands with the RCA or becomes a tracked follow-up. "Be more careful" is not a change.
```

## After the incident

**Every RCA action becomes tracked work or it did not happen.** Findings go to the Follow-ups table
in [`STATUS.md`](../plan/STATUS.md) with an `F-` number, the same register everything else in this
repository uses. An action that lives only in the RCA is an action nobody will run.

A finding is closed by the change that fixes it, referenced by number, and the register says which
change closed it. A finding that is decided against is closed as a decision with the reason, not
deleted - the reasoning is the part worth keeping.

## What the first exercise changed

> Filled by WP-18a, after the exercise. Until then this section is empty on purpose: writing it in
> advance would make this document a description of an exercise rather than a procedure the exercise
> could fail.
