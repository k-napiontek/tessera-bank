# WP-15 - backoffice

| | |
|---|---|
| **Ticket** | TB-1015 |
| **Branch** | `feat/TB-1015-backoffice` |
| **Stratum** | 1 - JSP + jQuery, ~2011 |
| **Depends on** | WP-10 |
| **Status** | `Done` - see `STATUS.md` |

## Objective

The internal operations screen, built the way internal bank tools genuinely are: JSP and jQuery,
server-rendered, unfashionable, and still running fifteen years later because it works and nobody
will fund replacing it. Operators use it to read the reconciliation report and work the rejects from
the overnight cycle.

## In scope

- Server-rendered JSP pages in **`legacy/backoffice`, its own WAR**, deployed to the same
  Tomcat as `customer-master` and reusing its DAO layer. This previously read "inside the
  `customer-master` WAR" and contradicted both READMEs; the repository owner resolved it in
  favour of a separate module. See the Tasks preamble.
- Reconciliation break list with drill-down to the underlying records.
- Rejects queue from the overnight cycle, with a reason per record.
- Basic operator actions: acknowledge a break, annotate a reject.
- jQuery for the interactive parts, as a 2011 team would have used it.

## Out of scope

- Any modern frontend framework, build step or bundler. Deliberately.
- Direct database access - the pages call the existing service layer.
- Authorisation model beyond a simple operator role.

## Constraints

- **JSP and jQuery only.** No React, no TypeScript, no npm. This screen exists to demonstrate that
  the estate contains genuinely different eras, and modernising it destroys that.
- Server-rendered. No single-page application behaviour.
- Every operator action writes to the audit trail. An internal tool that mutates state without an
  audit record is exactly the finding an auditor writes up.
- Styling should look its age. Do not make it pretty.

## Tasks

Detailed 2026-08-20. **Nine tasks on one branch**, `feat/TB-1015-backoffice`. Java 8, JSP and jQuery
on stratum 1, deployed as a WAR to Tomcat 8.5 over real Oracle.

Four decisions are taken here rather than discovered mid-branch.

- **`backoffice` is its own WAR**, by explicit instruction of the repository owner. This file's
  *In scope* previously said "JSP pages inside the `customer-master` WAR" while `legacy/README.md`
  and `legacy/backoffice/README.md` both described a separate screen, and the branch name has always
  been `feat/TB-1015-backoffice`. The wording above is corrected rather than left to be discovered.
  The shape is also the right one: an operator screen and the SOAP system of record the ESB depends
  on should not redeploy together, and a 2011 bank ran exactly this pair of WARs on one Tomcat. The
  cost is real and is task 1's - `customer-master` must attach a classes jar so the DAO can be
  reused rather than reimplemented, and the Cargo scaffolding is copied a third time (**F-61**).
- **The audit trail is built here, because stratum 1 does not have one.** `V1__schema.sql` holds
  `customer`, `account` and `applied_transfer` and nothing else, so REQ-OPS-004 has nothing to write
  to. This package adds it, in `customer-master`'s migrations because that is where stratum 1's
  schema lives. It is the honest reading of this file's own Constraint: *an internal tool that
  mutates state without an audit record is exactly the finding an auditor writes up.*
- **The break report is read as a file, never from the ledger.** `batch/recon` writes
  `BREAKS-CCYYMMDD.json` per [`contracts/recon/break-report-v1.md`](../../../contracts/recon/break-report-v1.md),
  and that contract exists precisely so this screen can render it. Giving stratum 1 a PostgreSQL
  connection would hand a 2011 monolith a dependency on a 2023 ledger and route around the layering
  the whole estate is built to demonstrate. The report carries `ledgerPosition` and
  `ledgerChainHash`; the screen shows both, because an operator working a break needs to know which
  cut they are looking at.
- **Server-rendered, so the server parses the JSON.** The Constraints forbid single-page behaviour,
  which rules out handing the file to the browser and letting jQuery build the table - the tempting
  shortcut, because it would need no JSON library at stratum 1 at all. A 2011-era parser goes in the
  parent POM instead, pinned and justified there like every other version. jQuery is for the
  interactive parts the era used it for: filtering, sorting, and posting an action without losing
  the page.

1. **The module.** `legacy/backoffice`, `war` packaging, `tessera-parent`, Java 8 with the same
   compiler settings `customer-master` uses. `ToolchainTest` and a bytecode-version test, as at every
   stratum. `make build-backoffice` and `make test-backoffice` following the `test-legacy` pattern,
   and `test-legacy` runs both modules. **`customer-master` gains a `classes` jar attachment** so the
   DAO and the domain types are reusable - the alternative is a second implementation of account
   lookup, which is exactly what this estate's reconciliation exists to catch.
2. **The audit trail.** `V4__audit.sql` and `V5__pkg_operator.sql` in `customer-master`'s migrations: who acted, what they acted
   on, when, and what changed. Oracle dialect with the writing done in a package body, as WP-10a did,
   because a 2011 team put it there and because an audit row written by application code is one an
   application bug can skip. **Append-only** - no `UPDATE`, no `DELETE`, enforced by a trigger, the
   same control the ledger's `audit_record` carries three strata away. A DAO over it, tested against
   real Oracle.
3. **The rejects queue, read from `REJECTS.DAT`.** `REJREC` is 200 bytes and its first 120 are a
   whole `MOVEREC`, so a reject carries the movement that failed - offsets from
   `contracts/check-copybook-offsets.py --json`, never counted. `REJ-REASON-CODE` is the machine
   field and `REJ-REASON-TEXT` the operator one; both are shown, because a code an operator has to
   look up is a code they will guess at. A file that is not a whole number of records is refused
   rather than partially listed.
4. **The break report, read and validated.** Parse `BREAKS-CCYYMMDD.json` against the contract's
   field list and refuse a document that does not match it - a screen that renders whatever it is
   given will one day render last week's file and say nothing. Classification drives presentation:
   `TIMING` is listed and visibly **not** actionable, per the same reasoning ADR 0015 and the
   [runbook](../../runbooks/reconciliation-break.md) give. The three that need an operator are the
   three that get an action.
5. **The break list screen.** Server-rendered JSP: the totals block first, then the breaks ascending
   by account reference. Drill-down opens one break with both balances, the difference, and the
   cut it was taken at. jQuery filters by classification client-side. **Styling looks its age** - a
   stylesheet a 2011 team wrote, tables with borders, no framework. Do not make it pretty.
6. **The rejects screen.** The night's rejects with a reason per record, and the movement decoded
   from the embedded `MOVEREC` - account, direction, amount, value date - so an operator does not
   read packed decimal by eye.
7. **Operator actions, every one of them audited.** Acknowledge a break; annotate a reject. Both go
   through the service layer, both write an audit row **in the same transaction as the change**, and
   both refuse an action against a target that does not exist in the file they claim to come from -
   an acknowledgement for a break nobody reported is either a stale page or a defect, and neither
   should be recorded as fact. A simple operator role via `web.xml` security constraints, which is
   what the Constraints scope and no more.
8. **Deployed, and tested deployed.** The WAR on a real Tomcat 8.5 against real Oracle through
   Cargo, exactly as `CustomerMasterDeploymentIT` does it. The test walks what an operator walks:
   the break list renders a report this test wrote, a break is acknowledged, and the audit row is
   read back with the acting user on it. A screen verified only by unit tests is a screen nobody has
   deployed.
9. **Documentation and landing.** `legacy/backoffice/README.md`, `legacy/README.md`, traceability for
   REQ-OPS-003 and REQ-OPS-004, the reconciliation-break runbook gains the operator's path through
   the screen, `STATUS.md`, pull request, merge.

**Out of scope and logged, not fixed:** the Cargo/Tomcat/Oracle test scaffolding is now in three
places (F-61). Sharing it is the same package-sized change F-66 describes for the batch tier.

## Definition of Done

- [x] Reconciliation breaks and rejects are listed with drill-down. `BreaksServlet` renders the
      totals block and the breaks ascending by account reference, and one break opens with both
      balances, the difference and the cut it was taken at; `RejectsServlet` lists the night's
      rejects with the reason code, the reason text and the movement decoded out of the embedded
      `MOVEREC`.
- [x] Operator actions are recorded in the audit trail with the acting user. `PKG_OPERATOR` writes
      the `operator_audit` row in the same transaction as the change, and `BackofficeDeploymentIT`
      reads the row back out of Oracle rather than off the screen that wrote it.
- [x] The pages render as their own WAR on the same Tomcat 8.5 as `customer-master` - the shape the
      repository owner chose, replacing this box's original "inside the existing WAR". Six
      deployment tests run against a Tomcat 8.5 installed by Cargo over a real Oracle.
- [x] No modern frontend tooling has been introduced. JSP, JSTL and a vendored jQuery 1.7.2. No
      npm, no bundler, no build step of any kind for the front end.

## Verification

```bash
make jdk8                                        # names the JDK 8 this tier will use
make test-backoffice                             # the new module, on real Tomcat 8.5 and real Oracle
make test-legacy                                 # customer-master is still green beside it
make test-recon                                  # the producer of the report this screen renders
bash contracts/validate.sh                       # the break report contract still validates
make test                                        # every other tier still green
```

Needs Docker: a real Oracle and a real Tomcat 8.5 start during this suite.

End to end, and it is what an operator walks: deploy the WAR, log in as an operator, and confirm a
break report written by `batch/recon` appears in the list and can be drilled into; that a reject
from `REJECTS.DAT` is listed with its reason and its decoded movement; that a break can be
acknowledged and a reject annotated; and that **both actions appear in the audit trail with the
acting user and the time**, read back from Oracle rather than from the screen that wrote them.

**A `TIMING` break offers no action.** The classification exists to say "expected", and a screen that
invites an operator to work one undoes what ADR 0015 was for.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-OPS-003 Operators can see and work reconciliation breaks | break list |
| REQ-OPS-004 Operator actions are attributable and audited | audit integration |
| REQ-EST-002 The estate contains genuinely different UI eras | JSP + jQuery |
