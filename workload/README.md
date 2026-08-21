# workload - the bank day as a schedule

**Stratum 4 - Go 1.25, ~2025** | **Built by WP-20** | **A fixture, not a component of the bank**

Tessera Bank has no such module. This one exists so that the estate can be put under demand that
looks like a bank's rather than like a loop, and it is the same kind of artefact as
`edge/web-banking/scripts/dev-token.mjs`: a thing that helps you exercise the bank, not a thing the
bank runs.

It turns [`contracts/workload/tessera-day-v1.json`](../contracts/workload/) into a schedule of
intended send times. **It sends nothing.** WP-21 executes the schedule against the modern spine and
WP-25 executes the same schedule against the older strata; the model is a contract precisely so the
second driver cannot drift from the first.

## Running it

```bash
make test-workload     # the engine, under the race detector
make lint-workload     # gofmt -l, go vet
make build-workload
```

Nothing else is needed - no Docker, no database, no broker. That is a consequence of the design
rather than a convenience: an engine that had to be run against something would not be an engine
that performs no I/O.

Print the committed model as a day:

```bash
go -C workload run ./cmd/workload-plan \
   --model ../contracts/workload/tessera-day-v1.json \
   --date 2026-08-31 --seed 42 --scale 1.0 --compress 72 --summary
```

Useful flags: `--window branch-hours` to plan part of the day, `--events 20` to see actual drawn
actions, `--manifest run.json` (or `-` for stdout) to write the run record.

## What is in here

| Package | What it does |
|---|---|
| `internal/money` | An amount is int64 minor units and an ISO 4217 code. No scale, no decimal point, no division. |
| `internal/bankday` | The calendar and the clock: business dates, minutes of the day, named windows, compression. |
| `internal/arrivals` | The open arrival process. Emits intended send times. |
| `internal/population` | Cohorts, references drawn to the ledger's own patterns, operation and currency mixes. |
| `internal/model` | Decodes a model and refuses one whose own numbers disagree. |
| `internal/manifest` | The run record: seed, digest, commit, both dials, the window, and what the run asks for. |
| `internal/purity` | The architectural controls. Nothing under `internal/` may reach `net`, `os` or a driver. |
| `cmd/workload-plan` | The only thing here that touches a file. |

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
it: a driver executing account openings would mutate the population it is drawing from, and a run
would stop being reproducible halfway through. Opening the accounts a run needs is WP-22's work.

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
