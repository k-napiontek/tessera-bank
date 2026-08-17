<!--
This template is the change record. In a regulated institution the pull request IS the audit
artefact - it is what an auditor samples to confirm a change was authorised, tested, approved and
implemented under control. Fill it in properly; an empty section is a finding.
-->

## Change

**Ticket:** TB-XXXX
**Work package:** WP-XX
**Type:** feature | fix | refactor | documentation | dependency

### What changed

<!-- Plainly, in a few sentences. Not a restatement of the diff. -->

### Why

<!-- The problem this solves. If it implements a work package, say which requirement it satisfies. -->

## Risk

**Classification:** standard | normal | major | emergency

<!--
standard  - pre-approved, low risk, well-understood (e.g. a patch-level dependency bump)
normal    - the default; requires review
major     - architecturally significant, or touches money movement; needs architecture review first
emergency - production-impacting; retrospective review within 48 hours
-->

**Blast radius:** <!-- which components are affected if this is wrong -->

**Does this touch money movement, authentication, or personal data?** yes / no
<!-- If yes, a security review is required before merge. -->

## Test evidence

<!--
Paste the ACTUAL output of the verification commands from the work package.
Do not paste expected output. Do not summarise. Falsifying a control record is worse than a
failing test, and it is the easiest thing in the world for a reviewer to catch.
-->

```
$ <command>
<real output>
```

- [ ] Every verification command in the work package was run and passed
- [ ] New tests fail without the implementation
- [ ] No new static-analysis violations
- [ ] No new high or critical CVEs, or the exception is registered in `docs/technical-debt.md`

## Rollback

**How to reverse this change:** <!-- revert commit / down-migration / config change -->

**Is it reversible without data loss?** yes / no
<!-- If no, say what is lost and why that is acceptable. -->

## Definition of Done

- [ ] Checked against [`docs/ways-of-working/definition-of-done.md`](../docs/ways-of-working/definition-of-done.md)
- [ ] Contract updated **before** implementation, if an interface changed
- [ ] ADR recorded, if architecturally significant
- [ ] Runbook updated, if operationally significant
- [ ] Traceability matrix updated
- [ ] `docs/plan/STATUS.md` updated
- [ ] No personal data in code, tests, fixtures, logs or sample files
- [ ] No version upgraded in strata 0-2 (see [`CLAUDE.md`](../CLAUDE.md))

## Approvals

**Author:**
**Reviewer:**

<!--
Four-eyes review is the target control: the author never approves or merges their own change.
In this repository that control is NOT enforced - see docs/ways-of-working/control-exceptions.md,
CE-001. Record what actually happened here, not what should have.
-->
