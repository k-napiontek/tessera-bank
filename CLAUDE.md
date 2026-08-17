# CLAUDE.md - Tessera Bank

Binding standards for any AI session working in this repository. These override default behaviour.

## Read this before doing anything

1. [`docs/plan/STATUS.md`](docs/plan/STATUS.md) - what is done, what is in progress, what is next.
2. [`docs/plan/master-plan.md`](docs/plan/master-plan.md) - what this project is and why.
3. [`docs/plan/PROTOCOL.md`](docs/plan/PROTOCOL.md) - how work is executed here. Binding.

Never start work that is not the next actionable work package. Use `/work-package <ID>`.

## The rule that protects this repository

> **Do not upgrade the legacy strata.**
>
> Java 8, Spring Boot 2.7.18, Tomcat 8.5, JAX-WS SOAP, COBOL-85 and the Oracle SQL dialect in
> `mainframe/`, `legacy/` and `integration/` are **deliberate and load-bearing**. They reproduce what
> the banking industry actually runs, and they are the entire reason this repository exists.
>
> Every linter, scanner, dependency bot and AI assistant will want to modernise them. Doing so
> destroys the purpose of the project.
>
> Any version change to strata 0, 1 or 2 requires an explicit instruction from the repository owner
> **and** an ADR recording the decision. There are no exceptions, including "while I was in there
> anyway" and "the scanner flagged it".

Deliberate debt is tracked in [`docs/technical-debt.md`](docs/technical-debt.md), not silently fixed.

## Which stratum am I in?

Check before writing a line. The pinned stack differs per directory and mixing them is a defect.

| Directory | Stratum | Vintage | Pinned stack |
|---|---|---|---|
| `mainframe/` | 0 | ~1995 | COBOL-85 (GnuCOBOL), JCL, fixed-width records, COMP-3 packed decimal |
| `legacy/` | 1 | ~2011 | Java 8, Servlet 3.0 / JSP, JAX-WS, Maven 3, WAR on Tomcat 8.5, Oracle dialect |
| `integration/` | 2 | ~2019 | Java 8, Spring Boot 2.7.18, Spring Integration, JMS, XSLT |
| `services/` | 3 | ~2023 | Java 17, Spring Boot 3.2, PostgreSQL, Flyway, Kafka, Gradle |
| `edge/` | 4 | ~2025 | Go 1.22+, Python 3.12 (uv), TypeScript + React + Vite |
| `batch/` | - | mixed | Python 3.12 for reporting; reconciliation spans strata 0 and 3 |

## Language

Everything that lands in this repository is English: code, identifiers, comments, docstrings, tests,
documentation, commit messages, PR titles and bodies. No exceptions.

## Git

- **Never commit to `main`.** Every change starts on a branch named `type/TB-XXXX-short-description`,
  using the same type as the commit: `feat/TB-1007-ledger-persistence`.
- Work lands through a pull request into `main`, using the template.
- Conventional Commits with the change ticket appended for traceability:

  ```
  feat(ledger): add idempotency key handling [TB-1008]
  fix(esb): correct COMP-3 sign nibble encoding [TB-1011]
  docs(adr): record SOAP interface decision [TB-1010]
  ```

  Types: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert.
  Subject line only, no body. Under 72 characters, imperative mood, lowercase, no trailing period.

- **Commit sizing.** One commit is one logical change that builds and passes its tests on its own.
  Target under ~400 lines of diff; hard ceiling ~800, and only for generated code or data fixtures.
  Test and implementation for the same behaviour belong in the same commit. Never mix a refactor with
  a behaviour change. No `wip` or `fix typo` commits survive into a pull request.

## Attribution

Never add yourself as author or co-author. No `Co-Authored-By` trailer, no "Generated with" footer,
no Claude or Anthropic credit in commits, PR bodies, issues or comments.

## Engineering standards

- **Test-driven.** Failing test first, then implementation, then refactor. This is not optional.
- **Contracts are the source of truth.** The artefacts in `contracts/` define every interface.
  Implementation follows the contract; a contract test enforces it. Never let them drift.
- **Definition of Done** applies to every change: see
  [`docs/ways-of-working/definition-of-done.md`](docs/ways-of-working/definition-of-done.md).
- **Stay in scope.** Never touch files outside the declared scope of the current work package.
  Anything else you discover is logged as a follow-up in `STATUS.md`, not fixed on this branch.
- **Money is never a floating-point number.** Minor units plus an ISO 4217 currency code, with the
  scale resolved per currency.

## Data protection

- **No personal data anywhere.** Not in code, tests, fixtures, logs, sample files or documentation.
  All data in this repository is synthetic and generated.
- Never log account holder names, national identifiers, addresses, card numbers or authentication
  material. Log account references and correlation ids.
- Test data comes from the synthetic generators, never from anything resembling a real customer.
- See [`docs/ways-of-working/data-classification.md`](docs/ways-of-working/data-classification.md).

## What does not belong in this repository

No Dockerfile, no Compose file, no Kubernetes manifest, no Helm chart, no Terraform, no CI workflow
YAML. This repository is application source and governance configuration only; packaging and
deployment belong to the companion platform repositories.
See [ADR 0001](docs/governance/adr/0001-source-only-repository.md).

If a task appears to require one of these, stop and ask rather than adding it.

## When to stop and ask

Halt and raise it rather than improvising when:

- verification fails and the fix lies outside the current work package's scope;
- the work would require upgrading a pinned legacy version;
- a work package contradicts the master plan;
- a dependency work package is not `Done`;
- the change would touch personal data or authentication in a way the package does not describe.
