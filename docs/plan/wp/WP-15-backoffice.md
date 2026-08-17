# WP-15 - backoffice

| | |
|---|---|
| **Ticket** | TB-1015 |
| **Branch** | `feat/TB-1015-backoffice` |
| **Stratum** | 1 - JSP + jQuery, ~2011 |
| **Depends on** | WP-10 |
| **Status** | `Not started` |

## Objective

The internal operations screen, built the way internal bank tools genuinely are: JSP and jQuery,
server-rendered, unfashionable, and still running fifteen years later because it works and nobody
will fund replacing it. Operators use it to read the reconciliation report and work the rejects from
the overnight cycle.

## In scope

- Server-rendered JSP pages inside the `customer-master` WAR.
- Reconciliation break list with drill-down to the underlying records.
- Rejects queue from the overnight cycle, with a reason per record.
- Basic operator actions: acknowledge a break, annotate a reject.
- jQuery for the interactive parts, as a 2011 team would have used it.

## Out of scope

- Any modern frontend framework, build step or bundler. Deliberately.
- Direct database access - the pages call the existing service layer.
- Authorisation model beyond a simple operator role.

## Constraints

- **JSP and jQuery only.** No React, no TypeScript, no npm. This screen exists to demonstrate that
  the estate contains genuinely different eras, and modernising it destroys that.
- Server-rendered. No single-page application behaviour.
- Every operator action writes to the audit trail. An internal tool that mutates state without an
  audit record is exactly the finding an auditor writes up.
- Styling should look its age. Do not make it pretty.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] Reconciliation breaks and rejects are listed with drill-down.
- [ ] Operator actions are recorded in the audit trail with the acting user.
- [ ] The pages render inside the existing WAR on Tomcat 8.5.
- [ ] No modern frontend tooling has been introduced.

## Verification

Deploy the WAR, log in as an operator, and confirm: a seeded reconciliation break appears and can be
drilled into; a reject can be annotated; both actions appear in the audit trail with the correct
actor and timestamp.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-OPS-003 Operators can see and work reconciliation breaks | break list |
| REQ-OPS-004 Operator actions are attributable and audited | audit integration |
| REQ-EST-002 The estate contains genuinely different UI eras | JSP + jQuery |
