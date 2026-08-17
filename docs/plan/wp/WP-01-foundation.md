# WP-01 - Repository foundation

| | |
|---|---|
| **Ticket** | TB-1001 |
| **Branch** | `feat/TB-1001-foundation` (not used - repository predates `git init`, see F-01) |
| **Stratum** | n/a - repository-wide |
| **Depends on** | - |
| **Status** | `Done` |

## Objective

Establish the repository so that it is self-describing and self-executing: a directory skeleton whose
shape communicates the estate, a planning system that any session can resume from, the governance
documentation that makes the regulated framing credible, and a Claude Code configuration that
mechanises the rules rather than trusting them to be remembered. After this package, a session with
no prior context can read two files and know both what the project is and what to do next.

## In scope

- Directory skeleton organised by era, with a `README.md` in every code directory stating its
  stratum, vintage, contents and owning work package.
- Root documents: `README.md`, `CLAUDE.md`, `SECURITY.md`, `Makefile`, `.gitignore`, `.editorconfig`.
- The planning system: `docs/plan/{README,master-plan,PROTOCOL,STATUS}.md` and all 18 work-package
  files.
- Governance and compliance documentation tree. Documents that make the repository self-describing
  written in full; the rest as short stubs naming their purpose and owning package.
- `.github/`: `CODEOWNERS`, pull request template, issue templates (change request, incident,
  problem).
- `.claude/`: `settings.json` with a permission allowlist and two hooks, plus the `/work-package`
  skill.

## Out of scope

- **Any application source code.** Not one line, in any language.
- Build manifests: `pom.xml`, `build.gradle.kts`, `go.mod`, `pyproject.toml`, `package.json`.
- Linter rule files in `quality/` - each lands with the package that first needs it (F-03).
- `git init`, any commit, any remote, any pull request (F-01).
- Full text of the stubbed governance documents - each is filled by a later package.

## Constraints

- No Dockerfile, Compose file, Kubernetes manifest, Terraform or CI workflow. ADR 0001.
- Directory naming is era-based, not role-based, so the strata are visible in the tree itself.
- `Makefile` targets must be honest: exit 0 and say nothing is built yet, rather than pretending.
- Every claim in the documentation must be traceable to something that exists in the repository.

## Tasks

1. Create the directory skeleton.
2. Write the root documents: `README.md`, `CLAUDE.md`, `SECURITY.md`, `Makefile`, `.gitignore`,
   `.editorconfig`.
3. Write the planning system: `master-plan.md`, `PROTOCOL.md`, `STATUS.md`, `docs/plan/README.md`.
4. Write the 18 work-package files - this one in full, the other 17 framed.
5. Write the governance and compliance tree, in full or stubbed per the plan.
6. Write ADRs 0001 and 0002, and the ADR process README.
7. Write `.github/` templates and `CODEOWNERS`.
8. Write `.claude/settings.json` and the `/work-package` skill.
9. Write a `README.md` into every code directory.
10. Run the verification below and record the outcome in `STATUS.md`.

## Definition of Done

- [x] Every directory contains at least one file - no empty directories.
- [x] `make build`, `make test` and `make lint` exit 0 with an honest message.
- [x] `STATUS.md` lists all 18 packages, WP-01 `Done`, WP-02 identified as next actionable.
- [x] Every work-package file carries all nine sections.
- [x] Every internal link in `README.md`, `master-plan.md` and `PROTOCOL.md` resolves.
- [x] `.claude/settings.json` is valid JSON.
- [x] No application source code, build manifest, Dockerfile or CI workflow exists anywhere.
- [x] Reading `README.md` then `docs/plan/STATUS.md` is sufficient to resume work cold.

## Verification

```bash
# No empty directories
find . -type d -empty

# Build targets are honest
make build && make test && make lint

# Settings parse
python3 -m json.tool .claude/settings.json > /dev/null && echo "settings.json valid"

# No source code, build manifests, containers or CI slipped in
find . \( -name '*.java' -o -name '*.go' -o -name '*.py' -o -name '*.ts' -o -name '*.tsx' \
       -o -name '*.cbl' -o -name 'pom.xml' -o -name 'build.gradle*' -o -name 'go.mod' \
       -o -name 'pyproject.toml' -o -name 'package.json' -o -name 'Dockerfile' \) -print

# Every internal markdown link resolves
grep -rhoE '\]\(([^)#]+\.md)\)' --include='*.md' . | sed -E 's/.*\((.*)\)/\1/' | sort -u
```

Expected: the first, fourth and any broken-link results are empty; the build targets print the
"nothing to build yet" message and exit 0; `settings.json valid` is printed.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-GOV-001 The repository states its purpose and boundaries | `README.md`, ADR 0001 |
| REQ-GOV-002 Deliberate technical debt is registered, not hidden | `SECURITY.md`, `docs/technical-debt.md`, ADR 0002 |
| REQ-GOV-003 Work is planned in the repository and resumable cold | `docs/plan/` |
| REQ-GOV-004 Execution rules are binding and machine-readable | `CLAUDE.md`, `PROTOCOL.md`, `.claude/` |
| REQ-GOV-005 Controls not enforced are registered as exceptions | `docs/ways-of-working/control-exceptions.md` |

## Outcome

Completed 2026-08-17. Three follow-ups raised: F-01 (`git init` deferred to the repository owner),
F-02 (packages 02-18 carry frame only), F-03 (`quality/` rule files land with the code they check).

No pull request exists because the repository predates `git init`. This is the only work package that
will bypass the branch and PR flow; from WP-02 onward `PROTOCOL.md` applies in full.
