# The SLO catalogue - what good looks like here

**Owner:** WP-23 | **Machine-readable form:** [`contracts/slo/`](../../contracts/slo/) |
**Boundary:** [ADR 0012](../governance/adr/0012-slo-catalogue-boundary.md)

Every component of this estate that emits a metric states what good looks like for it: the SLI, the
objective, the measurement window and the error budget that follows. This page is the prose. The
authority is [`contracts/slo/tessera-slo-v1.json`](../../contracts/slo/tessera-slo-v1.json), which a
platform repository consumes instead of re-typing.

## The rule this exists to enforce

> An alert firing on an objective this repository no longer declares is noise, and an objective
> nobody alerts on is a claim nobody checks. The second is the more likely and the more dangerous,
> **because it looks like a control.** - ADR 0012

So the catalogue is a contract, checked by `contracts/validate.sh` like every other one, and the
check runs in **both directions**: every metric the estate emits owes an entry, and every entry names
a metric the estate actually emits. Adding a metric without a catalogue entry fails the build. That
is the mechanical form of the commitment ADR 0012 makes - the entry is part of a component's
Definition of Done, not a follow-up.

## What is declared, and what is not

| Here | In the platform repositories |
|---|---|
| the SLI: which metric, which tags, aggregated how | alerting rules |
| the objective: target and measurement window | burn-rate windows |
| the error-budget arithmetic | notification routes and receivers |
| how the figure is computed from a scrape | dashboards, recording rules, retention |
| a recorded baseline, with the manifest that produced it | page thresholds per deployment |

The test is ADR 0012's: **would the artefact change if the same code were deployed differently?** An
SLI definition would not. A page threshold would - two instances of one service, one customer-facing
and one internal, deserve different pages from the same objective.

`contracts/check-slo-catalogue.py` enforces the line rather than trusting it: a denylist of alerting
vocabulary - `burnRate`, `severity`, `receiver`, `dashboard`, `alertname`, `retention` and the rest -
fails the build if one of those names ever appears as a field in the schema. The boundary is easy to
agree with and easy to erode one convenient field at a time.

## The objectives

Eleven, over five components. The catalogue carries the rationale for each; these are the ones whose
reasoning is worth reading twice.

| Objective | Target / window | Why that number |
|---|---|---|
| `SLO-LEDGER-MOVEMENT-SUCCESS` | 99.9% / 30d | A rejection is the ledger **working** - insufficient funds, a closed account, a currency mismatch. Only `failed` is the ledger failing, and the `outcome` tag exists so the two cannot be averaged. An objective over the whole counter would be met by a ledger refusing every payment. |
| `SLO-LEDGER-POSTING-LATENCY` | 99% under 500 ms / 30d | Half a second is where a caller's own timeout starts producing retries, and a retry arrives as a replay - so this objective and the replay share of the one above break in that order, giving an operator one signal before the other. |
| `SLO-LEDGER-POOL-HEADROOM` | 99% of the window / 30d | A pool larger than the database can serve turns a slow query into a queue of held connections. `hikaricp_connections_pending` is where that queue becomes visible, before latency moves and long before anything fails. |
| `SLO-GATEWAY-AVAILABILITY` | 99.9% / 30d | A 429 is deliberately on the **good** side: retrying into a limiter converts a working control into a stampede. Only 5xx is the edge failing. |
| `SLO-GATEWAY-LATENCY` | 99% under 1 s / 30d | One second is a **bucket boundary** of the histogram it is computed from. A threshold between boundaries cannot be computed from a histogram at all, so an objective at 750 ms would be one nobody could measure. |
| `SLO-FRAUD-DECISION-COMPLETENESS` | 99.99% / 30d | The budget is deliberately small and deliberately **not zero**: zero is a wish rather than an objective, and one nobody can ever meet is one everybody learns to ignore. |
| `SLO-RECON-ACCOUNTS-AGREE` | 99.99% / 30d | `TIMING` differences are on the **good** side. The cut-off is the movement file, so a movement posted after it is correctly in one core and not the other; an objective that counted it as a break would fire every morning and train operators to ignore the report. |

## Two entries per metric

Each SLI names its metric twice, and both halves are checked - in the two different places where each
can actually be checked.

- **`meterName`** is what the code registers. `contracts/check-slo-catalogue.py` asserts the string
  appears in that component's own source.
- **`exposedName`** is what a scrape carries. `CatalogueScrapeTest` asserts it against a **real
  scrape** of `/actuator/prometheus`.

The mapping between them is Micrometer's, not this repository's - dots to underscores, `_total` on a
counter, a base unit appended, a timer becoming three series. Restating those rules in the checker
would be a second copy of somebody else's convention, and a wrong copy would agree with itself.

Metrics a framework registers - Boot's Hikari binder - carry `origin: framework`. There is no source
literal to match, so re-deriving them just to make them matchable would be a second instrument that
could disagree with the first. The scrape test is the only thing standing behind those entries, and
the catalogue says so.

## How each figure is computed

Every objective declares `computedFrom`, so a report **derives** the SLI instead of a tool
transcribing it: `counterLabels` splits one counter by a label, `histogramBucket` divides the bucket
at the threshold by the count, `counterPair` divides a counter by itself plus its siblings, and
`seriesOverTime` says the question needs a window rather than two samples.

That last one matters. `workload-report` **refuses** to answer a `seriesOverTime` objective from two
scrapes; it prints the two points it has and names the objective that needed more. A figure produced
from two samples would be invented rather than measured.

## An objective is a declaration; the baseline is the measurement

The two are separate on purpose, and both are committed.

- The **objective** is what the people who built a component are willing to promise, with the
  reasoning beside it.
- The **baseline** in [`workload/baselines/`](../../workload/baselines/) is what the estate actually
  did under a named workload model, with the run manifest and the dataset digest that produced it.

A baseline names the conditions it was captured under or it is worthless: model version, seed, scale,
compression, dataset digest, git SHA and the hardware. Two baselines that do not state their
conditions cannot be compared, and comparing them anyway is how a team concludes a regression exists.

Where a baseline cannot meet an objective, that is **recorded rather than quietly relaxed** - see
[`../architecture/estate-under-load.md`](../architecture/estate-under-load.md), where the outbox
freshness objective is missed by the fixture and left in the report saying so. An objective revised
downwards without a note is indistinguishable from one that was always met.

## Adding a metric

1. Register it in the component.
2. Add its entry to `contracts/slo/tessera-slo-v1.json` - an objective if the component can promise
   something about it, a signal with `noObjectiveBecause` if it genuinely cannot.
3. `bash contracts/validate.sh`.

Step 2 is not optional and the build will tell you so. A metric with no stated objective is the state
ADR 0012 exists to end, and adding one more of them silently would undo it.
