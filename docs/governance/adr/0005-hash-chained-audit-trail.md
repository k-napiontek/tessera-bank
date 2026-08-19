# ADR 0005 - Make the audit trail append-only and hash-chained

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

REQ-AUD-001 says the audit trail is "append-only and tamper-evident". Those are two requirements and
they are met by different mechanisms, which is the part usually got wrong.

A table with no `UPDATE` in the application is append-only by convention. Convention is not a
control: the next adapter, a migration script, a support engineer with `psql`, or a restored backup
all bypass it, and none of them leaves a trace. So the schema has to refuse mutation rather than the
code merely declining to attempt it.

But no schema rule constrains somebody who can change the schema. A DBA with DDL rights can drop a
trigger, edit a row and put the trigger back; a backup can be restored with one row altered. A bank
cannot prevent this - the people who administer the database are the people who administer the
database - and a control that claims to prevent it is worse than one that admits what it does. What
is achievable is that any such change becomes **detectable**, and detectable specifically, naming the
row.

The regulatory framing is DORA and the audit expectations that come with it: the question a regulator
asks is not "can your DBA change a row" but "would you know".

## Decision

**Two mechanisms, doing different jobs, stated separately.**

1. **Append-only in the schema.** `audit_record` carries a trigger refusing `UPDATE` and `DELETE`,
   and a second, statement-level trigger refusing `TRUNCATE`. The second is not decoration: `TRUNCATE`
   fires no row trigger, so without it a single statement empties the trail while the control that was
   supposed to prevent it never runs.

2. **Hash-chained for tamper evidence.** Every row carries the SHA-256 of its own contents chained
   onto the previous row's hash. `AuditChain.verify()` walks the table in sequence order, recomputes
   each hash, and reports the **first** broken link with the reason - an altered row and a removed row
   produce different messages, because they are different incidents.

Three supporting decisions that are easy to get wrong:

- **The canonical form is length-prefixed.** The control is the encoding, not the digest: every
  implementation of SHA-256 agrees, so what decides tamper evidence is whether two different entries
  can hash to the same value. Concatenating fields lets `{"a": "bc"}` and `{"ab": "c"}` collide, and
  an auditor could then be shown either row with the chain verifying for both. An absent value is
  encoded differently from an empty one for the same reason.
- **The chain serialises on an advisory lock held to commit.** Reading the last hash and inserting
  the row that chains onto it must be one atomic step. `audit_record_previous_unique` makes a fork
  unrepresentable, so without the lock the loser of a race has its transfer rejected - a chain defect
  surfacing as a customer-visible failure.
- **What is hashed is what is stored.** The entry is normalised before hashing: `timestamptz` keeps
  microseconds while `Instant` carries nanoseconds, and a `uuid` column returns lowercase. Hash first
  and normalise second and verification reports tampering on rows nobody touched, and a control that
  cries wolf gets switched off.

## Consequences

**What becomes easier.** "Would you know" has a demonstrable answer: a test tampers with a row -
disabling the trigger to do it, which is the scenario - and the verifier names it.

**What becomes harder.** Every money-moving transaction now queues behind one advisory lock for the
duration of its own transaction. That is a real throughput ceiling and it is stated rather than
discovered: the audit chain is a global sequence, and a global sequence has one writer at a time.

**What we are committed to.** The chain has one head, so the trail cannot be partitioned or sharded
without changing this decision. Retention meets the same obstacle GDPR erasure does - deleting a row
breaks the chain - which `docs/ways-of-working/data-classification.md` already confronts.

## Alternatives considered

**Append-only by convention, enforced in code.** What most systems have. Rejected because it is not a
control: it constrains the one caller that was written to respect it and nothing else, and its
failures leave no trace.

**A per-subject chain, one head per account.** Removes the global lock and would scale. Rejected
because it weakens exactly the property the chain is for: with one chain per subject, deleting an
entire subject's history breaks nothing that any verifier can see. A global chain makes the *absence*
of rows detectable, which is the tampering an insider would actually attempt.

**Signing each row with a private key, rather than chaining.** Stronger against an attacker who can
rewrite the whole table, since they cannot forge signatures. Rejected for now because it requires key
management this repository deliberately does not have - the keys would live in the platform
repositories (ADR 0001) - and it detects alteration but not deletion, which chaining does. The two
compose, and signing the chain head periodically is the natural extension.

**Writing the audit trail to a separate append-only store.** Removes the DBA from the threat model
properly. Rejected because the audit row must commit with the postings, and a second store
reintroduces the dual-write problem ADR 0004 exists to avoid. The audit trail and the outbox make the
same trade for the same reason.
