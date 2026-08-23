# Software development lifecycle

The gates a change passes through here, what each one produces as evidence, and - the half that
matters - which of them this repository actually operates.

This is the document that answers an auditor asking *show me how a change gets to production*. The
honest answer has two parts. The gates below are real, and every one of them has an artefact that can
be opened and sampled. And three of them are **not enforced by anything except discipline**, which is
recorded in [`control-exceptions.md`](control-exceptions.md) rather than claimed here. A lifecycle
document that describes the gates a bank would operate, in a repository where one actor does
everything, is a claim its own commit history disproves.

| # | Gate | Requires | Evidence it produces | Operated here |
|---|---|---|---|---|
| 1 | Change request | A ticket id and a described change | The work package file, [`STATUS.md`](../plan/STATUS.md) | **Yes** |
| 2 | Design | An ADR if architecturally significant | [`../governance/adr/`](../governance/adr/README.md) | **Yes**, without the review board |
| 3 | Implementation | Branch, test-first, Definition of Done | The branch and its commits | **Yes** |
| 4 | Review | Four-eyes through CODEOWNERS | The pull request | **No** - CE-001 |
| 5 | Quality | Linters, tests, coverage, composition analysis | `make lint`, `make test` output | **Partly** - see below |
| 6 | Security review | Triggered by auth, money movement or personal data | The change record's answer | **Partly** - CE-002 |
| 7 | Release | Promotion up the environment ladder with sign-off | Nothing - this repository releases nothing | **No** - ADR 0001 |
| 8 | Post-implementation | Verification run for real, outcome recorded | The PR's evidence, `STATUS.md` | **Yes** |

---

## 1. Change request

**No work starts without a ticket.** A change here is a work package: a file under
[`../plan/wp/`](../plan/wp/) carrying a `TB-XXXX` ticket, an objective, an explicit *In scope* and
*Out of scope*, a task list, a Definition of Done and a Verification section. The backlog, the
dependency order and the status of every one of them is [`STATUS.md`](../plan/STATUS.md).

The ticket id then travels with the change - into the branch name, into every commit subject, into
the pull request title. **Traceability from a line of code back to the change that authorised it is
what an auditor samples for**, and a commit log without it cannot answer the question.

Risk is classified at this point, not at merge time, into standard, normal, major or emergency. The
classes and what each one requires are [`change-management.md`](change-management.md)'s subject.

## 2. Design gate

**Architecturally significant means an ADR.** The test is not size: it is whether the decision would
be expensive to reverse, constrains something built later, or would otherwise be re-litigated by
someone with no memory of why. Eighteen of them exist in
[`../governance/adr/`](../governance/adr/README.md), and the Definition of Done carries an ADR box
for exactly this reason.

A **major** change additionally requires architecture review before implementation. That review body
does not exist here - see CE-002 - so what happens instead is that the work package is detailed and
approved by the repository owner before execution, in its own change rather than inside the branch
that executes it. Three packages record precisely that in the decision log. It is weaker than a
review board and it is not nothing.

## 3. Implementation gate

Branch from up-to-date `main`, named `type/TB-XXXX-short-description`. Never commit to `main`; a
`PreToolUse` hook in [`../../.claude/settings.json`](../../.claude/settings.json) refuses it.

**Test-first is a gate, not a preference.** The failing test comes before the implementation, and a
task with no test is a task that is not done. What that means per tier - COBOL fixture comparison,
property-based tests over the ledger invariants, Testcontainers against real PostgreSQL, contract
tests - is [`test-strategy.md`](test-strategy.md).

Commit sizing is part of this gate rather than a style note: under ~400 lines, one logical change
each, individually green, roughly 3 to 10 per package. The reason is review quality, and it is stated
in [`branching-and-review.md`](branching-and-review.md#sizing): a 400-line change gets read, a
4,000-line change gets approved.

The gate closes with a self-check against
[`definition-of-done.md`](definition-of-done.md), whose honesty clause is the operative part: a box
that cannot be ticked means the change is not done, and saying so is the required behaviour.

## 4. Review gate

The target control is **maker-checker**: the author never approves and never merges their own change.
Ownership is expressed as code in [`../../.github/CODEOWNERS`](../../.github/CODEOWNERS), which
follows the strata because the skills follow the strata - the team that can safely review a Spring
Boot 3 change is usually not the team that can safely review a COBOL match-merge.

**This gate is not operated.** The AI authors the change and merges it once its own verification
passes; the team handles in `CODEOWNERS` are placeholders and the file has no effect (F-04). Both
facts are registered in
[`control-exceptions.md`](control-exceptions.md#ce-001---four-eyes-review-is-not-enforced) with the
four compensating controls that stand in for it, the strongest being that **work stops after every
package** and nothing proceeds without an explicit instruction. Review happens - after merge rather
than before it.

## 5. Quality gate

What actually runs:

| Check | Command | Covers |
|---|---|---|
| Contract validation | `bash contracts/validate.sh` | OpenAPI, AsyncAPI, WSDL/XSD well-formedness, copybook offsets, four cross-era JSON contracts |
| Static analysis | `make lint` | `gofmt` and `go vet`, `ruff` check and format, ESLint with type-aware rules |
| Documentation | `make lint` | Internal links, surviving stub markers, invented requirement ids |
| Tests | `make test` | Every tier that has something to run |

What does **not** run, stated rather than implied: there is **no coverage threshold anywhere in this
repository**, no software composition analysis, and no static analysis at all for the JVM and COBOL
tiers. [`../../quality/README.md`](../../quality/README.md) declares the rulesets a bank would
operate and F-03 records that each one lands with the package that first needs it. A quality gate
that names thresholds nothing measures is a gate in name only, which is the failure mode this
document is trying not to reproduce.

There is also no pipeline to hang any of it on: this repository has no CI configuration by design
([ADR 0001](../governance/adr/0001-source-only-repository.md)), so every check above is run by the
engineer, and the evidence is the output pasted into the change record.

## 6. Security review trigger

A security review is required when a change touches **authentication**, **money movement**, or
**personal data**. The [change record](../../.github/pull_request_template.md) asks the question
directly and a `yes` obliges the review before merge.

The trigger is real; the independent reviewer is not (CE-002). What compensates is that the three
trigger conditions are also the three the estate constrains hardest by construction: money movement
is double-entry with balancing invariants that a property-based test asserts, authentication happens
once at the edge and is pinned to one algorithm, and personal data is excluded by
[`data-classification.md`](data-classification.md) and mapped in
[`../compliance/gdpr-data-map.md`](../compliance/gdpr-data-map.md) - the ledger holds none at all, so
most of the estate is out of scope for it entirely.

## 7. Release gate

**This repository releases nothing.** It ships no Dockerfile, no Compose file, no Kubernetes
manifest and no pipeline ([ADR 0001](../governance/adr/0001-source-only-repository.md)); the
companion platform repositories consume it and deploy it. Promotion through the environment ladder,
what is tested at each rung and who signs off is specified for them in
[`environments.md`](environments.md), and what each tier needs at runtime is
[`../consuming-this-repo.md`](../consuming-this-repo.md).

Merging to `main` is therefore the last gate that exists here, and its only precondition is that
every verification in the work package passed. **A red package is not a finished package.**

## 8. Post-implementation verification

The verification commands in the work package are run for real and their **actual output** is pasted
into the pull request. Pasting expected output is falsifying a control record; it is also the easiest
thing in the world for a reviewer to catch.

After merge, [`STATUS.md`](../plan/STATUS.md) records the status, the PR link, the merge SHA and
every follow-up the package discovered but deliberately did not fix. That follow-up register is the
defect backlog: 114 entries at the time of writing, each naming the package that raised it. A finding
recorded and left open is honest; a finding fixed quietly on someone else's branch is how a
reviewable change becomes an unreviewable one.

---

## Where the evidence lives

| Question an auditor asks | Where it is answered |
|---|---|
| Was this change authorised? | The ticket id in every commit, the work package file |
| Was it designed before it was built? | The task list, detailed and approved before execution; the ADR if one was needed |
| Was it tested? | The test evidence section of the pull request, with real output |
| Who approved it? | The Approvals section - and CE-001, which says nobody independent did |
| Can it be reversed? | The Rollback section of the change record |
| Did it satisfy a stated requirement? | [`../compliance/traceability-matrix.md`](../compliance/traceability-matrix.md) |
| What is still wrong with it? | The Follow-ups register in `STATUS.md`, and [`../technical-debt.md`](../technical-debt.md) |

The binding version of all of this, for any session working in this repository, is
[`../plan/PROTOCOL.md`](../plan/PROTOCOL.md). This document explains the gates; that one is the
instruction.
