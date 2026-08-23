# quality - shared linter and gate configuration

**Cross-cutting** | **Built by each service package**

Shared static-analysis and quality-gate configuration, declared here and executed by the companion
platform repositories. See [ADR 0001](../docs/governance/adr/0001-source-only-repository.md) for why
the execution lives elsewhere.

## What is here

| File | What it does | Run by |
|---|---|---|
| `docs-check.py` | Fails on a broken internal markdown link, a surviving `> **STUB.**` marker, or a `REQ-*` id that is in no catalogue | `make lint-docs`, and `make lint` |
| `test-docs-check.py` | The checker's own tests, against fixture trees rather than against this repository | `make test-quality`, and `make test` |

**The documentation checker is a control rather than a convenience.** Four work packages merged over
their own stub documents and nothing noticed, which is follow-up F-17 - a document that no Definition
of Done covers is a document nothing checks. Two boxes of WP-18's Definition of Done - *no stub
documents remain* and *every internal markdown link resolves* - are now things a build fails on
rather than things a session asserts.

It is Python 3 standard library, like everything else in this repository that needs no install, and
it takes an optional root so it can be pointed at a worktree of another commit.

## Planned contents

Checkstyle, Spotless and PMD rulesets (JVM), `.golangci.yml` (Go), `ruff.toml` (Python), ESLint
configuration (TypeScript), and `sonar-project.properties` for the coverage and duplication
thresholds.

**None of those exists yet, by design.** Each rule file lands with the work package that first needs
it, so the rules arrive together with code to check - see follow-up F-03 in
[`../docs/plan/STATUS.md`](../docs/plan/STATUS.md). Until then there is **no coverage threshold and
no software composition analysis anywhere in this repository**, which
[`../docs/ways-of-working/test-strategy.md`](../docs/ways-of-working/test-strategy.md) and
[`../docs/compliance/dora-control-map.md`](../docs/compliance/dora-control-map.md) both state plainly
rather than implying otherwise.
