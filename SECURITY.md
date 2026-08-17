# Security policy

## This repository intentionally contains end-of-life software

**Tessera Bank is an educational and portfolio project. It is not fit for production use.**

Parts of this repository are deliberately pinned to unsupported versions - Java 8, Spring Boot
2.7.18, Tomcat 8.5, JAX-WS SOAP - in order to reproduce the technology estate that banks genuinely
operate. Software Composition Analysis will report known CVEs against these components. That is
expected and intended.

The debt is not hidden. It is registered as accepted risk, with a named owner, a compensating control
and a review date, in:

- [`docs/technical-debt.md`](docs/technical-debt.md) - the accepted-risk register
- [ADR 0002](docs/governance/adr/0002-deliberate-legacy-strata.md) - the decision and its reasoning

**Do not deploy this code.** Do not copy its legacy strata into a real system. If you want the
patterns without the vintage, take stratum 3 (`services/`) only.

## Reporting a genuine vulnerability

A "genuine" vulnerability here means a defect in code written for this project - a logic flaw in the
ledger, an injection path, a broken authorisation check - as opposed to a known CVE in a component
that is deliberately out of date.

Open a GitHub issue using the **incident** template. Because this repository holds no real data,
serves no users and processes no money, there is no need for private disclosure.

## Data

All data in this repository is synthetic and generated. There is no personal data of any kind, and
none may be added - see [`docs/ways-of-working/data-classification.md`](docs/ways-of-working/data-classification.md).

Tessera Bank is fictional. Any resemblance to a real institution is unintended.
