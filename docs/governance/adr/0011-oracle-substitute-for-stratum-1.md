# ADR 0011 - Stratum 1 runs real Oracle in a container, not an Oracle-compatible substitute

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

WP-10 builds `customer-master` in the Oracle SQL dialect with business logic in stored procedures,
because that is where a 2011 bank put it. [TD-005](../../technical-debt.md) accepts the resulting
lock-in as deliberate and states that "Oracle itself is not distributable, so local execution uses a
compatible substitute" - but from WP-01 until WP-10a it never named the substitute, because the
document it points at for that detail, `consuming-this-repo.md`, is still a stub. The first task of
WP-10 therefore ran straight into an unanswered question, and answering it wrongly is cheap now and
expensive later.

The reason the answer matters is the stated purpose of the debt. TD-005 justifies the dialect on the
grounds that "the dialect lock-in and the stored-procedure logic are the realistic parts, and they
make a later migration exercise genuinely difficult in the way real migrations are." A migration is
difficult because there is PL/SQL to rewrite. If the substitute cannot execute PL/SQL, the logic
never lands in the database in the first place, and the exercise later measures the difficulty of
moving code that was always in Java.

This is the failure mode worth recording: **nothing breaks.** The module builds, the tests pass, the
SOAP responses validate, and stratum 1 looks finished. It is simply no longer the tier the master
plan describes, and nobody finds out until WP-11 or a migration exercise leans on it.

The development machine is Apple Silicon, which removes one candidate outright.

## Decision

Tests for `customer-master` run **real Oracle Database Free 23ai** through Testcontainers, from
`gvenzl/oracle-free:23-slim-faststart`. That tag publishes a native `linux/arm64` manifest alongside
`linux/amd64` - verified with `docker manifest inspect` - so it runs here without emulation.

The schema and packages are written to the **2011 Oracle feature set**: `NUMBER` and `VARCHAR2`,
sequences rather than identity columns, `REGEXP_LIKE` check constraints, and logic in `PACKAGE` and
`PACKAGE BODY`. None of the 12c-and-later syntax the engine would happily accept.

## Consequences

- PL/SQL actually executes, so the stored procedures are tested against the behaviour they claim
  rather than against a description of it, and the migration friction the tier exists to create is
  real rather than asserted.
- Stratum 1's tests need a running Docker daemon and a first pull of roughly two gigabytes. Strata 3
  and 4 already require Docker, so the prerequisite is not new - but stratum 1 is now slow by
  construction. One container per module, started once, keeps it to about eight seconds a run.
- **The engine is newer than the code it hosts, and this is the real weakness of the decision.**
  23ai accepts syntax 11g would reject, so the dialect discipline is enforced by review and by the
  feature restriction above, **not** by the database. A reviewer who waves through an identity column
  or a 21c JSON type will not be caught by a failing test. Recorded rather than solved.
- One neighbouring trap *is* now caught mechanically, and it is worth naming because it is the one
  that actually fired. Oracle **creates a package body that does not compile**, reporting no error to
  JDBC and leaving the object `INVALID` until something calls it - at which point the failure is
  `ORA-04063` attributed to whatever test ran first. `SchemaTest.leavesNoInvalidObjectBehind` fails
  the build on any invalid object and prints the compiler's own line and column. That catches broken
  PL/SQL; it does not catch PL/SQL that is valid and anachronistic, which is the paragraph above.
- `docs/consuming-this-repo.md` must name this substitution when WP-18 writes it - the compensating
  control TD-005 promised and has not yet delivered. Logged as F-55.

## Alternatives considered

**H2 in Oracle compatibility mode.** Rejected, and it is the option that looks most attractive: no
Docker, hermetic, milliseconds to start. `MODE=Oracle` is a *dialect* emulation - `NUMBER`,
`VARCHAR2`, `DUAL`, `NVL`, `SEQUENCE.NEXTVAL` - and H2 does not execute PL/SQL at all. Its stored
procedures are Java methods bound through `CREATE ALIAS`. Choosing it moves the business logic that
WP-10 exists to place in the database back into Java, while leaving the schema looking Oracle enough
that the substitution is easy to forget.

**Oracle XE 21c (`gvenzl/oracle-xe`).** Rejected on architecture.
`docker manifest inspect gvenzl/oracle-xe:21-slim-faststart` returns a single-platform manifest with
no `arm64` entry, so it would run under emulation on this machine - on a container that already
takes tens of seconds to open, that is slow enough to change how often anyone runs the suite, and a
suite nobody runs is not a control.

**A real installed Oracle instance.** Not distributable, which is the constraint TD-005 records in
the first place.

## Note on numbering

This decision was first drafted as ADR 0010 on a branch that was overtaken by WP-10a's execution,
where 0010 was taken by
[the balance-duplication decision](0010-customer-master-holds-its-own-balances.md). The number is
sequential and never reused, so the draft was re-landed here as 0011 with its reasoning intact and
its claims re-verified against what was actually built.
