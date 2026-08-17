---
name: Incident
about: Something is broken now. Raise first, investigate second.
title: '[INC-XXXX] '
labels: incident
---

## Summary

<!-- One or two sentences. What is broken, observed from the outside. -->

## Severity

**Proposed:** P1 | P2 | P3 | P4

| | |
|---|---|
| **P1** | Money movement is incorrect, or data has been lost. Escalate immediately. |
| **P2** | A core service is unavailable, or a batch cycle has failed. |
| **P3** | Degraded function with a workaround. |
| **P4** | Minor, no customer or financial impact. |

**Money movement affected?** yes / no
**Data loss or corruption suspected?** yes / no
**Personal data potentially exposed?** yes / no
<!-- If yes to the last, DORA and GDPR notification clocks may start. Escalate before investigating. -->

## Detection

**How was this found?** reconciliation break | alert | operator report | test failure | other
**First observed:**
**Correlation id / reference:**

## Impact

**Components affected:**
**Customer impact:**
**Financial impact:**

## Timeline

<!-- Append as you go. Times, not adjectives. -->

| Time | Event |
|---|---|
| | |

## Containment

<!-- What was done to stop it getting worse, before anyone understood why it happened. -->

## Current status

open | contained | resolved | closed

---

Worked according to [`docs/ways-of-working/incident-management.md`](../../docs/ways-of-working/incident-management.md).
A P1 or P2 requires a root cause analysis before closure. The RCA is blameless and honest about
process failures as well as technical ones - a sanitised RCA teaches nothing.
