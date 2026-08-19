# WP-22 - Ledger data volume

| | |
|---|---|
| **Ticket** | TB-1022 |
| **Branch** | `feat/TB-1022-ledger-data-volume` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-20, WP-09 |
| **Status** | `Not started` |

## Objective

Put a production-shaped amount of data into the ledger, so that its queries meet real cardinality
for the first time.

The ledger currently holds about three accounts, opened by `walkthrough.sh`. Every query in this
repository has only ever run against a fixture, and the plans they get at that size say nothing
about the plans they will get at a year of postings. F-24 already records the specific consequence:
the statement's keyset seek orders by columns that live on `journal_entry` while the rows come from
`posting`, so the sort happens after the join and one index narrows it - "worth doing before any
account has a large history". At three accounts that is invisible. This package makes it measurable,
and gives F-24 the evidence it asks for.

Volume is also what makes WP-23's baseline mean anything. A recorded normal captured against a
fixture is a recorded normal of the fixture.

## In scope

- A loader that takes a WP-20 population and writes a ledger of a few hundred thousand accounts and
  roughly a year of postings.
- The audit chain and the materialised `balance` rows written correctly by the loader, not left for
  something else to reconstruct.
- Verification by the ledger's **own** controls run against what the loader produced.
- Query-plan evidence at volume for the statement page, the balance read and the reporting queries.
- A documented, repeatable way to produce the same dataset again from the same seed.

## Out of scope

- Any change to the schema. A missing index discovered here is a finding against WP-07's migration
  set and is logged, not added on this branch.
- Driving traffic - WP-21.
- SLOs and baselines - WP-23.
- Loading the Oracle system of record or the COBOL master. Those are WP-25's, and they need WP-10b
  and WP-11 first.

## Constraints

- **It loads by `COPY`, not through the API.** Millions of postings through `POST /transfers` would
  take days, would serialise on the audit chain's advisory lock, and would measure the driver rather
  than the database. Bulk load is what a bank does to stand up a test environment, and it is the
  honest tool here.
- **A bulk loader that skips the audit row corrupts the estate silently.** F-43 records that every
  report bounds its postings by joining `journal_entry` to `audit_record` on `subject_ref`; entries
  without an audit row become invisible to every report, without breaking a single test in
  `services/`. The loader therefore writes the chain - correctly hashed, correctly linked - or it is
  producing a ledger that lies to `batch/reporting`.
- **The loader's verification is the ledger's existing controls, not the loader's own arithmetic.**
  `BalanceReconciliation` sums the postings in SQL independently of the materialised `balance` rows,
  and `AuditChain.verify()` walks the chain. A loader checked only against itself proves nothing,
  which is the same argument the decision log makes for deriving a balance two independent ways.
- **Every entry balances, and every posting respects the sign convention.** The database already
  enforces the first with a trigger. A loader that has to disable a constraint to finish is a loader
  that is writing data the ledger would have refused.
- Money is minor units. No `double`, no `BigDecimal` on the amount path.
- No personal data: references only, drawn from the WP-20 population.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] A loaded ledger passes `BalanceReconciliation` for every account.
- [ ] `AuditChain.verify()` passes over the whole loaded chain.
- [ ] No constraint or trigger was disabled to complete a load.
- [ ] The same seed produces the same dataset, checked by a digest rather than by row counts alone.
- [ ] `batch/reporting` runs against the loaded ledger and its control totals reconcile.
- [ ] The statement page's query plan at volume is captured and recorded, with the row counts it was
      measured at.

## Verification

Load a dataset, then run the ledger's own reconciliation and chain verification against it. Run
`batch/reporting` for a business date inside the loaded range and confirm the extract's control
totals reconcile. Capture `EXPLAIN (ANALYZE, BUFFERS)` for the statement page at the first page and
at a deep cursor, and record both.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-004 Query cost is measured at production cardinality, not at fixture size | the loaded dataset and the captured plans |
