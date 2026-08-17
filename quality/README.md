# quality - shared linter and gate configuration

**Cross-cutting** | **Built by each service package**

Shared static-analysis and quality-gate configuration, declared here and executed by the companion platform repositories. See [ADR 0001](../docs/governance/adr/0001-source-only-repository.md) for why the execution lives elsewhere.

## Planned contents

Checkstyle, Spotless and PMD rulesets (JVM), `.golangci.yml` (Go), `ruff.toml` (Python), ESLint
configuration (TypeScript), and `sonar-project.properties` for the coverage and duplication
thresholds.

**Currently empty by design.** Each rule file lands with the work package that first needs it, so
the rules arrive together with code to check - see follow-up F-03 in
[`../docs/plan/STATUS.md`](../docs/plan/STATUS.md).

