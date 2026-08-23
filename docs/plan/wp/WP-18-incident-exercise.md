# WP-18 - Incident exercise and documentation pass

| | |
|---|---|
| **Ticket** | TB-1018 |
| **Branch** | `feat/TB-1018-incident-exercise` |
| **Stratum** | n/a - repository-wide |
| **Depends on** | WP-16 |
| **Status** | **18a `Done`** ([#87](https://github.com/k-napiontek/tessera-bank/pull/87), `776a8c0`) - **18b `Not started`** |

## Objective

Prove the process works by using it. Break reconciliation deliberately, work the failure through the
documented incident procedure exactly as written, and produce a genuine root cause analysis. Then
close every documentation gap the exercise exposes. An incident process that has never been exercised
is not a process - it is a document, and it will fail the first time it matters.

## In scope

- A deliberate, documented fault injected into the estate that produces a reconciliation break.
- The incident worked end to end through `docs/ways-of-working/incident-management.md`: detection,
  severity classification, triage, containment, resolution.
- A real root cause analysis written from what actually happened, not from what should have happened.
- Every gap the exercise exposes fixed - in the runbooks, the incident procedure, or the code.
- Final documentation pass: fill remaining stubs, complete the traceability matrix, complete the DORA
  control map, verify every internal link.

## Out of scope

- Injecting faults into anything that would be dangerous or irreversible.
- Adding new features. This package hardens and documents what exists.

## Constraints

- The incident must be worked **as documented**, not as convenient. Where the documented procedure
  turns out to be wrong or unusable, that is the finding - record it and then fix the procedure.
- The RCA must be honest about what the exercise revealed, including anything embarrassing. A
  sanitised RCA teaches nothing and would undermine the credibility of every other document here.
- The fault must be reversible and its removal verified.

## Tasks

Detailed 2026-08-23, in its own change rather than inside the branch that executes it - the same
reason the decision log records for WP-21, WP-23 and WP-25. The package lands as **two halves on one
ticket**, WP-18a and WP-18b, each its own branch and pull request, tracked as two rows in
`STATUS.md`.

**Detailing it out is what showed it could not be one package.** The frame reads as an exercise plus
a documentation pass. What is actually here is an incident exercise, **ten** stub documents, a DORA
control map, a link checker that does not exist, and **eight** unresolved requirements - five of them
belonging to a package that closed without them. WP-09, WP-10, WP-11, WP-24 and WP-25 were each split
in the plan rather than in the pull request at less than this, and the decision log's answer at this
size has not changed.

The split runs where the dependency runs. **18a is the exercise**, and everything it needs to be an
exercise rather than an improvisation. **18b is the documentation pass**, which needs the exercise to
have happened so that `incident-management.md` can carry a worked RCA rather than a template.

Four decisions are taken here rather than left to the executing session.

- **The procedure is written before the exercise, and it is 18a's first task rather than 18b's.**
  This package's Constraint says the incident must be worked *as documented*, and
  `ways-of-working/incident-management.md` is a **stub**: it has a severity model in outline, no
  triage path, and no RCA template. **An exercise against a document that does not exist is an
  improvisation with a report attached**, and it would tick this package's own Definition of Done
  while proving nothing. Writing it first also sets up the finding the package is really after: the
  Constraint says *"where the documented procedure turns out to be wrong or unusable, that is the
  finding"*, and a procedure written cold and then used in anger for the first time is precisely
  where that shows up.

- **The fault is F-106, because the estate already has it.** WP-25d found that a transfer whose value
  date precedes its account's `opened_date` is refused `ORA-02290` by stratum 1, arrives at the
  adapter as a *generic* SOAP fault, is classified **transient**, and is then retried for ever at
  Spring Kafka's default zero backoff - blocking the partition by design, so **every transfer behind
  it silently stops reaching the mainframe** and nothing is dead-lettered. That is a real defect of
  this estate rather than a fault manufactured for the exercise, it produces exactly the
  reconciliation break the In scope asks for, and its detection is genuinely indirect: nothing fails,
  no alert fires on an error rate, and the first sign is the next morning's recon report. **It is
  reversible without touching the queue**: correcting the account's `opened_date` in stratum 1 makes
  the next redelivery succeed, so the retry that was blocking the partition becomes the retry that
  drains it - no offset surgery, no message deleted, and the recovery is verified by the same
  reconciliation that found the break.

- **It is an exercise of its own and adds nothing to `TB-SCENARIOS-V1`.** [ADR 0018](../../governance/adr/0018-the-migration-exercise-is-not-a-condition.md)
  settled this shape for the migration and the mechanical reason is unchanged: **F-91** means
  `Catalogue.Digest()` covers the whole catalogue, so an eighth condition would invalidate all seven
  captures WP-24c committed, for a change that has nothing to do with any of them. The fault is
  injected by its own script, sealed, and captured under `workload/baselines/`.

- **18b absorbs four stubs and five requirements that belong to packages already `Done`, and records
  why.** `compliance/psd2-notes.md` is WP-12's, `governance/tech-radar.md` and
  `ways-of-working/dependency-policy.md` are WP-02's, `ways-of-working/test-strategy.md` is WP-06's,
  and `REQ-GOV-001` to `REQ-GOV-005` are WP-01's - every one of those packages is merged and none of
  them filled what it declared. This package's Definition of Done says **no stub may remain**, so
  they are WP-18's whether or not they were WP-18's work. They are filled here and the orphaning is
  recorded as **F-17 again**: a document that no Definition of Done covers is a document nothing
  checks, and four packages closing over their own stubs is the strongest evidence of it yet. **18b
  builds the control rather than only the correction** - see its task 4.

### WP-18a - the incident, worked

Branch `feat/TB-1018-incident-exercise`. Six tasks.

1. Set 18a `In progress` and branch from up-to-date `main`.

2. **Write `ways-of-working/incident-management.md` for real, before anything is broken.** The
   severity model P1 to P4 with money movement and data loss as the escalating factors; detection
   sources named, the reconciliation report among them; triage, containment, resolution and who does
   what; DORA's reporting timelines and classification criteria; a blameless RCA template that has a
   place for process failures and not only technical ones; and how a post-incident finding becomes
   tracked work. Written cold, from what this estate actually has - and **not adjusted afterwards to
   match what the exercise happened to do**, because that would be fitting the procedure to the
   incident rather than the other way round. Its own commit, so the diff shows what was written
   before the exercise and what the exercise changed.

3. **The fault, injected by a script that seals what it planted, over two business dates.**
   `workload/scripts/incident-exercise.sh`, composing `four-era-day.sh` the way `migration.sh`
   composes `estate-up.sh`. It moves **one** account's `opened_date` forward by a day in stratum 1
   after seeding, drives the day, runs the overnight cycle and then `batch/recon`. What it planted -
   the account reference, the transfer reference and the instant - is written to a **sealed envelope**
   under the capture directory, separately from everything the responder is allowed to look at. The
   point of the Verification below is that the break is found without opening it.

   > **Two business dates, and the reason is the finding this task produced before a line of it was
   > written.** `batch/recon` counts a posting towards what the master ought to hold when its
   > reference is in the movement file **or** its value date is earlier than the business date -
   > `ledger.py:146`, which is [ADR 0015](../../governance/adr/0015-the-cut-off-is-the-movement-file.md)
   > in SQL. A transfer this fault blocks reaches neither: it is not in the file, because the fault is
   > what stopped it getting there, and its value date is the day being reconciled rather than an
   > earlier one. **So on the day it happens the reconciliation passes.** The bank is short and the
   > control that exists to say so is, correctly by its own rules, silent. On D+1 those same postings
   > are dated earlier than the business date, enter the expected set, and the master still does not
   > hold them - so the break surfaces a full cycle late, as `VALUE_DRIFT`. The exercise therefore
   > drives **D and D+1** and the first reconciliation passing is evidence rather than a failed
   > attempt. It is also a finding in its own right, and independent of this exercise: **the
   > reconciliation is blind for one cycle to anything that stops a posting reaching the movement
   > file**, which is the class of fault the ESB hop can produce at any time.

4. **Work the incident as documented.** Detection from the reconciliation report and
   `legacy/backoffice`, which is where an operator reads it - not from the adapter's log, and not
   from the envelope. Then classification against the severity model written in task 2, triage,
   containment, resolution, and confirmation that the recovery worked. Every step recorded as it
   happened, with timestamps, including the steps that went nowhere. **A response that reads as a
   straight line is a response that was written afterwards.**

5. **The RCA, from what actually happened.** Against the template task 2 produced, and honest about
   the process as well as the code: which procedure step was unusable, what the responder reached for
   that did not exist, how long detection actually took, and what the estate could not tell them. The
   Constraint is explicit that a sanitised RCA teaches nothing and would undermine every other
   document here.

6. **The gaps, and the write-up.** Everything the exercise exposed is fixed where it belongs to this
   package - `incident-management.md` itself, the runbooks - or logged in `STATUS.md` where it does
   not. Then the reversal is verified the way the Constraint requires: the fault removed, the estate
   driven again, and **zero drift confirmed by the same reconciliation that found the break**.

### WP-18b - the documentation pass, and the control that keeps it true

Branch `docs/TB-1018-documentation-pass`. Six tasks. Depends on 18a, because two of these documents
carry what the exercise produced.

1. Set 18b `In progress` and branch from up-to-date `main`.

2. **The five remaining stubs this package owns.** `ways-of-working/sdlc.md`,
   `ways-of-working/change-management.md`, `ways-of-working/environments.md`,
   `consuming-this-repo.md` and `compliance/dora-control-map.md`. Each written from what this
   repository genuinely does - `change-management.md` in particular describes a control this estate
   actually operates, and the pull request template is already its artefact. Where a document would
   have to describe something the repository does not have, it says so; **inventing coverage is the
   failure mode `dora-control-map.md`'s own outline warns about.**

3. **The four stubs orphaned by packages that are `Done`.** `compliance/psd2-notes.md` (WP-12),
   `governance/tech-radar.md` and `ways-of-working/dependency-policy.md` (WP-02), and
   `ways-of-working/test-strategy.md` (WP-06). Filled here per the fourth decision above, with the
   orphaning recorded as **F-17**'s latest occurrence rather than silently absorbed.

4. **The control, not just the correction.** A checker under `quality/`, Python 3 standard library
   like everything else in this repository that needs no install, wired into `make lint` as its own
   target. It asserts two things over every markdown file in the repository: **every internal link
   resolves**, and **no `> **STUB.**` marker remains**. That turns two boxes of this package's own
   Definition of Done from assertions into something a build fails on, which is what **F-17** has
   been asking for since WP-01 and what four packages closing over their own stubs shows is needed.
   It is written **test-first against the tree as it stands**, so it fails before task 2 and 3 land
   and passes after.

5. **The matrix completes, and WP-01's requirements get the section they never had.**
   `REQ-GOV-006`, `REQ-OPS-005` and `REQ-DORA-001` are this package's own. `REQ-GOV-001` to
   `REQ-GOV-005` are WP-01's and it merged without a section for them - they are resolved here,
   under a `WP-01` heading, against the artefacts that have existed since the first package.
   `docs/compliance/traceability-matrix.md` then resolves **all 68** ids, which is what this
   package's Definition of Done means by complete.

6. **The final pass and the Verification below.** Every internal link, every stub marker, the DORA
   control map resolving to artefacts that exist, and the walkthrough from `master-plan.md` run end
   to end.

## Definition of Done

The half that satisfies each box is named, because two pull requests cannot each tick all seven.

- [x] A fault was injected, detected through normal means, and worked through the documented process. *(18a)*
- [x] An RCA exists describing what actually happened, including process failures. *(18a)*
- [x] Every gap found has been fixed or logged as a tracked follow-up. *(18a for what the exercise
      exposed, 18b for what the documentation pass does)*
- [x] No stub documents remain in `docs/`. *(18a fills `incident-management.md` because the exercise
      cannot follow a stub; 18b fills the other nine, and 18b's checker is what makes the box
      checkable rather than asserted)*
- [x] The traceability matrix resolves every requirement to code and a test. *(18b - all 68,
      including the five `REQ-GOV-*` WP-01 left unresolved)*
- [x] The DORA control map resolves every entry to an artefact that exists. *(18b)*
- [x] Every internal markdown link resolves. *(18b, enforced by the checker rather than claimed)*

## Verification

```bash
bash workload/scripts/incident-exercise.sh          # the fault, the day, the cycle, the break  (18a)
make lint                                           # including the new docs checker             (18b)
make test                                           # every tier still green                     (both)
```

**The exercise is its own verification, and the control is that the envelope stays sealed.** The
break must be found through the reconciliation report and `legacy/backoffice` rather than by reading
what the injector planted; the capture commits the envelope and the response side by side so the
order they were written in is checkable. Then the fault is removed, the estate driven again, and
**zero drift confirmed by the same reconciliation that found the break** - a recovery asserted
without re-running the control is a recovery nobody measured.

Finally the full end-to-end walkthrough from `master-plan.md`, and the checker over the whole tree:
no `> **STUB.**` marker anywhere under `docs/`, and every internal link resolving.

Real output into the pull request, never expected output.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-OPS-005 The incident process is exercised, not merely documented | the exercise and RCA (18a) |
| REQ-DORA-001 Operational resilience is tested, not assumed | deliberate fault injection (18a) |
| REQ-GOV-006 Documentation is complete and traceable | the documentation pass and the checker that keeps it true (18b) |

WP-18b also resolves **REQ-GOV-001 to REQ-GOV-005**, which are WP-01's and which WP-01 merged without
a matrix section for. They are listed here because this package is where they come to be resolved,
not because ownership moves.
