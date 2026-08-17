# WP-18 - Incident exercise and documentation pass

| | |
|---|---|
| **Ticket** | TB-1018 |
| **Branch** | `feat/TB-1018-incident-exercise` |
| **Stratum** | n/a - repository-wide |
| **Depends on** | WP-16 |
| **Status** | `Not started` |

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

To be detailed before execution.

## Definition of Done

- [ ] A fault was injected, detected through normal means, and worked through the documented process.
- [ ] An RCA exists describing what actually happened, including process failures.
- [ ] Every gap found has been fixed or logged as a tracked follow-up.
- [ ] No stub documents remain in `docs/`.
- [ ] The traceability matrix resolves every requirement to code and a test.
- [ ] The DORA control map resolves every entry to an artefact that exists.
- [ ] Every internal markdown link resolves.

## Verification

The exercise is its own verification: the break must be found through the reconciliation report and
the back office rather than by knowing where it was planted. Then run the full end-to-end walkthrough
from `master-plan.md` and confirm zero drift after the fault is removed. Finally, run the link check
and confirm no stub markers remain anywhere under `docs/`.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-OPS-005 The incident process is exercised, not merely documented | the exercise and RCA |
| REQ-DORA-001 Operational resilience is tested, not assumed | deliberate fault injection |
| REQ-GOV-006 Documentation is complete and traceable | final documentation pass |
