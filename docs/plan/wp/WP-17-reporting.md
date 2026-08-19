# WP-17 - reporting

| | |
|---|---|
| **Ticket** | TB-1017 |
| **Branch** | `feat/TB-1017-reporting` |
| **Stratum** | 4 - Python 3.12, ~2025 |
| **Depends on** | WP-09 |
| **Status** | `Not started` |

## Objective

Produce the regulatory and management reports a bank must generate from its ledger: daily position,
movement summaries, and the data extracts that regulatory submissions are built from. Batch, not
real-time, because that is what reporting genuinely is.

## In scope

- Daily position report per account and per currency, from the ledger.
- Movement summary with control totals reconciling to the ledger.
- A regulatory extract in a defined, versioned format.
- Report versioning and reproducibility: rerunning for a past date produces the same output.
- Metrics on run duration and record counts.

## Out of scope

- Submission to any actual regulator or external system.
- A BI tool, dashboard or visualisation layer.
- Real-time reporting - this tier is deliberately batch.

## Constraints

- Python 3.12 with `uv`.
- **Reports must be reproducible.** Rerunning for a historical date must produce identical output;
  reports built on mutable state are not auditable, and auditability is the entire purpose.
- Every report carries the ledger position it was generated from, so a figure can always be traced
  back to the postings behind it.
- Reports contain account references, never personal data - joining to customer identity happens
  downstream, outside this repository.
- Control totals on every report must reconcile to the ledger independently.

## Tasks

Detailed 2026-08-19. Three decisions were taken with the repository owner before any code was
written, because each changes what gets built:

- **The ledger position is an audit sequence number.** `audit_record.seq` is the only column in this
  schema where sequence order is *commit* order: `JdbcAuditLog` takes `pg_advisory_xact_lock` before
  reading the chain head and holds it to commit, so if seq P is visible then every seq below it has
  committed. `max(posting.id)` and `journal_entry.created_at` both lack that guarantee - identity
  values and `now()` are assigned when a transaction starts, not when it commits, so a watermark on
  either silently admits rows on a rerun that the first run could not see. Every `journal_entry` has
  exactly one audit row - `TRANSFER_POSTED` or `TRANSFER_REVERSED`, `subject_ref` being the entry
  reference, written in the same transaction as the postings - so the join is total. F-27 records the
  advisory lock as a throughput ceiling; it is also what buys reporting a reproducible cut, and that
  is worth saying in the same breath.
- **The regulatory extract is a fixed-width file with a header and a trailer.** Record counts and
  control totals in the trailer, which is what a regulatory submission genuinely looks like in the
  batch world. Its declared format is a column map checked by a byte-offset validator - the idiom
  `contracts/copybook/column-map.md` and `check-copybook-offsets.py` already establish - so
  "validates against its declared format" is a check that had to be written and demonstrated to fail,
  not a schema library asserting on the repository's behalf.
- **The declared format lives in `contracts/`, not in the component.** The extract is an interface
  this repository publishes outward, so PROTOCOL step 12 applies: the contract lands before the
  implementation that satisfies it, and `contracts/validate.sh` gains the check. This widens the
  branch into WP-02's directory by one new subdirectory, deliberately - F-34 records the gateway
  answering the same question the other way and leaving a consumer unable to find the error surface
  from `contracts/` alone, and repeating that would be repeating a known defect.

**No report reads the `balance` table.** It is a materialised read path that reflects now, and a
report built on mutable state is not auditable - the constraint this package opens with. Every figure
is summed from postings, which are append-only and therefore the same on every rerun. That also makes
the reconciliation in REQ-REP-003 independent rather than circular: the report's own arithmetic and
the ledger's materialised figure are two computations, and the test asserts they agree.

**No wall clock enters a report body.** A generation timestamp would make byte-identical output
impossible by construction. The run instant belongs in the manifest beside the report, never in it.

1. Detail this task list, record the decisions above, and set the package `In progress` in
   `STATUS.md`.
2. **The extract contract.** `contracts/reporting/` with the fixed-width layout - header, detail and
   trailer records - as a column map fixing every field's offset, width and type, plus a validator
   asserting those offsets and the record length. Wired into `contracts/validate.sh`. Demonstrated to
   fail on a resized field and on a changed record length before it is accepted, exactly as WP-02's
   copybook checker was.
3. **Module skeleton and configuration.** `batch/reporting/` with `pyproject.toml` pinned to
   `>=3.12,<3.13` under `uv`, a `src/` layout, `ruff`, and settings read from the environment and the
   command line and validated before anything connects: the ledger DSN, the business date, the output
   directory, and the position when one is being reproduced. An unparseable setting fails the run
   rather than defaulting silently, and every problem is reported at once - the rule the gateway and
   fraud-scoring both follow, for the same reason.
4. **Money without a float.** Minor units as `int`, an ISO 4217 code, and the scale resolved per
   currency so JPY (0) and BHD (3) are both right. Formatting for a fixed-width field is part of this
   type, not of the writer. A test asserts the module contains no `float`, no `Decimal` and no
   division, the way the ledger scans its own sources for `BigDecimal`.
5. **The ledger reader.** A read-only `REPEATABLE READ` transaction that resolves the position - the
   audit high-water mark and the chain head hash - and reads every row of the run inside that one
   snapshot. `SET TRANSACTION READ ONLY` because a reporting job that can write to the ledger is a
   reporting job that will. Tested against real PostgreSQL through Testcontainers, with the ledger's
   own Flyway migrations applied in order from
   `services/ledger-persistence/src/main/resources/db/migration/`: a reader proved against a
   hand-written schema is verified against a fiction, and this one fails the day WP-07's schema moves.
6. **The daily position report.** One row per account and currency: booked balance as at the business
   date, summed from postings of entries with `value_date <= businessDate` and audit `seq <=
   position`, signed by the account type's normal balance rather than by the posting direction alone.
   Per-currency control totals in a trailer, and the independent reconciliation - the report's
   arithmetic against the ledger's own `balance` rows - as a test rather than a claim.
7. **The movement summary.** Every posting with `value_date = businessDate` inside the position, with
   per-currency control totals: debit count and total, credit count and total. Debits must equal
   credits per currency - the double-entry identity is what proves no movement was dropped between
   the query and the file, so the report fails rather than prints when it does not hold.
8. **The regulatory extract.** The writer for the format declared in task 2, validated by the same
   checker the contract ships. Account references, customer references, types, currencies, balances
   and statuses - and a test that greps the output for anything shaped like a name, an address, a
   national identifier or an IBAN, because "no personal data" is a Definition of Done box and a grep
   is the only thing that can tick it honestly.
9. **Reproducibility, proven rather than asserted.** Run for a business date, capture the position,
   post a further transfer *and* a backdated one, rerun at the captured position, and assert the
   three output files are byte-identical. The backdated entry is the case a naive `value_date` filter
   gets wrong, so the test is demonstrated to fail when the position filter is removed.
10. **Observability.** Run duration, rows read and records written per report, exported through the
    node_exporter textfile format - the honest answer for a batch job, which is gone before any
    scrape could reach it. JSON logs carrying the business date, the position and the account
    references, never the remittance `reference_text`.
11. **Toolchain and documentation.** Makefile targets beside the fraud-scoring ones, the component
    README, the traceability rows for REQ-REP-001, 002 and 003, and ADR 0009 recording the audit
    watermark.
12. **Verification and landing.** `uv run pytest`, then the live checks the package names: generate
    for a seeded date, reconcile the totals against a direct ledger query, rerun and diff, and grep
    the output. Actual output into the pull request.

## Definition of Done

- [ ] Each report generates and its control totals reconcile to the ledger.
- [ ] Rerunning for a past date produces byte-identical output.
- [ ] The regulatory extract validates against its declared format.
- [ ] No personal data appears in any output.

## Verification

`uv run pytest`. Then generate reports for a seeded date, confirm totals reconcile to a direct ledger
query, rerun and confirm identical output, and grep the output for anything resembling personal data.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-REP-001 Regulatory reports are generated from the ledger | reporting jobs |
| REQ-REP-002 Reports are reproducible for historical dates | versioned, position-stamped runs |
| REQ-REP-003 Report totals reconcile independently to the ledger | control totals |
