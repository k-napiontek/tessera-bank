# Architecture decision records

An ADR records a decision that was hard, contested, or expensive to reverse - together with the
context that made it correct at the time. It is not documentation of how the system works; it is
documentation of **why it is the way it is**, written so that a future engineer can tell the
difference between a deliberate choice and an accident.

## When to write one

Write an ADR when the decision:

- constrains future work (a framework, a persistence strategy, a protocol);
- would otherwise look like a mistake to someone arriving later;
- was contested, or had a serious alternative that was rejected;
- accepts a known risk or a known piece of debt.

Do **not** write one for choices with an obvious default and no trade-off. An ADR set diluted by
trivia stops being read, and an ADR nobody reads has no value.

## Format

```markdown
# ADR NNNN - Title in the imperative

**Status:** Proposed | Accepted | Superseded by ADR-NNNN | Deprecated
**Date:** YYYY-MM-DD
**Deciders:** who

## Context
What forces are in play. What makes this decision necessary and non-obvious.

## Decision
What we are doing. Stated plainly, in the active voice.

## Consequences
What becomes easier, what becomes harder, what we are now committed to.

## Alternatives considered
What else was on the table and why it lost.
```

Numbering is sequential and never reused. Superseded ADRs are **never deleted or edited** - the
record of a decision that was later reversed is often more useful than the decision that replaced it.
Mark the old one superseded and link forward.

## Back-dated ADRs

This repository reproduces a bank's estate across four decades, and some decisions belong to their
era rather than to today. Those ADRs are written **in the idiom and with the reasoning of their own
year**, and are marked as such.

An ADR from 2011 choosing SOAP for the customer-master interface should argue the 2011 case honestly
- WS-Security, tooling maturity, contract-first design, enterprise interoperability - because those
arguments were correct in 2011. Judging that decision by 2026 standards would misrepresent both the
decision and the engineers who made it, and would teach the wrong lesson about legacy systems: they
are usually the residue of good decisions in a different context, not of incompetence.

Back-dated ADRs carry a note identifying them as historical reconstructions, so nobody mistakes the
date for a real one.

## Index

| ADR | Title | Status | Date |
|---|---|---|---|
| [0001](0001-source-only-repository.md) | Application source only, no deployment artefacts | Accepted | 2026-08-17 |
| [0002](0002-deliberate-legacy-strata.md) | Pin legacy strata to end-of-life versions | Accepted | 2026-08-17 |
| [0003](0003-cursor-paged-statements.md) | Page the statement with an opaque keyset cursor | Accepted | 2026-08-19 |
