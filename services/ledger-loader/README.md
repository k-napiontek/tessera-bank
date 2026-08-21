# ledger-loader - a production-shaped ledger, from the workload model

**Stratum 3 - Java 17, ~2023** | **Built by WP-22** | **A fixture, not a component of the bank**

Tessera Bank has no such component. This module exists so that the ledger's queries meet real
cardinality for the first time: before it, the whole estate had been verified against about three
accounts, and the plans a query gets at that size say nothing about the plans it gets at a year of
postings. It is the same kind of artefact as [`workload/`](../../workload/) and
`edge/web-banking/scripts/dev-token.mjs` - something that helps you exercise the bank, not something
the bank runs. Nothing in the estate depends on it.

It reads a stream of drawn actions from
[`workload-dataset`](../../workload/cmd/workload-dataset/) and turns them into `account`,
`journal_entry`, `posting`, `balance`, `hold` and `audit_record` rows, written with `COPY`.

## Why it is here rather than in `workload/`

Because of what it must not restate.

The audit trail's canonical form is
[`AuditEntry`](../ledger-core/src/main/java/bank/tessera/ledger/port/AuditEntry.java)'s. What is
hashed decides whether two different entries can produce the same bytes, and a second implementation
of it - in Go, in Python, anywhere - would agree with the first until the day it did not, at which
point the chain would verify against nothing. The sign convention is `AccountType.signedEffect`'s for
the same reason, and the references are validated by the domain's own types, so a malformed stream is
refused at the boundary rather than by PostgreSQL three million rows in.

`workload/` is standard-library-only Go whose `internal/purity` check forbids `database/sql`
outright, so a loader living there would have needed its own wire protocol **or** a copy of both
rules. That is the duplication follow-ups F-61 and F-66 record rotting, in a place where the
disagreement would have been silent.

The population still comes from WP-20, over a pipe. Neither side draws the day twice.

## Running it

```bash
make test-services              # includes this module; needs Docker for the loaded-ledger tests
bash scripts/load-dataset.sh    # from the repository root, see below
```

One command loads a dataset, checks it with the ledger's own controls and leaves the database up:

```bash
bash services/ledger-loader/scripts/load-dataset.sh \
  --customers 150000 --from 2025-09-01 --to 2026-05-08 --scale 0.0017 --seed 42
```

Then capture the query plans at that volume:

```bash
bash services/ledger-loader/scripts/capture-plans.sh
```

The three commands underneath, if you want them separately:

```bash
ledger-loader migrate --url jdbc:postgresql://localhost:5435/tessera --user tessera
go -C workload run ./cmd/workload-dataset --model ... --from ... --to ... \
  | ledger-loader load --url ... --manifest dataset-manifest.json
ledger-loader verify --url ...
```

## What a load decides, and says so

Three things the model asks for cannot be had exactly against this estate. Each is **counted in the
manifest** rather than quietly done, which is the same choice WP-21 made for the same reason.

| Counter | What it means |
|---|---|
| `CURRENCY_SUBSTITUTED` | The ledger fixes an account's currency at open and the model draws from a mix of up to five. The estate is opened in one, so the rest are posted in it. F-72 |
| `REFUSED_INSUFFICIENT_FUNDS` | A transfer that would have taken an account below zero. Nothing in the schema stops that; `OverdraftPolicy` is the domain's and the bulk path does not go through it, so this counter is the only thing between the dataset and rows `Transfer` would have rejected |
| `HOLD_NOT_FOUND`, `NOTHING_TO_REVERSE` | A capture, release or reversal drawn against an account with nothing open to act on. A driver would have got a 404; inventing one would be reversing a payment that never happened |

## The two things a bulk loader gets wrong

**An entry without an audit row is not wrong, it is invisible.** Every query in
[`batch/reporting`](../../batch/reporting/) bounds its postings by joining `journal_entry` to
`audit_record` on `subject_ref` with `seq <= position`, so a loader that skipped the chain would
produce millions of postings that no report can see - without breaking a single test in `services/`.
Follow-up **F-43** records the exposure; `ChainWriter` is what keeps this module out of it, and
`LoadedLedgerTest` asserts the join in SQL against what was actually written.

**One transaction per checkpoint, never one per load.** `posting_entry_balances` is a
`DEFERRABLE INITIALLY DEFERRED` constraint trigger fired for each row, so PostgreSQL queues one
pending trigger event per posting until the transaction commits. Five million of them in one
transaction is a queue that spills to disk, and the symptom is a load that gets slower rather than
one that fails. Nothing is disabled to avoid it - a loader that has to switch a constraint off is
writing rows the ledger would have refused - and a test reads `pg_trigger.tgenabled` after a load to
say so.

## Verified by the ledger, not by itself

`LoadedLedgerTest` loads a small dataset into real PostgreSQL and then runs
[`BalanceReconciliation`](../ledger-persistence/src/main/java/bank/tessera/ledger/adapter/jdbc/BalanceReconciliation.java),
which sums the postings in SQL independently of the Java that wrote them, and
[`AuditChain.verify()`](../ledger-persistence/src/main/java/bank/tessera/ledger/adapter/jdbc/AuditChain.java),
which walks the whole trail. A loader checked only against its own arithmetic proves nothing.

The same seed loaded twice must produce the same **digest** - a SHA-256 over every row in write
order, excluding `posting.id` and `audit_record.seq`, which are the database's answers rather than
the dataset's. Row counts alone are not enough: two loads can write the same number of rows and
disagree about every amount in them.

## The fixture, and the seam it protects

`src/test/resources/sample-stream.ndjson` is real emitter output, and
`workload/internal/dataset`'s `TestTheLoaderFixtureIsWhatThisCommandProduces` regenerates it and
compares bytes. There is no schema between the emitter and this module - it is one contract in two
languages - so that test catches a field renamed or reordered, and `DatasetReader`'s refusal of an
unknown property catches one added. Regenerate it with:

```bash
go -C workload run ./cmd/workload-dataset \
  --model ../contracts/workload/tessera-day-v1.json \
  --from 2026-03-02 --to 2026-03-03 --seed 42 --scale 0.0000075 --customers 200 \
  > services/ledger-loader/src/test/resources/sample-stream.ndjson
```

## What it does not do

No schema change. An index the captured plans argue for is a finding against WP-07's migration set
and is logged, not added here - see **F-24** and
[`docs/architecture/query-plans-at-volume.md`](../../docs/architecture/query-plans-at-volume.md).
No traffic: WP-21 drives the API. No SLOs: WP-23 records them. No Oracle and no COBOL master: those
are WP-25's, at the far end of the same model.
