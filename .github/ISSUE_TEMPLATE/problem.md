---
name: Problem
about: A known weakness or recurring fault. Not urgent, but real and worth tracking.
title: '[PRB-XXXX] '
labels: problem
---

<!--
Problem management is distinct from incident management. An incident is "it is broken now"; a
problem is "this will break again unless something changes". Separating them stops the second from
being quietly forgotten once the first is closed - which is how the same outage happens three times.
-->

## Problem

<!-- The underlying weakness, not its symptoms. -->

## Evidence

**Related incidents:**
**Frequency:**
**First observed:**

## Impact if left

<!-- What happens if nobody addresses this. Be specific about the failure mode. -->

## Known workaround

<!-- What is done today to live with it. -->

## Proposed resolution

<!-- What would actually fix it, and roughly what it would cost. -->

## Classification

- [ ] Accept as known debt - register it in [`docs/technical-debt.md`](../../docs/technical-debt.md)
      with an owner, a compensating control and a review date
- [ ] Fix - raise a change request
- [ ] Deliberate design decision - record an ADR and close

<!--
Note: the deliberately outdated components in strata 0-2 are NOT problems. They are accepted risks
recorded in docs/technical-debt.md and ADR 0002. Do not raise a problem for those.
-->
