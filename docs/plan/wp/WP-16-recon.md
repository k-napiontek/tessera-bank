# WP-16 - Reconciliation

| | |
|---|---|
| **Ticket** | TB-1016 |
| **Branch** | `feat/TB-1016-recon` |
| **Stratum** | spans 0 and 3 |
| **Depends on** | WP-05, WP-11b |
| **Status** | `In progress` - see `STATUS.md` |

## Objective

Compare the COBOL account master against the PostgreSQL ledger every morning and report any drift.
This is the safety net that makes strangler-fig modernisation survivable: while two systems both
believe they hold the truth about a customer's money, the only defensible position is to check them
against each other daily and investigate every difference. Without it, the migration is a guess.

## In scope

- A job reading the COBOL master file, decoding COMP-3, and comparing balances account by account
  against the ledger.
- A break report classifying each difference: timing, missing on one side, or genuine value drift.
- The break report as a **contract artefact**, in a form `backoffice` renders. Building that screen
  is WP-15.
- Control totals: accounts compared, matched, broken, and total absolute drift.
- Alerting hooks and metrics so the platform repositories can act on breaks.

## Out of scope

- Automatic correction of breaks. A reconciliation that silently fixes differences destroys the
  evidence of why they occurred - breaks are investigated by humans, never auto-healed.
- The scheduler that runs it - platform repositories.

## Constraints

- Read-only against both systems. This job never writes to the ledger or the master.
- **Timing differences must be distinguished from genuine drift.** A movement posted after the
  mainframe cut-off is expected and is not a break; classifying it as one trains operators to ignore
  the report, which is worse than having no report.
- The comparison must be deterministic and reproducible from the same two inputs.
- A break report of zero breaks must still be produced and recorded - absence of output is
  indistinguishable from a failed job.

## Tasks

Detailed 2026-08-20. **Nine tasks on one branch**, `feat/TB-1016-recon`. Python 3.12 under `uv` in
`batch/recon`, which today holds a README and nothing else.

**Taken before WP-15 by explicit instruction of the repository owner**, which is the mechanism this
plan's decision log records for work outside the plan's order. The two packages are entangled as
framed: WP-15's break list needs breaks only WP-16 produces, and WP-16's "surfaced to `backoffice`"
needs a screen only WP-15 builds. Building the data before the screen is the order that leaves the
second package something real to render. The seam is task 2's contract, and this package **gives up
the `backoffice` box in its own Definition of Done** rather than pretending to tick it.

Three decisions are taken here rather than discovered mid-branch.

- **The cut-off is the movement file, not a timestamp.** Every document in this repository refers to
  "the mainframe cut-off" and no document defines it. A wall clock cannot define it: the ledger and
  the cycle do not share one, and a report cut at a time is irreproducible the moment either side's
  clock moves. But the cycle's input is a file, and that file names every transfer it carried in
  `MOV-TRANSFER-REF`. **The set of transfer references in the movement file the cycle consumed is the
  cut-off.** A ledger entry whose reference is in that file must be in the master; one whose
  reference is not is expected to be absent, and is *timing* rather than drift. The answer is exact,
  derived from the two inputs alone, and unchanged by rerunning either side - which is what the
  Constraints section means by deterministic and reproducible. This is architecturally significant
  and the estate has never written it down, so it gets **an ADR**, 0015. It is the same file
  answering a second question, and [ADR 0014](../../governance/adr/0014-the-movement-file-is-its-own-unique-constraint.md)
  is the first.
- **The ledger side is cut at an audit position**, exactly as
  [ADR 0009](../../governance/adr/0009-reports-are-cut-at-an-audit-position.md) decided for
  reporting: `seq <= position`, carrying the chain hash so a database restored onto a divergent
  history is detectable rather than merely unlikely. Balances are **summed from postings**, never
  read from the `balance` table - a reconciliation built on the materialised figure is comparing the
  mainframe against a cache of the thing it is supposed to be checking.
- **This package reads the ledger with its own reader.** `batch/reporting` has a `LedgerReader` that
  is close to what is needed here and it is deliberately not reused. `accounting.py` already states
  the estate's reasoning for the same situation - "a report that asked the ledger which way its own
  figures ran would be reconciling nothing" - and it applies with more force to a reconciliation
  than to a report. Two jobs asking one question through one reader agree by construction, and by
  construction is not by check. The cost is a second reader and it is the point rather than the
  price.

1. **The package skeleton.** `batch/recon` under `uv`, mirroring `batch/reporting` because the tier
   is the same one: `requires-python = ">=3.12,<3.13"` as a range and not a floor, src layout,
   `pytest` with `--import-mode=importlib`, `testcontainers[postgres]`, `ruff` with the same rule
   selection - `DTZ` earns its place here twice over, since a business date read as a local date
   reconciles the wrong day. `make test-recon` wired into `test-batch`, and a toolchain test that
   pins the interpreter rather than trusting the lockfile.
2. **The break report contract, before anything writes one.** `contracts/recon/break-report-v1.md`.
   The break report crosses a stratum boundary - WP-15 renders it from stratum 1 - so it is a
   contract artefact and not a README section, which is the lesson F-34 and F-51 have both recorded
   about fault and error surfaces. It defines the break types, the fields carried per break, the
   control-total block, and the rule that **a zero-break run still emits a report**. A contract test
   parses the document and holds the writer to it, as `check-extract-layout.py` does for the
   regulatory extract. Write this task's commit **before** task 6's.
3. **Read the account master.** `ACCTREC`, 100 bytes, layout taken from
   `contracts/check-copybook-offsets.py --json ACCTREC` - the view WP-11b added - rather than
   transcribed and asserted to agree. `ACCT-BOOKED-BAL` and `ACCT-AVAIL-BAL` are COMP-3 and the
   decoder is **held to the mainframe's own fixtures byte for byte**, including zero, the maximum
   representable balance and a negative, exactly as `Comp3Test` holds the encoder. A third
   implementation of COMP-3 is deliberate: `comp3.py` and `Comp3.java` already disagree with each
   other or they do not, and a decoder that imported one of them would inherit its mistakes. A file
   that is not a whole number of 100-byte records is refused, not partially read.
4. **Read the ledger.** A read-only connection, its own narrow query, balances summed from postings
   with the normal-balance rule applied, resolved as at a `Position`. Reruns against the same
   position produce the same figures; the test that pins this runs twice and compares. The
   connection is granted nothing but `SELECT`, and a test asserts a write fails - "read-only against
   both systems" is a Constraint, and a constraint nothing tests is a comment.
5. **The comparison and its classification.** Account by account, one pass over two sorted inputs -
   the master is already ascending by `ACCT-REF` and the ledger query orders to match, so this is a
   match-merge and not a pair of dictionaries, for the same reason WP-04's is. Four outcomes:
   **matched**, **value drift**, **missing on one side** (which side, named), and **timing**, per the
   cut-off rule above. Nothing is ever corrected: the comparison returns breaks and holds no write
   path to either system, which is REQ-REC-003 enforced by the shape of the code rather than by
   discipline.
6. **Control totals and the report.** Accounts compared, matched, broken, and total absolute drift,
   written per the task 2 contract. The totals must **balance against each other** - compared equals
   matched plus broken - and a run that finds nothing still writes a report and records it, because
   absence of output is indistinguishable from a job that never ran.
7. **Metrics and alerting hooks.** A Prometheus textfile, as `reporting/observability.py` writes one,
   so the platform repositories have something to alert on: breaks by classification, total absolute
   drift, and the run's own success. A batch job has no endpoint to scrape, which is the same problem
   `reporting` already solved here.
8. **The whole spine, end to end.** Post transfers through the ledger, let the ESB write the movement
   file, run the **real** overnight cycle, then reconcile and assert zero breaks. Then inject the
   three faults the Verification section names - a value discrepancy, an account missing from the
   master, and a movement posted after the cut-off - and assert each is detected and classified
   correctly. The post-cut-off case is the one that matters: it must come out **timing**, and a test
   that lets it come out as drift is the one that trains operators to ignore the report.
9. **Documentation and landing.** ADR 0015 for the cut-off. Fill
   [`docs/runbooks/reconciliation-break.md`](../../runbooks/reconciliation-break.md), which has been
   a stub naming WP-16 as its owner since the foundation commit, against the behaviour the tests
   assert rather than against intent. Traceability for REQ-REC-001, REQ-REC-002 and REQ-REC-003,
   `batch/README.md`, `batch/recon/README.md`, `STATUS.md`, pull request, merge.

**Out of scope and logged, not fixed:** the `backoffice` half of the seam is WP-15's, and the plan's
dependency table hides the cycle between the two packages - WP-15 is recorded as depending on WP-10
alone. That is a plan defect and goes to Follow-ups rather than onto this branch.

## Definition of Done

- [ ] A clean run over consistent data reports zero breaks and produces a report.
- [ ] A deliberately injected discrepancy is detected and correctly classified.
- [ ] A post-cut-off movement is classified as timing, not drift.
- [ ] Breaks are written in a form `backoffice` can render, per the task 2 contract. **Rendering
      them is WP-15's box, not this one's** - see the Tasks preamble on the seam between the two.
- [ ] Control totals balance.

## Verification

```bash
make test-recon                                  # the new tier, against real PostgreSQL
make test-batch                                  # reporting is still green beside it
make eod                                         # the cycle this reconciles against still runs
bash contracts/validate.sh                       # the break-report contract validates
make test                                        # every other tier still green
```

Needs Docker and GnuCOBOL: the end-to-end task runs the real overnight cycle against a real ledger.

End to end: post transfers through the ledger, let the ESB write the movement file, run the WP-05
cycle, reconcile, and confirm **zero breaks with a report still produced**. Then inject three faults -
a value discrepancy, an account missing from the master, and a movement posted after the cut-off -
and confirm each is detected and classified correctly. The third is the one that decides whether this
package is worth having: it must classify as **timing**, not drift.

**Read-only, proved rather than asserted.** The reconciliation's database role is granted `SELECT`
only, and a test attempts a write and requires it to fail.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-REC-001 Old and new cores are reconciled every cycle | reconciliation job |
| REQ-REC-002 Timing differences are distinguished from genuine drift | break classification |
| REQ-REC-003 Breaks are investigated, never auto-corrected | read-only design |
