# STATUS

The single source of truth for what is done and what comes next. **Read this first, every session.**

Updated by the executing session at the start and end of every work package, per
[`PROTOCOL.md`](PROTOCOL.md).

---

## Next actionable package

> **WP-06 - Ledger domain** is **in progress** on branch `feat/TB-1006-ledger-domain`. Its task list
> was detailed and merged first ([#4](https://github.com/k-napiontek/tessera-bank/pull/4)), so
> execution follows a reviewed plan rather than one invented while coding.
>
> WP-03 and WP-10 are also unblocked but remain undetailed, and WP-03 additionally needs GnuCOBOL,
> which is not installed. See F-02 and F-10.

---

## Work packages

Status values: `Not started` | `In progress` | `Blocked` | `Done`

| WP | Title | Stratum | Depends on | Status | PR | Merge SHA |
|---|---|---|---|---|---|---|
| [01](wp/WP-01-foundation.md) | Repo foundation, governance docs, plan system, Claude Code config | - | - | `Done` | n/a - pre-git | n/a |
| [02](wp/WP-02-contracts.md) | Canonical data model and contracts - copybook, WSDL/XSD, OpenAPI, AsyncAPI | - | 01 | `Done` | [#1](https://github.com/k-napiontek/tessera-bank/pull/1) | `4044e07` |
| [03](wp/WP-03-mainframe-data.md) | Mainframe copybooks and synthetic master/movement data | 0 | 02 | `Not started` | | |
| [04](wp/WP-04-acctpost.md) | `ACCTPOST.CBL` - balanced-line match-merge | 0 | 03 | `Not started` | | |
| [05](wp/WP-05-eodrept.md) | `EODREPT.CBL`, `EODCYCLE.JCL`, local runner | 0 | 04 | `Not started` | | |
| [06](wp/WP-06-ledger-domain.md) | Ledger domain - pure Java, no Spring, property tests | 3 | 02 | `In progress` | | |
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
| F-06 | WP-02 | The branch-protection `PreToolUse` hook in `.claude/settings.json` refuses read-only Bash commands that use a shell `for` loop, answering with the "Never commit to main" message. Plain `cat` and `git status` on the same branch pass. The `if: Bash(git commit *)` condition is not filtering compound commands, so the hook over-blocks. Protection is not weakened; ordinary work is obstructed. | Open |
| F-07 | WP-02 | `npx @asyncapi/cli validate`, the command WP-02's Verification section names, cannot be installed at any published version: every one depends on `@asyncapi/studio-ui@0.5.0`, which is not on the npm registry (HTTP 404). The document itself is valid - `@asyncapi/parser`, the engine that CLI wraps, reports 0 errors and 0 warnings. `contracts/validate.sh` tries the CLI first and falls back to the parser, so the specified command resumes automatically once upstream is fixed. Accepted by the repository owner at merge: the document is valid, only the tool is broken. | Open |
| F-08 | WP-02 | A WSDL reference-consistency check was written and run during WP-02 verification - it confirms every message part resolves, the document/literal wrapped naming rule holds, and every `tb:` type the WSDL uses exists in the canonical XSD. It is **not** in `validate.sh`, because WP-02 names only two validation artefacts and widening the branch was the wrong call. `xmllint --noout` alone proves well-formedness, not that the references resolve. | Open |
| F-09 | WP-02 | The stratum 0 scale-2 constraint - `PIC S9(13)V99 COMP-3` cannot represent JPY or BHD, so the integration tier must reject them before they reach the mainframe - is architecturally significant and arguably warrants an ADR. It is fully documented in `canonical-data-model.md` section 2, but the Definition of Done's ADR box cannot be honestly ticked without one. | Open |
| F-10 | WP-06 | The development machine had no JDK and no GnuCOBOL. JDK 17 (`openjdk@17`) and Gradle 9.7 were installed for WP-06; **GnuCOBOL is still missing**, so WP-03, WP-04 and WP-05 cannot be executed or verified, and **JDK 8 is still missing**, so WP-10 and WP-11 cannot either. `openjdk@17` is keg-only, so `JAVA_HOME` must point at `/opt/homebrew/opt/openjdk@17` when invoking Gradle. | Open |
| F-11 | WP-02 | The requirement ids in the traceability matrix collided with the catalogue the work packages already defined - 14 genuine collisions, introduced by WP-02 task 8. Fixed in [#3](https://github.com/k-napiontek/tessera-bank/pull/3), which also added a catalogue index of all 60 ids so the mistake cannot repeat. | **Closed** - 2026-08-17 |

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
| 2026-08-17 | Published to GitHub as `k-napiontek/tessera-bank`, public. The repository had been local-only, which left `PROTOCOL.md` phase 3 unrunnable - there was nowhere to open a pull request. Public was chosen because the master plan frames the repository as a portfolio piece. |
| 2026-08-17 | `JournalEntry` rejects mixed-currency entries outright. WP-06's invariant 3 allowed "no mixed-currency entry without an explicit FX leg" while the canonical model states single currency with no conversion anywhere. Rejecting outright satisfies both: if no mixed-currency entry can exist, none exists without an FX leg. FX belongs to `payment-engine`, out of initial scope. |
| 2026-08-17 | The OpenAPI contract declares a bearer security scheme. Redocly's `security-defined` rule failed the lint with 11 errors otherwise, and the alternative - adding a config file to switch the rule off - would weaken a validator to hide a real gap. The contract states what it expects; `edge/api-gateway` remains the only component that authenticates. |
| 2026-08-17 | Stratum 0 carries scale-2 currencies only. `PIC S9(13)V99 COMP-3` hard-codes two decimals, so JPY (scale 0) and BHD (scale 3) cannot be represented. Rather than change the picture clause, the constraint is documented and WP-11 rejects such movements before they reach the mainframe - a real limitation of a 1995 domestic core, and the kind of thing this repository exists to reproduce. See F-09. |
| 2026-08-17 | Work packages merge with a merge commit, not a squash. `PROTOCOL.md` sizes commits deliberately at 3-10 per package; squashing would erase the history that rule exists to produce. |
