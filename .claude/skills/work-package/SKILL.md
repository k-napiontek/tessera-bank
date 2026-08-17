---
name: work-package
description: Execute one Tessera Bank work package end to end - select it, branch, build it test-first in properly-sized commits, verify, open the PR, merge, update STATUS.md, then stop. Use when starting or resuming work in this repository, or when the user says /work-package, "next work package", "continue the plan", or names a WP number.
---

# Execute a work package

This skill drives the execution protocol for this repository. It is deliberately thin: the rules
live in the repository documents, and this skill points at them so there is exactly one source of
truth to keep current.

**Read [`docs/plan/PROTOCOL.md`](../../../docs/plan/PROTOCOL.md) now, in full, before continuing.**
It is binding and this skill does not restate it.

## The core rule

**One work package at a time. Finish it. Stop. Report.**

Never work two packages in parallel. Never begin the next package without being asked.

## Step 1 - Orient

Read, in this order:

1. [`CLAUDE.md`](../../../CLAUDE.md) - binding standards, including the legacy-strata rule
2. [`docs/plan/STATUS.md`](../../../docs/plan/STATUS.md) - what is done and what is next
3. [`docs/plan/master-plan.md`](../../../docs/plan/master-plan.md) - why the project is shaped this way
4. [`docs/plan/PROTOCOL.md`](../../../docs/plan/PROTOCOL.md) - how work is executed

## Step 2 - Select

If the user named a package, use it. Otherwise take the **lowest-numbered** package whose status is
`Not started` and whose dependencies are **all** `Done`.

Then check, before touching anything:

- Are all of its dependencies `Done`? If not, **stop and say so.**
- Does its file still carry `## Tasks` as "To be detailed before execution"? If so, the package has
  not been refined yet. **Stop and ask the user to detail it** rather than inventing tasks - the task
  list is where the user's intent lives, and guessing it defeats the point of the plan.
- Is the working tree clean?

## Step 3 - Execute

Follow `PROTOCOL.md` phases 2 and 3 exactly. The points that get violated most often:

- **Stay in scope.** Read the package's *Out of scope* section and honour it. Anything else you
  discover goes to Follow-ups in `STATUS.md`, not onto this branch.
- **Check the stratum.** `CLAUDE.md` lists the pinned stack per directory. Never upgrade strata 0-2.
  Never let a modern idiom leak into a legacy tier - the eras are supposed to differ.
- **Test first.** Failing test, then implementation, then refactor.
- **Size the commits.** One logical change each, under ~400 lines, individually green, roughly 3-10
  per package.
- **Run the verification for real.** Paste actual output into the pull request. Never paste expected
  output - falsifying a control record is worse than a failing test.

## Step 4 - Land and stop

Open the PR from the template with the change record completed. Merge **only** if every verification
passed. Update `STATUS.md` with status, PR link, merge SHA and any follow-ups.

Then report: what was built, what verification showed, what was logged as a follow-up, and which
package is next.

**Do not start the next package.** Wait to be asked.

## Halt conditions

Stop and ask rather than improvising when verification fails outside the package's scope, the
package contradicts the master plan, the work would require upgrading a pinned legacy version, a
dependency is not `Done`, the change would touch personal data or authentication unexpectedly, or
the package turns out much larger than described - in which case propose splitting it rather than
producing an unreviewable pull request.
