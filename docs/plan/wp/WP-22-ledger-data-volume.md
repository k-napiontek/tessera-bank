# WP-22 - Ledger data volume

| | |
|---|---|
| **Ticket** | TB-1022 |
| **Branch** | `feat/TB-1022-ledger-data-volume` |
| **Stratum** | 3 - Java 17, ~2023 |
| **Depends on** | WP-20, WP-09 |
| **Status** | `In progress` |

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

1. Set the package `In progress` in `STATUS.md` and branch from up-to-date `main`, per
   [`PROTOCOL.md`](../PROTOCOL.md).

2. **Emit the population as a stream, rather than drawing it twice.** The dataset is WP-20's day
   repeated over a year, and the cohort mix, the log-normal amounts and all four reference formats
   already live in `workload/internal/population`. A new pure package `internal/dataset` renders the
   actions the model draws for a range of business dates as NDJSON, and `cmd/workload-dataset` supplies
   the dates and owns stdout - so `internal/purity`'s forbidden list still applies to it, and
   `TestEveryPackageIsClassified` fails until it is put on the engine side of the line. `--customers`
   overrides the model's population size and is validated by `population.New`, so a count that does
   not divide the cohorts into whole people is refused rather than rounded into a population the
   model never described. The same seed and range must produce byte-identical output, compared as
   bytes and not as structs, exactly as WP-20 pins its schedule.

3. **A loader beside the schema, not beside the model.** `services/ledger-loader`: a new Gradle
   module on Java 17, depending on `ledger-core` and `ledger-persistence`, wired into
   `settings.gradle.kts` and into the Makefile's `build-services` and `test-services`. It reads the
   NDJSON on stdin and renders `account`, `journal_entry`, `posting` and `balance` rows. Two rules
   decide where it lives and they are the same rule: **nothing here restates what another module
   already knows.** Balances are derived through `AccountType.signedEffect` rather than through a
   second copy of the sign convention, and the audit canonical form in task 6 is `AuditEntry`'s.
   Writing the loader in `workload/` would have needed one or both copied, since that module is
   standard-library-only Go whose purity check forbids `database/sql` outright - the duplication
   **F-61** and **F-66** record rotting, in a place where a disagreement would be silent.

4. **A loader that has to be refused is writing data the ledger would have refused.** Every customer
   account is opened with a funding entry from the treasury the population generates, and the loader
   carries a running balance so that a drawn transfer which would take an account below zero is
   **skipped and counted** rather than written. Nothing in the schema stops a negative balance -
   `OverdraftPolicy` is the domain's and the bulk path does not go through it - so this is the only
   thing standing between the dataset and a ledger full of rows `Transfer` would have rejected. The
   counter goes in the manifest, in the shape WP-21 gave `tessera_workload_currency_substituted_total`
   for **F-72**: a substitution that is counted is a stated limitation, and one that is not is a
   number nobody can defend.

5. **Holds and reversals, because the balance read is one of the plans.** `placeHold` writes a
   `PLACED` hold; a later `captureHold` or `releaseHold` transitions the oldest open hold on that
   account, and a capture writes the entry it captured into with `hold.captured_by` set - which V2's
   `hold_captured_by_consistent` asserts either way round. `reverseTransfer` writes a reversing entry
   with `journal_entry.reverses` set, against `journal_entry_reverses_uq`. Holds are not decoration
   here: the Definition of Done asks for the **balance read's** plan at volume, `Balance.of` is booked
   less active holds, and a plan captured against a ledger holding no holds is a plan of the fixture
   this package exists to replace. An operation naming a hold that does not exist yet is skipped and
   counted with the rest.

6. **Without the audit chain the data is invisible to reporting, and nothing here would say so.**
   One `ACCOUNT_OPENED` row per account, one row per entry and per hold transition, each with
   `subject_ref` set to the reference the report joins on. **F-43** is the whole of this task: every
   query in `batch/reporting` bounds its postings by joining `journal_entry` to `audit_record` on
   `subject_ref` with `seq <= position`, so entries loaded without a chain row are not wrong - they
   are absent, from every report, without breaking a single test in `services/`. The chain is
   computed sequentially through `AuditEntry.hashWith`; no advisory lock is needed because the loader
   is the only writer, which is the one thing bulk loading makes easier rather than harder. A planted
   edit must break `AuditChain.verify()` at the row it was planted in.

7. **`COPY`, and a commit per business date.** Streamed through pgjdbc's `CopyManager` in the order
   the foreign keys require - accounts, entries, postings, balances, holds, audit rows. Two traps,
   both silent:
   - **Every timestamp is written explicitly.** `account.created_at`, `journal_entry.created_at`,
     `balance.updated_at`, `hold.placed_at` and `audit_record.occurred_at` all default to `now()`.
     Left to default, the same seed stops producing the same dataset - and `journal_entry.created_at`
     is the *second* key the statement page orders by, so the order rows happened to be loaded in
     would become the order a customer's statement is read in.
   - **One transaction per business date, not one per load.** `posting_entry_balances` is a
     `DEFERRABLE INITIALLY DEFERRED` constraint trigger fired `FOR EACH ROW`, so PostgreSQL holds one
     pending trigger event per posting until the transaction commits. Five million of them in one
     transaction is a queue that spills to disk long before it fails, and the symptom is a load that
     slows down rather than one that stops. Batching by date bounds it. **The trigger is not
     disabled**, and neither is anything else: a loader that needs a constraint switched off is
     writing rows the ledger would have refused, which is what task 4 is about from the other side.

8. **Verified by the ledger's controls, not by the loader's own arithmetic.** A Testcontainers test
   loads a small profile - 2 000 customers over five business dates, which divides the cohorts into
   whole people and runs in seconds - and then asserts, against what the loader produced:
   `BalanceReconciliation.breaks()` is empty; `AuditChain.verify()` is empty; the same seed loaded
   twice into two fresh databases gives the same digest; and no trigger was disabled, read out of
   `pg_trigger.tgenabled` rather than promised. The digest is a SHA-256 over the loader's own
   canonical rendering of every row in the order it wrote them, excluding `posting.id` and
   `audit_record.seq` because an identity value is the database's answer and not the dataset's. It
   goes into a `dataset-manifest.json` beside the run with the seed, the model digest, the customer
   count, the date range, the scale, the row counts, the skip counters, and the busiest account with
   its posting count.

9. **The evidence run, and the plans at volume.** `scripts/load-dataset.sh` wires the pipe from
   `workload-dataset` into the loader; `scripts/capture-plans.sh` runs `EXPLAIN (ANALYZE, BUFFERS)`
   over the statement page at its first page, the statement page at a deep cursor, the balance read,
   and `batch/reporting`'s two bounded queries. Both scripts follow `workload/scripts/estate-up.sh`
   and not `walkthrough.sh`: kill the process group rather than the parent, wait on the port, remove
   state before writing it - the two traps **F-73** records the latter still carrying. The deep
   cursor goes into **the busiest account the load actually produced**, whose reference the manifest
   names, rather than into an account planted to be deep: at 300 000 accounts and five million
   postings the average account holds around seventeen of them, and that number belongs in the write-up
   rather than behind it.

10. **Documentation, traceability and landing.** A new
    [`../../architecture/query-plans-at-volume.md`](../../architecture/query-plans-at-volume.md)
    holds the captured plans with the row counts they were measured at and what they say about
    **F-24** - which stays open and gains its measurement, because an index added here would be a
    change to WP-07's schema and this package's Out of scope forbids it. `services/ledger-loader/README.md`,
    `services/README.md` and the root `README.md`, which **F-17** and **F-31** both record going stale.
    REQ-PERF-004 gets its evidence row in
    [`../../compliance/traceability-matrix.md`](../../compliance/traceability-matrix.md). Then the
    Verification below, with real output into the pull request.

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
