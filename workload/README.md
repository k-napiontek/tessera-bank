# workload - the bank day, and the driver that executes it

**Stratum 4 - Go 1.25, ~2025** | **Built by WP-20 and WP-21** | **A fixture, not a component of the bank**

Tessera Bank has no such module. This one exists so that the estate can be put under demand that
looks like a bank's rather than like a loop, and it is the same kind of artefact as
`edge/web-banking/scripts/dev-token.mjs`: a thing that helps you exercise the bank, not a thing the
bank runs.

It turns [`contracts/workload/tessera-day-v1.json`](../contracts/workload/) into a schedule of
intended send times, and executes that schedule against the modern spine. WP-25 will execute the
same schedule against the older strata; the model is a contract precisely so the second driver
cannot drift from the first.

The module has two halves and the boundary between them is enforced rather than described. The
**engine** - `bankday`, `arrivals`, `population`, `model`, `money`, `manifest` - performs no I/O of
any kind: it reads no clock, opens no socket and holds no connection. The **driver** - `identity`,
`client`, `seeding`, `runner`, `metrics`, `reconcile` - does nothing else. `internal/purity` fails
if a package is in neither list, so a new one has to be put on a side deliberately.

## Running it

```bash
make test-workload     # the whole module, under the race detector
make lint-workload     # gofmt -l, go vet
make build-workload
```

Nothing else is needed to test it - no Docker, no database, no broker. That is a consequence of the
design rather than a convenience: an engine that had to be run against something would not be an
engine that performs no I/O, and the driver's own tests answer from `httptest` handlers rather than
from an estate.

Print the committed model as a day:

```bash
go -C workload run ./cmd/workload-plan \
   --model ../contracts/workload/tessera-day-v1.json \
   --date 2026-08-31 --seed 42 --scale 1.0 --compress 72 --summary
```

Useful flags: `--window branch-hours` to plan part of the day, `--events 20` to see actual drawn
actions, `--manifest run.json` (or `-` for stdout) to write the run record.

## Driving the estate

One command boots PostgreSQL, the ledger and the gateway, seeds the accounts the run will use, and
executes a compressed bank day against them. Ctrl-C stops everything it started.

```bash
bash workload/scripts/estate-up.sh --scale 0.0002 --compress 720 --window branch-hours
```

Needs Docker, a JDK 17 and Go. Every argument is passed through to `workload-run`, whose `--help`
lists the rest. To drive an estate that is already running, skip the script:

```bash
go -C workload run ./cmd/workload-run \
   --model ../contracts/workload/tessera-day-v1.json \
   --date 2026-08-31 --seed 42 --scale 0.0002 --compress 720 --window branch-hours \
   --gateway http://localhost:8081 --metrics :9100
```

The run publishes `tessera_workload_*` on its own port while it runs, and prints an outcome table,
a latency summary and a reconciliation against the ledger's own `ledger_transfers_total` when it
finishes.

## What is in here

| Package | What it does |
|---|---|
| `internal/money` | An amount is int64 minor units and an ISO 4217 code. No scale, no decimal point, no division. |
| `internal/bankday` | The calendar and the clock: business dates, minutes of the day, named windows, compression. |
| `internal/arrivals` | The open arrival process. Emits intended send times. |
| `internal/population` | Cohorts, references drawn to the ledger's own patterns, operation and currency mixes. |
| `internal/model` | Decodes a model and refuses one whose own numbers disagree. |
| `internal/manifest` | The run record: seed, digest, commit, both dials, the window, and what the run asks for. |
| `internal/identity` | Mints an RS256 token per synthetic subject, and the public key the gateway verifies with. |
| `internal/client` | Builds every request from the ledger's contract, sends it, and says which of five things happened. |
| `internal/seeding` | Opens and funds the accounts a run will use, before the measurement starts. |
| `internal/runner` | The open-model executor: releases every event at its intended time, whatever is outstanding. |
| `internal/metrics` | `tessera_workload_*`, hand-written because this module carries no dependencies. |
| `internal/reconcile` | Reads the ledger's own counter and lines it up against the driver's. |
| `internal/purity` | The architectural controls. The engine reaches no `net`, `os` or database driver; the driver is named. |
| `cmd/workload-plan` | Prints the model as a day. Touches one file and no network. |
| `cmd/workload-run` | Executes a day against a running estate. |

## What the driver does that a load tool does not

A load tool sends requests and counts the ones that came back 200. A customer application does four
things this one also does, and each of them changes what the run measures.

**One idempotency key per scheduled event, reused by every retry.** The key is derived from the
business date and the event's ordinal, so every attempt at one logical request computes the same
one. A driver that minted a fresh key per HTTP call would double-spend under packet loss and would
report success twice, because both requests would answer 201. It also means a second run of the same
date against the same ledger **replays** rather than posts, which is why `estate-up.sh` starts from
an empty ledger unless told otherwise.

**Five outcome columns, not two.** *posted*, *replayed*, *rejected*, *refused* and *unknown*. A 429
is a control working and is never retried immediately; a 5xx or a dropped connection is an unknown
outcome rather than a failure, because the request may well have been applied. WP-14 found that last
one live. The driver's totals are reconciled against the ledger's own `ledger_transfers_total` at
the end of every run, and only three of the five columns are expected to match - a refusal never
reached the ledger, and the ledger's failures are a lower bound on the driver's unknowns.

**It reads back what it created, and nothing it invented.** The ledger allocates its own transfer
and hold references, so `getTransfer` names a transfer this run posted. Early in a run, before
anything has been posted, such an event is counted as unsent rather than sent against a fabricated
reference - which would fill the rejected column with 404s no client would ever produce.

**The estate is single-currency and the model is not.** The ledger fixes an account's currency when
it is opened and requires a transfer to be in the currency of both sides; the model draws a currency
per transfer from a mix of up to five and gives each customer two accounts. Every account is opened
in the heaviest currency the model declares - computed, not assumed - and a transfer drawn in
another goes in that one and is counted in `tessera_workload_currency_substituted_total`. The
alternative is a run in which most transfers are a 422 the ledger is right to return.

## The three things worth understanding

**The model is open, and that is the entire point.** A closed model - *N* workers each waiting for a
reply before sending again - throttles itself precisely when the system slows down, so the offered
load falls exactly when the interesting thing is happening and the latency figures come out
flattering. That is coordinated omission, and it is the same class of defect as the `V99` truncation
in WP-04: the output looks entirely plausible and is simply wrong. Every event's send time is fixed
before the run starts, and WP-21 measures latency from that time rather than from the actual send.
[ADR 0016](../docs/governance/adr/0016-the-workload-model-is-open.md).

**`scale` and `compress` are different dials, and compression multiplies intensity.** The committed
model describes a real bank: roughly 21.6 million events on an ordinary weekday. Run that at 72x and
it asks for tens of thousands of requests a second, which this estate will not take and must not
pretend to. `--scale` lowers the volume; `--compress` changes how fast the day happens. The manifest
records both, and `workload-plan` prints a note when a run asks for a rate nothing here will serve.

**A float never becomes money except in one named function.** An intensity is continuous and a
log-normal draw needs real arithmetic, so this module does contain floats - but every file that names
`float64` is listed with its reason in `internal/money/source_test.go`, and exactly one *function*,
`population.drawMinor`, may convert between a float and an amount. The scanner is an AST walk rather
than a regex, and it refuses a file or a function that is not on the list. The same control as
`edge/web-banking/src/money.source.test.ts` and `batch/reporting/src/reporting/money.py`, in the
third language where it matters.

## What is deliberately absent

**`openAccount` is in no cohort's operation mix.** The schema permits it and the model does not use
it: a customer does not open an account fifteen times a day, and a run that executed account
openings would mutate the population it is drawing from. `internal/seeding` opens the accounts a run
needs before the run starts, deliberately outside the measurement - an opening balance is a transfer
like any other, and counting a few thousand of them as offered load would put a spike at the start
of every run that has nothing to do with the day the model describes. Bulk-loading a year of history
is WP-22's work and goes in by `COPY` rather than over HTTP.

**There is no timezone.** Every time in the model is local civil time at the bank, and mapping a
minute of the business day onto an instant is the driver's job - the two drivers map it differently.
`time.UTC` appears in `internal/bankday` purely as a calendar with no daylight saving in it.

**`online-cut-off` is not the reconciliation cut-off.** It is a point on the demand curve: the moment
the online day stops feeding the batch.
[ADR 0015](../docs/governance/adr/0015-the-cut-off-is-the-movement-file.md) defines *the* cut-off as
the set of `MOV-TRANSFER-REF` values in the movement file, deliberately not a time of day. The
model's own `purpose` field says so, so that nobody has to find this paragraph first.

## No personal data

A customer here is an index and a pseudonymous reference. The schema has no field a name could go in
and `contracts/check-workload-model.py` refuses one that grows a field a name could go in; the
manifest is checked the same way against its own generated output rather than by assertion about
intent, per
[`data-classification.md`](../docs/ways-of-working/data-classification.md).
