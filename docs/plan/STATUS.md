# STATUS

The single source of truth for what is done and what comes next. **Read this first, every session.**

Updated by the executing session at the start and end of every work package, per
[`PROTOCOL.md`](PROTOCOL.md).

---

## Next actionable package

> **WP-02 - Canonical data model and contracts.** Dependencies satisfied (WP-01 is `Done`) and the
> package is fully detailed: eight tasks, real verification commands, conformance checks specified.
> **Ready to execute.** Start a fresh session and run `/work-package WP-02`.

---

## Work packages

Status values: `Not started` | `In progress` | `Blocked` | `Done`

| WP | Title | Stratum | Depends on | Status | PR | Merge SHA |
|---|---|---|---|---|---|---|
| [01](wp/WP-01-foundation.md) | Repo foundation, governance docs, plan system, Claude Code config | - | - | `Done` | n/a - pre-git | n/a |
| [02](wp/WP-02-contracts.md) | Canonical data model and contracts - copybook, WSDL/XSD, OpenAPI, AsyncAPI | - | 01 | `Not started` | | |
| [03](wp/WP-03-mainframe-data.md) | Mainframe copybooks and synthetic master/movement data | 0 | 02 | `Not started` | | |
| [04](wp/WP-04-acctpost.md) | `ACCTPOST.CBL` - balanced-line match-merge | 0 | 03 | `Not started` | | |
| [05](wp/WP-05-eodrept.md) | `EODREPT.CBL`, `EODCYCLE.JCL`, local runner | 0 | 04 | `Not started` | | |
| [06](wp/WP-06-ledger-domain.md) | Ledger domain - pure Java, no Spring, property tests | 3 | 02 | `Not started` | | |
| [07](wp/WP-07-ledger-persistence.md) | Ledger persistence - schema, migrations, locking, Testcontainers | 3 | 06 | `Not started` | | |
| [08](wp/WP-08-ledger-api.md) | Ledger API - transfers, idempotency, Problem Details, contract test | 3 | 07 | `Not started` | | |
| [09](wp/WP-09-ledger-audit-outbox.md) | Ledger audit chain, transactional outbox, metrics, logging | 3 | 08 | `Not started` | | |
| [10](wp/WP-10-customer-master.md) | `customer-master` - Java 8, WSDL-first SOAP, WAR | 1 | 02 | `Not started` | | |
| [11](wp/WP-11-esb-adapter.md) | `esb-adapter` - Boot 2.7, Kafka to XSLT to SOAP, COMP-3 encoding | 2 | 09, 10 | `Not started` | | |
| [12](wp/WP-12-api-gateway.md) | `api-gateway` - Go | 4 | 08 | `Not started` | | |
| [13](wp/WP-13-fraud-scoring.md) | `fraud-scoring` - Python, Kafka consumer | 4 | 09 | `Not started` | | |
| [14](wp/WP-14-web-banking.md) | `web-banking` - React | 4 | 12 | `Not started` | | |
| [15](wp/WP-15-backoffice.md) | `backoffice` - JSP + jQuery | 1 | 10 | `Not started` | | |
| [16](wp/WP-16-recon.md) | `recon` - COBOL master against ledger, break reporting | - | 05, 11 | `Not started` | | |
| [17](wp/WP-17-reporting.md) | `reporting` - Python batch | 4 | 09 | `Not started` | | |
| [18](wp/WP-18-incident-exercise.md) | Deliberate incident exercise, RCA, final documentation pass | - | 16 | `Not started` | | |

## Critical path

```
01 -> 02 -+-> 06 -> 07 -> 08 -> 09 -+-> 11 -> 16 -> 18
          |                          |
          +-> 03 -> 04 -> 05 --------+
          |
          +-> 10 --------------------+
```

`12`, `13`, `14`, `15` and `17` sit off the critical path and can be taken whenever their
dependencies are `Done`.

---

## Follow-ups

Discovered outside the scope of the package being worked, and deliberately not fixed there. Each
becomes its own change when picked up.

| # | Raised in | Description | Status |
|---|---|---|---|
| F-01 | WP-01 | `git init` has not been run, so the branch-protection hook is inert. | **Closed** - repository initialised on `main` with a baseline commit, 2026-08-17 |
| F-02 | WP-01 | Work packages carry frame only until detailed. **WP-02 is now fully detailed.** WP-03 to WP-18 still need their task lists filled in before execution; the `/work-package` skill halts on any package that has not been. | Open |
| F-03 | WP-01 | `quality/` holds no linter rule files yet. Each is added by the work package that first needs it, so the rules land with code to check. | Open |
| F-04 | WP-01 | `.github/CODEOWNERS` uses placeholder team handles (`@tessera-bank/...`). The file has no effect until they are replaced with real GitHub teams or usernames. The ownership structure is deliberate and should be kept. | Open |
| F-05 | WP-01 | 14 governance documents are outlines only, each carrying a stub banner and naming its owning work package. WP-18 verifies none remain. | Open |

---

## Decision log

Decisions taken outside an ADR that later sessions need to know about.

| Date | Decision |
|---|---|
| 2026-08-17 | Repository is application source only. No Dockerfile, no CI. See ADR 0001. |
| 2026-08-17 | Legacy strata deliberately pinned to EOL versions. See ADR 0002. |
| 2026-08-17 | Regulatory framing is EU-first: DORA, GDPR, PSD2. SOX excluded - Tessera Bank is not US-listed. |
| 2026-08-17 | The AI merges its own PRs once verification passes. Four-eyes registered as a control exception. |
| 2026-08-17 | Commit convention extended with the change ticket: `type(scope): subject [TB-XXXX]`. |
| 2026-08-17 | Legacy-manifest hook uses `ask`, not `deny`. It stops silent modernisation - the actual risk - while still letting the repository owner approve a deliberate version change. A hard `deny` would require editing `settings.json` to make any legitimate change. |
| 2026-08-17 | Branch-protection hook uses `git branch --show-current`, not `git rev-parse --abbrev-ref HEAD`. The latter fails on a repository with no commits, so it would not have protected the very first commit. |
| 2026-08-17 | Renamed from the earlier working name to **Tessera Bank**, ticket prefix `TB-`. The rename landed before the first commit, so no history carries the old name. Web checks confirmed Thaler, Strata, Cairn and Monolith are real institutions; Tessera returned no bank and no tech-giant collision. A *tessera* is one tile of a Roman mosaic, and *tesserae nummulariae* were tokens bankers used to certify coin. |
| 2026-08-17 | Licensed **MIT**. Its "AS IS, without warranty" clause is load-bearing here, not boilerplate, given the deliberately end-of-life dependencies. |
| 2026-08-17 | WP-02 gains a **canonical data model** as its first deliverable, plus conformance checks in WP-03, WP-08, WP-10 and WP-11. Without it the four era-specific contracts are written independently and drift, and the drift would not surface until WP-11 encodes COMP-3 bytes that WP-03 laid out differently. |
| 2026-08-17 | `make status` and `make plan` print their files in full instead of a fixed line range, which had begun silently truncating `STATUS.md`. |
