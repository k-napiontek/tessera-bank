# STATUS

The single source of truth for what is done and what comes next. **Read this first, every session.**

Updated by the executing session at the start and end of every work package, per
[`PROTOCOL.md`](PROTOCOL.md).

---

## Next actionable package

> **WP-08 - Ledger API** is next: the lowest-numbered package whose dependencies are all `Done`
> (WP-07). Its task list still reads "To be detailed before execution". It needs the same running
> Docker daemon WP-07 needs, for the contract and integration tests.
>
> WP-08 is where the pieces WP-07 proved individually get composed into a transfer: lock the accounts
> in order, consult `OverdraftPolicy`, append the entry. Nothing does that yet - see F-22.
>
> **Stratum 0 is complete** and the overnight cycle runs end to end locally (`make eod`). WP-16 waits
> on WP-11, which waits on WP-09 and WP-10.
>
> Also unblocked: **WP-10** (stratum 1), still **blocked on tooling** - only `openjdk@17` and
> `openjdk@26` are installed and it needs JDK 8. See F-02 and F-10.

---

## Work packages

Status values: `Not started` | `In progress` | `Blocked` | `Done`

| WP | Title | Stratum | Depends on | Status | PR | Merge SHA |
|---|---|---|---|---|---|---|
| [01](wp/WP-01-foundation.md) | Repo foundation, governance docs, plan system, Claude Code config | - | - | `Done` | n/a - pre-git | n/a |
| [02](wp/WP-02-contracts.md) | Canonical data model and contracts - copybook, WSDL/XSD, OpenAPI, AsyncAPI | - | 01 | `Done` | [#1](https://github.com/k-napiontek/tessera-bank/pull/1) | `4044e07` |
| [03](wp/WP-03-mainframe-data.md) | Mainframe copybooks and synthetic master/movement data | 0 | 02 | `Done` | [#8](https://github.com/k-napiontek/tessera-bank/pull/8) | `9db131d` |
| [04](wp/WP-04-acctpost.md) | `ACCTPOST.CBL` - balanced-line match-merge | 0 | 03 | `Done` | [#11](https://github.com/k-napiontek/tessera-bank/pull/11) | `9e9e44e` |
| [05](wp/WP-05-eodrept.md) | `EODREPT.CBL`, `EODCYCLE.JCL`, local runner | 0 | 04 | `Done` | [#16](https://github.com/k-napiontek/tessera-bank/pull/16) | `f05219d` |
| [06](wp/WP-06-ledger-domain.md) | Ledger domain - pure Java, no Spring, property tests | 3 | 02 | `Done` | [#5](https://github.com/k-napiontek/tessera-bank/pull/5) | `e67dc3e` |
| [07](wp/WP-07-ledger-persistence.md) | Ledger persistence - schema, migrations, locking, Testcontainers | 3 | 06 | `Done` | [#19](https://github.com/k-napiontek/tessera-bank/pull/19) | `eb538ca` |
| [08](wp/WP-08-ledger-api.md) | Ledger API - transfers, idempotency, Problem Details, contract test | 3 | 07 | `In progress` | | |
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
| F-10 | WP-06 | The development machine had no JDK and no GnuCOBOL. JDK 17 (`openjdk@17`) and Gradle 9.7 were installed for WP-06. **GnuCOBOL 3.2.0 is now installed**, and WP-03, WP-04 and WP-05 have all been executed and verified against it. **JDK 8 is still missing**, so WP-10 and WP-11 cannot be. `openjdk@17` is keg-only, so `JAVA_HOME` must point at `/opt/homebrew/opt/openjdk@17` when invoking Gradle. | Open - JDK 8 half only, 2026-08-18 |
| F-11 | WP-02 | The requirement ids in the traceability matrix collided with the catalogue the work packages already defined - 14 genuine collisions, introduced by WP-02 task 8. Fixed in [#3](https://github.com/k-napiontek/tessera-bank/pull/3), which also added a catalogue index of all 60 ids so the mistake cannot repeat. | **Closed** - 2026-08-17 |
| F-12 | WP-06 | The Definition of Done's ADR box was left unticked. Two decisions in WP-06 are arguably architecturally significant - `Account` stores no balance, and `Money` arithmetic throws on overflow rather than wrapping - and both are recorded in [#5](https://github.com/k-napiontek/tessera-bank/pull/5) and `services/ledger-core/README.md` but not as ADRs. Together with F-09 there are now two candidate ADRs outstanding. | Open |
| F-13 | WP-06 | `openjdk@17` is keg-only on this machine, so `./gradlew` needs `JAVA_HOME=/opt/homebrew/opt/openjdk@17` unless 17 is the default JVM. `docs/consuming-this-repo.md` names JDK 17 as a prerequisite but does not mention this. Worth a line there when that document is next revised. | Open |
| F-14 | WP-03 | WP-03's **In scope** section describes an `ACCTREC` that predates the canonical data model - account number `PIC X(10)`, status `PIC X(1)`, no customer reference or account type. The contract in `contracts/copybook/` supersedes it, as WP-03's own Constraints require, and the task list records this. The In-scope text itself was left unedited so the original intent stays visible; it should be reconciled when WP-18 does its documentation pass. | Open |
| F-15 | WP-03 | The task list said to add the GnuCOBOL prerequisite to `docs/consuming-this-repo.md`. That document is still a stub owned by WP-18 (F-05) and already names GnuCOBOL under "Planned contents", so filling in part of it would have widened the branch into another package's work. The concrete prerequisites and build commands went into `mainframe/README.md` instead. `consuming-this-repo.md` still needs writing. | Open |
| F-16 | WP-04 | `mainframe/data/check-records.py` gained a `--skip-coverage` flag. Its fixture-coverage assertions - that the data contains a positive, a negative and a zero amount - are a requirement on *generated* data, not on the output of a batch run, where the account holding zero may legitimately have been moved away. The flag names which of the two jobs the tool is doing. If more tools end up serving both purposes, the split is worth making explicit rather than adding more flags. | Open |
| F-17 | WP-01 | **Nothing stops the foundation documents going stale again.** `README.md` claimed "no application source code has been written yet" through four merged packages, and `make test` said the same, because the Definition of Done only requires a *directory* README to be updated when that directory changes - and the root README, the Makefile and the compliance banners belong to no package. Corrected in [#13](https://github.com/k-napiontek/tessera-bank/pull/13), but the gap that allowed it is still open. WP-18 should either extend the Definition of Done to cover repository-level documents or add a check that fails when they contradict `STATUS.md`. | Open |
| F-18 | WP-05 | **The synthetic movement file rejects 54% of its records.** `build_movements` in `mainframe/data/generate.py` hard-codes `PLN` on every leg, while `build_master` draws from `["PLN","PLN","PLN","EUR","USD"]`. Every movement landing on a EUR or USD account rejects `R003`, and movements are drawn without regard to account status, so 48 more reject `R002`. On the full run 162 of 302 movements reject and only 140 post. Nothing is broken - the rejections are correct - but the cycle's happy path is thinly exercised on real data, multi-currency posting is never exercised at all, and WP-16 will inherit a master that barely moved. Fixing it means changing the generator, which is WP-03's file and outside WP-05's scope. |
| F-19 | WP-05 | **`0x0A` cannot occur in a COMP-3 field, but `CLAUDE.md` and `ACCTPOST.CBL` both say it can.** Every nibble of a packed field is a digit except the last, which is the sign - `C`, `D` or `F` - so the byte `0x0A` is unreachable. `0x0D` is reachable and common: any negative amount whose final digit is zero packs to a trailing `0x0D`. The conclusion those comments draw is right and the reasoning is half wrong, which is worse than either. The stronger argument is not mentioned at all: a fixed-width record is padded with `0x20`, and line sequential strips trailing spaces, so every record would lose its length. Affects the trap in `CLAUDE.md`, the comment block in `ACCTPOST.CBL` and WP-04's task list. |
| F-20 | WP-05 | **`test-acctpost.py` hides compiler warnings.** It compiles with `capture_output=True` and never inspects the result, so a `cobc -Wall` warning in `ACCTPOST.CBL` is invisible in a suite that prints thirteen lines of `PASS`. Exactly that hid an `arithmetic-osvs` warning in `EODREPT.CBL` until a compile was run by hand. `test-eodrept.py` now fails on any compiler output; `test-acctpost.py` should do the same, but it is WP-04's file. |
| F-21 | WP-07 | **`Hold.transitionTo` requires an `Instant` it never keeps.** `capture`, `release` and `expire` all demand a non-null `at`, validate it, and then construct the new `Hold` from `placedAt` - the transition instant is discarded. So a released hold cannot say when it was released, and the persistence adapter has to pass a value it knows is ignored in order to rehydrate one. Either the field should be stored on the aggregate, which is what an audit trail would want, or the parameter should go. `Hold` is WP-06's type and WP-07 must not add a persistence-shaped back door to it, so the adapter passes `placedAt` and a round-trip equality test proves the value cannot matter. WP-09 will care about this when it builds the audit chain. |
| F-22 | WP-07 | **There is no `transfer` use case yet, so nothing composes locking with the overdraft policy.** WP-07 supplies `AccountLocks`, the repositories and the reconciliation, and proves each holds under concurrency, but `OverdraftPolicy.permits` is never consulted on the persistence path - `append` will write an entry that takes a forbidden-overdraft account below zero. That is correct for this package: the port has no transfer method and WP-08 owns the service. Worth stating plainly because a reader could otherwise conclude the ledger enforces overdraft policy at the database, and it does not. |
| F-23 | WP-07 | **`services/ledger-persistence` has no linter, and neither does `ledger-core`.** `-Xlint:all -Werror` catches compiler warnings, and nothing checks style, formatting or the static-analysis rules `quality/` was created to hold. F-03 covers the empty `quality/` directory generally; this records that the Java tier now has two modules and roughly 2,000 lines with no gate beyond javac. |

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
| 2026-08-18 | `make build`, `make test` and `make lint` now run the real per-tier checks instead of printing a stub. Tiers with no code report that per tier: "nothing here yet" and "nothing anywhere" are different statements, and conflating them is how a session concludes the repository is empty. `make jdk17` locates a JDK 17 and names `brew install openjdk@17` when there is none, because `openjdk@17` is keg-only and Gradle's own error reads like a broken build. |
| 2026-08-18 | `CLAUDE.md` gained a **Traps that have already been caught here** section: only mistakes actually made in this repository, each tagged with the package that made it. The entry bar is that the mistake produced a *confident wrong answer* rather than an obvious failure - a tips list nobody reads is worse than nothing. |
| 2026-08-18 | The overnight cycle has **two** SORT steps, not the one WP-05's In-scope section names. `ACCTPOST` writes the new master in account-reference order because that is the order the match-merge consumes it in, while `EODREPT` control-breaks on currency. A control-break report over a file sorted by a different field emits a subtotal at nearly every record - figures that look like subtotals and are nonsense. A report sequence is a sort step, which is how DFSORT is used in practice, and it keeps `EODREPT` a single sequential pass like everything else in the tier. The alternative, letting the report sort for itself, is a report that holds the master. |
| 2026-08-18 | **The end-of-day report prints no cross-currency total.** WP-05 asks for "a grand total"; adding 100 PLN to 100 EUR produces a figure that means nothing and that no auditor would accept. The grand total counts accounts, money figures stay per currency in the subtotals and the closing recap, and the report states on the page that no cross-currency amount is printed. Printing a summed figure would be the report equivalent of the WP-04 `V99` truncation: plausible-looking and simply wrong. |
| 2026-08-18 | The cycle **refuses to apply the same movement file twice**. On success `run-eod.sh` writes a marker holding the file's SHA-256 and the business date; a second run with the identical file for the same date exits 8 unless `--rerun` is passed. A corrected file re-sent for the same date is allowed, because that is normal operations. Applying a day's movements twice doubles every posting in the bank, and "the operator would notice" is not a control. |
| 2026-08-18 | Stratum 0's **subtotal accumulators are `PIC S9(15)V99`**, two digits wider than the `S9(13)V99` balances they sum, and the report's money columns are 15 digits wide so totals print under the same columns with the same picture. Not defensive: the synthetic data's PLN total is `10,000,074,741,234.88` - 14 digits - because WP-03 deliberately seeds an account at the maximum representable balance. A 13-digit accumulator would have truncated it in silence. |
| 2026-08-18 | The ledger's persistence lives in a **separate module**, `services/ledger-persistence`, rather than beside the domain in `ledger-core`. That module carries no framework on its compile classpath, so a Spring import there fails to compile rather than failing a rule, and `DomainPurityTest` scans every source in it. Adapters in the same module would have forced that scan to be narrowed to the domain package - weakening a control that works, to make room for new code. ArchUnit still has teeth because the persistence module holds both sides on its classpath. This changed WP-07's stated verification command from `:services:ledger-core:test`. **Strongest outstanding ADR candidate; see F-09, F-12.** |
| 2026-08-18 | A ledger balance is **derived two independent ways on purpose**. `balanceOf` reads the materialised `balance` row - the fast path an API call takes - and `BalanceReconciliation` sums the postings in SQL, reimplementing the sign convention that `AccountType.signedEffect` holds in Java. The duplication is the control: a check written against the same code it checks proves nothing, and if `balanceOf` summed the postings the reconciliation would compare a number to itself. This does not contradict WP-06's "`Account` stores no balance" - the aggregate holds none; the database materialises one and is then held to account for it. |
| 2026-08-18 | **Locking more than one account goes through `AccountLocks.lockInOrder`, which sorts by reference.** The port locks one account at a time and widening it to suit an adapter would invert the dependency the architecture protects, so the rule lives in the persistence module. The order is arbitrary; that it is the same order every time is the whole mechanism. Proven by deleting the single `.sorted(...)` line and rerunning the ring test, which produced five `deadlock detected` errors from PostgreSQL - the rule is load-bearing, not decorative. |
| 2026-08-18 | A PostgreSQL trigger function must address its tables through **`TG_TABLE_SCHEMA`**, never by an unqualified name. A function body resolves unqualified names against the *caller's* `search_path`, not the schema it was created in, so the balanced-entry trigger worked during migration and failed the moment it was called from a connection with a different `search_path`. |
| 2026-08-17 | Stratum 0 has no arranged-overdraft concept, because `ACCTREC` carries no limit field. `ACCTPOST` therefore rejects any debit that would take a `LIABILITY` account's booked balance below zero, and never rejects a credit on balance grounds - an account can already be negative from legacy state. The same rule WP-06 arrived at from a failing test, reached independently at this tier. |
| 2026-08-17 | Every intermediate money field in COBOL must carry the same `V99` scale as the money it holds. `WS-EFFECT PIC S9(15)` silently truncated every amount to whole units in WP-04, so a debit of 100.01 against 100.00 computed to zero and an overdraft rejection never fired. Only a test caught it; the run output looked plausible. |
| 2026-08-17 | Stratum 0 compiles with `cobc -std=ibm`, not `-std=cobol85`. `COMP-3` is an IBM extension; strict ANSI COBOL-85 spells packed decimal `PACKED-DECIMAL` and rejects `COMP-3` outright, which the WP-03 compile harness discovered on its first run. Both spellings produce identical bytes, and every banking COBOL program writes `COMP-3` - so the copybooks keep `COMP-3` and the compiler is told which dialect that is. Changing the contract to satisfy a stricter flag would have made the code less like the thing it reproduces. |
| 2026-08-17 | A currency whose ISO 4217 scale is not 2 appears in stratum 0 data **only as a movement destined for rejection**, never as an account in the master. `PIC S9(13)V99 COMP-3` hard-codes two decimals, so a JPY balance would be misstated a hundredfold. The integration tier rejects such a movement before it arrives and the mainframe validates it again - defence in depth, because a 1995 core does not trust its feeds. |
| 2026-08-17 | Credits into an overdrawn account are never blocked, even past the overdraft limit. An account can reach that state legitimately through fees or a reduced limit, and refusing a repayment would be absurd. Only an effect that worsens the position is subject to the policy. Driven out by a failing test in WP-06. |
| 2026-08-17 | `Account` stores no balance. A balance is derived from postings; storing one would create a second source of truth on day one - the exact drift `batch/recon` exists to detect. See F-12. |
| 2026-08-17 | `JournalEntry` rejects mixed-currency entries outright. WP-06's invariant 3 allowed "no mixed-currency entry without an explicit FX leg" while the canonical model states single currency with no conversion anywhere. Rejecting outright satisfies both: if no mixed-currency entry can exist, none exists without an FX leg. FX belongs to `payment-engine`, out of initial scope. |
| 2026-08-17 | The OpenAPI contract declares a bearer security scheme. Redocly's `security-defined` rule failed the lint with 11 errors otherwise, and the alternative - adding a config file to switch the rule off - would weaken a validator to hide a real gap. The contract states what it expects; `edge/api-gateway` remains the only component that authenticates. |
| 2026-08-17 | Stratum 0 carries scale-2 currencies only. `PIC S9(13)V99 COMP-3` hard-codes two decimals, so JPY (scale 0) and BHD (scale 3) cannot be represented. Rather than change the picture clause, the constraint is documented and WP-11 rejects such movements before they reach the mainframe - a real limitation of a 1995 domestic core, and the kind of thing this repository exists to reproduce. See F-09. |
| 2026-08-17 | Work packages merge with a merge commit, not a squash. `PROTOCOL.md` sizes commits deliberately at 3-10 per package; squashing would erase the history that rule exists to produce. |
