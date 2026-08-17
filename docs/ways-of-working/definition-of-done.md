# Definition of Done

A change is done when every box below is genuinely ticked. Not "mostly", not "will follow up" - the
point of a Definition of Done is that it is binary, so that "done" means the same thing every time.

Applies to every change, including documentation-only ones (the irrelevant boxes are marked n/a
explicitly rather than skipped silently).

## Code

- [ ] Implements exactly what the work package describes - no more, no less.
- [ ] Written test-first. Tests fail without the implementation.
- [ ] All tests pass locally, including the tiers the change touches.
- [ ] No new static-analysis violations.
- [ ] No new high or critical CVEs introduced. Any accepted one is registered in
      [`../technical-debt.md`](../technical-debt.md) with an owner and a review date.
- [ ] Matches the pinned stack of its stratum. No modern idiom leaked into a legacy tier, and no
      version upgraded in strata 0-2.
- [ ] No personal data in code, tests, fixtures, logs or sample files.

## Contracts

- [ ] If an interface changed, the contract in `contracts/` changed **first**.
- [ ] The contract test passes, proving implementation and contract agree.
- [ ] Breaking changes to a contract are called out explicitly in the PR, with the consumers named.
- [ ] `bash contracts/validate.sh` exits 0, and every field the change touches traces to a concept
      in [`../architecture/canonical-data-model.md`](../architecture/canonical-data-model.md).
      No contract invents a concept of its own.

## Documentation

- [ ] ADR recorded if the change is architecturally significant.
- [ ] Runbook created or updated if the change is operationally significant.
- [ ] Directory `README.md` updated if the directory's contents or purpose changed.
- [ ] [`../compliance/traceability-matrix.md`](../compliance/traceability-matrix.md) updated with the
      requirements this change satisfies.
- [ ] Every new internal link resolves.

## Process

- [ ] Branch named `type/TB-XXXX-short-description`.
- [ ] Commits are conventional, carry the ticket ID, and are individually green.
- [ ] No commit exceeds ~400 lines of diff without a stated reason.
- [ ] No `wip` or `fix typo` commits survive into the pull request.
- [ ] Pull request uses the template with the change record fully completed.
- [ ] Verification commands from the work package were **actually run**, and their real output is
      pasted into the PR.
- [ ] [`../plan/STATUS.md`](../plan/STATUS.md) updated: status, PR link, merge SHA, follow-ups.

## The honesty clause

If a box cannot be ticked, the change is not done. Say so, explain why, and either fix it or record
it as a follow-up in `STATUS.md`.

Never tick a box you did not verify. The entire value of this list is that a tick means something -
a checklist filled in optimistically is worse than no checklist, because it converts an unknown into
a false assurance.
