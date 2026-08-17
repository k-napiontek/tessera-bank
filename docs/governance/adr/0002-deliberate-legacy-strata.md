# ADR 0002 - Pin legacy strata to end-of-life versions

**Status:** Accepted
**Date:** 2026-08-17
**Deciders:** Karol Napiontek

## Context

The first draft of this project assumed current versions everywhere - the newest JDK, the newest
Spring Boot, one uniform modern stack. That is what a greenfield project looks like, and it is not
what a bank looks like.

The evidence is unambiguous:

- Roughly **90%** of US banking core software is legacy, typically COBOL or PL/SQL running overnight
  batch on a mainframe.
- **33%** of banking, insurance and financial services organisations still run **Tomcat 8.5**, which
  is out of community support.
- **Spring Boot 2.x** was the last line to support **Java 8**, so those organisations are pinned to
  Java 8 + Spring Boot 2.7 + Tomcat 8.5 as a single block. Upgrading one component forces upgrading
  all of them - and, underneath, the stored-procedure layer as well. That is a funded programme, not
  a ticket, which is precisely why it does not happen.

A repository that models a bank without modelling this models the marketing diagram, not the estate.
The version lag *is* the subject matter: it is what makes dependency remediation, migration
strategy, strangler-fig modernisation and risk acceptance into real problems rather than exercises.

Against that: this is a public repository. Pinning to end-of-life versions means Software Composition
Analysis will report genuine CVEs, and a reader who does not understand the intent may conclude the
author is careless.

## Decision

Strata 0, 1 and 2 are **deliberately pinned to end-of-life technology**:

| Stratum | Pinned to | Vintage |
|---|---|---|
| 0 | COBOL-85, fixed-width files, COMP-3 packed decimal, JCL | ~1995 |
| 1 | Java 8, Servlet 3.0/JSP, JAX-WS SOAP, Maven 3, Tomcat 8.5, Oracle dialect | ~2011 |
| 2 | Java 8, Spring Boot 2.7.18, Spring Integration, JMS, XSLT | ~2019 |

Stratum 3 is Java 17 and Spring Boot 3.2 - modern, but deliberately a few years behind current,
because that is where the modern tier of a real bank sits.

Every pinned component is registered as **accepted risk** in
[`../../technical-debt.md`](../../technical-debt.md), with an owner, a compensating control and a
review date.

**No version in strata 0, 1 or 2 may be upgraded** without an explicit instruction from the
repository owner and an ADR superseding this one. This rule is stated in
[`../../../CLAUDE.md`](../../../CLAUDE.md) and reinforced by a `PreToolUse` hook blocking edits to
build manifests in those directories.

## Consequences

**Easier.** The repository teaches what it claims to teach. Dependency remediation, version-lag
analysis, migration planning and risk acceptance all have real material to work on. The estate is
recognisable to anyone who has worked in a bank, which matters for a portfolio piece.

**Harder.** Development in strata 1 and 2 is genuinely less pleasant - no records, no `var`, no text
blocks, older tooling. That is representative. It is also the point: writing Java 8 after Java 17
teaches more about why organisations struggle to upgrade than any amount of reading.

**Risk accepted.** SCA will report CVEs against these components continuously. This is expected
output, not a defect. `SECURITY.md` states plainly that the code must not be deployed.

**The main threat is well-meant repair.** Every linter, dependency bot and AI assistant will attempt
to modernise these strata, and each attempt silently destroys the premise. Hence the explicit rule,
its restatement in `CLAUDE.md`, and the hook.

## Alternatives considered

**Current versions everywhere.** Clean scans, comfortable development, and an unrepresentative
project that teaches nothing about the constraint that dominates real banking engineering. Rejected -
this was the first draft, and rejecting it is why this ADR exists.

**Old-looking but supported.** Java 17 and Spring Boot 3.x throughout, written in a dated idiom, so
the estate looks stratified while scanning clean. Rejected: the version block *is* the lesson, and
simulating it with supported versions removes the only part that cannot be faked.

**End-of-life plus a scheduled remediation programme in the repository.** Pin as above, but also plan
and execute the upgrades as later work packages. Deferred rather than rejected - it would be a strong
follow-on exercise, but doing it now would delete the legacy estate this project was built to model.
