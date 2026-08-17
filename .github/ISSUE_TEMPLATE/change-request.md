---
name: Change request
about: Propose a change to the estate. Every change starts here and gets a ticket ID.
title: '[TB-XXXX] '
labels: change-request
---

## Request

**Requested by:**
**Business justification:**

<!-- Why this change is needed. If it comes from a work package, name it. -->

## Proposed change

<!-- What should change. Enough detail that someone else could scope it. -->

**Components affected:**
**Stratum:** 0 (mainframe) | 1 (legacy) | 2 (integration) | 3 (services) | 4 (edge) | cross-cutting

## Risk classification

**Proposed class:** standard | normal | major | emergency

- [ ] Touches money movement
- [ ] Touches authentication or authorisation
- [ ] Touches personal data
- [ ] Requires a database migration
- [ ] Changes a published contract (breaking or non-breaking - say which)
- [ ] Would require a version change in strata 0-2 <!-- if ticked, an ADR is mandatory -->

Any box ticked makes this at least a **normal** change. Money movement or a breaking contract change
makes it **major**, requiring architecture review before implementation.

## Acceptance criteria

<!-- How we will know this is done. Written so they can become tests. -->

- [ ]
- [ ]

## Rollback

**How this would be reversed:**

## Notes

<!-- Dependencies on other work, freeze-window constraints, anything a reviewer needs to know. -->
