# reporting

**Stratum 4** | **Python 3.12 under `uv`** | **Built by WP-17**

Daily position, movement summaries and the regulatory extract, generated from the ledger. Batch, not
real-time, because that is what reporting genuinely is.

```bash
make test-reporting          # from the repository root; needs Docker
cd batch/reporting && uv run pytest

TB_REPORT_DSN=postgresql://ledger@localhost:5432/ledger \
  uv run reporting --business-date 20260818
```

## What a run produces

| File | Format | Holds |
|---|---|---|
| `position-CCYYMMDD.csv` | CSV | One row per account: booked balance, movement count, control totals per currency |
| `movements-CCYYMMDD.csv` | CSV | One row per posting on the business date, with debit and credit totals per currency |
| `regulatory-extract-CCYYMMDD.txt` | [`TB-REGEXT-V1`](../../contracts/reporting/regulatory-extract-v1.md) | Fixed-width, 200-byte records, header and trailer |
| `manifest-CCYYMMDD.json` | JSON | The position, the chain hash, the run instant and a SHA-256 of every file above |

## The one idea: a report is a function of a position

**`audit_record.seq` is the ledger position**, and everything else here follows from it.

`JdbcAuditLog` takes `pg_advisory_xact_lock` before reading the chain head and holds it to commit, so
audit appends cannot interleave. That makes `seq` the only column in the ledger's schema where
**sequence order is commit order**: if seq P is visible, every seq below it has committed. Every
journal entry has exactly one audit row - `Transfer` writes `TRANSFER_POSTED` in the same transaction
as the postings, `ReverseTransfer` writes `TRANSFER_REVERSED`, and `CaptureHold` delegates its
posting to `Transfer` - so bounding a report by `seq <= position` bounds it by a fact rather than by
a guess.

`max(posting.id)` and `journal_entry.created_at` both look like they would serve and neither does. An
identity value is allocated when a row is inserted and `now()` is fixed when a transaction starts, so
a transaction that begins early and commits late carries a low value while appearing after rows
written later. A rerun would admit rows the first run could not see, every figure would still be
internally consistent, and nothing would report a discrepancy.

The case this exists for is a **backdated entry**: one posted today carrying an earlier value date. A
filter on value date alone admits it on a rerun; the position does not.

```bash
# Cut a fresh position.
uv run reporting --business-date 20260818

# Reproduce that exact run, whatever has happened since. The position comes from the manifest.
uv run reporting --business-date 20260818 --position 4711
```

Follow-up F-27 records the advisory lock as a throughput ceiling on the ledger. It is also what buys
reporting a reproducible cut, and both statements are true at once.

## What is deliberately not done here

- **No report reads the `balance` table.** It reflects *now*, and a report built on mutable state is
  not auditable. Every figure is summed from postings, which are append-only. That also makes the
  reconciliation independent rather than circular: the report's arithmetic and the ledger's
  materialised figure are two computations, and `test_reconciliation.py` asserts they agree.
- **No wall clock enters a report body.** A generation timestamp would make byte-identical reruns
  impossible by construction. The run instant lives in the manifest.
- **No floating point, anywhere near money.** Minor units as `int` plus an ISO 4217 code, with the
  scale resolved per currency. `test_money.py` parses `money.py` and fails on a true division, a
  `float`, a `Decimal` or a `round` - the check is structural because a grep is fooled by its own
  documentation.
- **No conversion between currencies.** This estate holds no rates, so a cross-currency figure would
  be an unsourced, undated rate on a regulatory report. Control totals are per currency; the only
  cross-currency number in the estate is the extract's hash total, which is a control rather than an
  amount and says so.

## Control totals

Debits equal credits, per currency. For any set of complete entries that is not an observation but
the definition of double entry, so a currency where they disagree is a currency where a posting went
missing between the query and the file. Both reports **raise rather than print** when it does not
hold: an imbalance published as a figure has stopped being a detected fault and become a reported
number.

The extract's trailer carries a different kind of total - the sum of absolute minor units across
every currency. It is meaningless as money and exactly right as a check: any lost, duplicated or
edited detail record changes it, and no exchange rate is needed to recompute it.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `TB_REPORT_DSN` | *required* | libpq connection string for the ledger database |
| `TB_REPORT_OUTPUT_DIR` | `out` | Where the reports are written |
| `TB_REPORT_INSTITUTION` | `TESSPLPWXXX` | The reporting institution's BIC, stamped in the extract header |
| `TB_REPORT_METRICS_PATH` | `out/reporting.prom` | node_exporter textfile the run's metrics go to |
| `TB_REPORT_QUERY_TIMEOUT_SECONDS` | `300` | Statement timeout; a query slower than this is scanning |
| `TB_REPORT_LOG_LEVEL` | `info` | `debug`, `info`, `warning` or `error` |

A setting that is present but unparseable is an error rather than a fall back to the default, and
every problem is reported at once. This job runs unattended: a loader that stops at the first problem
costs one night per variable.

Exit codes are what the scheduler reads. `0` wrote the reports; `2` means the configuration or
command line was wrong and nothing was read, so a retry will fail identically; `1` means the run
started and failed, which is worth retrying once.

## Observability

Metrics go to a **file**, not an endpoint: a batch job is gone before anything could scrape it, and a
pushgateway would keep the last value indefinitely, turning a job that stopped running into a job
whose figures look healthy.

| Metric | Type | Answers |
|---|---|---|
| `tessera_reporting_duration_seconds` | gauge | Is the run getting slower |
| `tessera_reporting_position` | gauge | Did the ledger move at all since last night |
| `tessera_reporting_accounts_read` | gauge | How much was in scope |
| `tessera_reporting_movements_read` | gauge | How much moved |
| `tessera_reporting_records_written` | gauge | Did every file get written, and how big |

Logs are JSON on stdout carrying the business date and the position. **Never the remittance
reference**: it is the one piece of free text a paying customer controls, the ledger keeps it out of
its audit rows, and a report's log is read by more people than that audit trail is.
