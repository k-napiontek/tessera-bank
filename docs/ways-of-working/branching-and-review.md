# Branching and review

## Branching model

Trunk-based with short-lived branches. `main` is always releasable.

```
main ────●────────●────────●────────●───────>
          \      /  \     /  \     /
           ●────●    ●───●    ●───●
        feat/TB-1006  feat/TB-1007  feat/TB-1008
```

- **Never commit directly to `main`.** This is enforced by a `PreToolUse` hook in
  [`../../.claude/settings.json`](../../.claude/settings.json) once the repository is initialised.
- One branch per work package. Name it `type/TB-XXXX-short-description`, using the same type as the
  commits: `feat/TB-1007-ledger-persistence`, `fix/TB-1042-comp3-sign-nibble`.
- Branch from up-to-date `main`. Rebase rather than merge `main` into the branch, so history stays
  linear and reviewable.
- Delete the branch after merge.

## Commits

```
feat(ledger): add idempotency key handling [TB-1008]
fix(esb): correct COMP-3 sign nibble encoding [TB-1011]
docs(adr): record SOAP interface decision [TB-1010]
```

Conventional Commits with the change ticket appended. Types: feat, fix, docs, style, refactor, perf,
test, build, ci, chore, revert. Subject line only, no body, under 72 characters, imperative mood,
lowercase, no trailing period.

The ticket ID is not decoration. Traceability from a line of code back to the change that authorised
it is what an auditor samples for, and a commit log without it cannot answer the question.

### Sizing

One commit is one logical change that builds and passes its tests on its own.

| Rule | Value |
|---|---|
| Target diff size | under ~400 lines |
| Hard ceiling | ~800 lines, generated code or fixtures only |
| Commits per work package | roughly 3 to 10 |

Test and implementation for the same behaviour belong in the same commit. Never mix a refactor with a
behaviour change - a reviewer cannot tell which of the two introduced a defect. No `wip`, `fixes` or
`address review` commits survive into a pull request.

Review quality collapses as change size grows, which is the practical reason for the limits: a
400-line change gets read, a 4,000-line change gets approved.

## Pull requests

Every change reaches `main` through a pull request using
[the template](../../.github/pull_request_template.md), with the change record completed: what
changed, why, risk classification, test evidence, rollback plan.

Verification evidence must be **real output from commands actually run**. Pasting expected output is
falsifying a control record, and it is the single easiest thing for a reviewer to catch.

## Review

The target control is **four-eyes**: the author never approves and never merges their own change.
This is standard maker-checker segregation of duties, and it is what a regulated institution
requires.

**In this repository that control is not enforced** - the AI both authors and merges. That gap is
registered, with its compensating controls, in
[`control-exceptions.md`](control-exceptions.md). It is registered rather than quietly claimed
because claiming a control you do not enforce is what fails an audit.

## Merging

- Merge only when every verification in the work package has passed. A red package is not a finished
  package.
- Squash or rebase - never a merge commit. History stays linear.
- Delete the branch.
- Update [`../plan/STATUS.md`](../plan/STATUS.md) with the status, PR link and merge SHA.

## Branch protection to apply

This repository declares its rules; the platform repositories enforce them. When `main` exists,
apply:

- Require a pull request before merging.
- Require status checks to pass.
- Require linear history.
- Require conversation resolution before merging.
- Block force pushes and deletions on `main`.
