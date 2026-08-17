# PROTOCOL - how work is executed in this repository

**Binding.** This document governs how any AI session works here. It exists so that a session with no
prior context can pick up exactly where the last one stopped, and produce work that looks like it
came from a professional engineering team rather than a machine spraying commits at `main`.

The shortcut: `/work-package <ID>`.

---

## The core rule

**One work package at a time. Finish it. Stop. Report.**

Never work two packages in parallel. Never begin the next package without being asked. The pause
between packages is where the repository owner stays in control of their own repository.

---

## Phase 1 - Starting

1. Read [`../../CLAUDE.md`](../../CLAUDE.md), [`master-plan.md`](master-plan.md), this file, and
   [`STATUS.md`](STATUS.md).
2. Select the work package: the **lowest-numbered** package whose status is `Not started` and whose
   dependencies are **all** `Done`. If the caller named a package, verify it satisfies the same
   condition before accepting it.
3. Confirm the working tree is clean and `main` is up to date.
4. Read the **entire** work-package file before touching anything, including its Out of scope and
   Constraints sections.
5. If anything in the package is ambiguous or contradicts the master plan, **stop and ask now** -
   not halfway through.
6. Set the package to `In progress` in `STATUS.md`.
7. Create the branch from up-to-date `main`:

   ```
   git switch main && git pull --ff-only
   git switch -c feat/TB-XXXX-<slug>
   ```

## Phase 2 - Working

8. Follow the package's task list **in order**. Each task is roughly one commit.
9. **Test-driven, always.** Write the failing test, make it pass, refactor. A task with no test is a
   task that is not done.
10. **Stay inside the declared scope.** If you discover something that needs changing outside this
    package - a bug, a missing document, an inconsistency - do **not** fix it here. Log it under
    Follow-ups in `STATUS.md` and carry on. Widening a branch is how a reviewable change becomes an
    unreviewable one.
11. **Respect the stratum.** Check `CLAUDE.md` for the pinned stack of the directory you are in.
    Never upgrade strata 0, 1 or 2. Never let a stratum-3 idiom leak into stratum-1 code - the whole
    point is that the eras differ.
12. Update the contract in `contracts/` **before** the implementation that satisfies it, never after.

### Commit sizing

Karol asked for this specifically, so it is a rule and not a preference.

- One commit is **one logical change that builds and passes its tests on its own**.
- Target under **~400 lines** of diff. Hard ceiling ~800, and only for generated code or data
  fixtures.
- Test and implementation for the same behaviour go in the **same** commit - test-driven development
  produces this naturally.
- **Never** mix a refactor with a behaviour change.
- No `wip`, `fixes`, `address review` or `fix typo` commits survive into a pull request. Clean the
  history before opening it.
- A work package should produce roughly **3 to 10** commits. One commit means the package was too
  coarse; twenty means the tasks were not grouped.

Message format, per `CLAUDE.md`:

```
feat(ledger): add idempotency key handling [TB-1008]
```

## Phase 3 - Finishing

13. Run **every** command in the package's Verification section. All must pass. Paste the actual
    output into the pull request - never claim a verification you did not run.
14. Update everything the package affects: the traceability matrix, any ADRs, any runbooks, and the
    directory `README.md` files whose contents changed.
15. Self-check against
    [`../ways-of-working/definition-of-done.md`](../ways-of-working/definition-of-done.md). Every box
    must be genuinely ticked.
16. Open the pull request using
    [`../../.github/pull_request_template.md`](../../.github/pull_request_template.md), with the
    change record fully completed: what changed, why, risk class, test evidence, rollback plan.
17. **Only if every verification passed**, merge to `main` and delete the branch. If anything failed,
    stop and report instead - a red package is not a finished package.
18. Record the outcome in `STATUS.md`: status `Done`, the PR link, the merge SHA, and any follow-ups
    discovered.

## Phase 4 - Stop

19. Report to Karol: what was built, what the verification showed, what was logged as a follow-up,
    and what the next actionable package is.
20. **Do not start the next package.** Wait to be asked.

---

## Halt conditions

Stop and ask rather than improvising when:

- verification fails and the fix lies **outside** the current package's scope;
- the package contradicts the master plan or another package;
- the work would require **upgrading a pinned legacy version** in strata 0, 1 or 2;
- a dependency package is not `Done`;
- the change would touch personal data or authentication in a way the package does not describe;
- the package turns out to be substantially larger than its description implies - say so and propose
  splitting it rather than producing a thousand-line pull request.

Improvising past any of these produces work that has to be undone. Asking costs one message.

---

## Why this exists

Every rule above maps to a control that a regulated institution genuinely operates: change requests
with ticket IDs, branch discipline, reviewable change sizes, traceability from requirement to test,
a Definition of Done, evidence attached to the change record, and segregation between authoring and
approval.

Where a control is documented here but **not** actually enforced - notably four-eyes review, since
the AI both authors and merges - it is registered as an exception with compensating controls in
[`../ways-of-working/control-exceptions.md`](../ways-of-working/control-exceptions.md) rather than
quietly claimed.
