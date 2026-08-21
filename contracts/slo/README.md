# contracts/slo - what good looks like, declared beside the code

**Spans every stratum that emits a metric** | **Built by WP-23**

| File | What it is |
|---|---|
| [`slo-catalogue.schema.json`](slo-catalogue.schema.json) | the shape a catalogue must have |
| [`tessera-slo-v1.json`](tessera-slo-v1.json) | the committed catalogue, `TB-SLO-CATALOGUE-V1` |
| [`../check-slo-catalogue.py`](../check-slo-catalogue.py) | validates it, and checks what a schema cannot |

The prose companion is [`docs/ways-of-working/slo-catalogue.md`](../../docs/ways-of-working/slo-catalogue.md).
This directory is the machine-readable half, so that a platform repository consumes the objectives
rather than re-typing them.

## Why this is a contract and not a document

[ADR 0012](../../docs/governance/adr/0012-slo-catalogue-boundary.md) decides that the objective is
declared here and the alert is configured elsewhere. The half that stayed here is only worth having
if something checks it, and the ADR says which failure matters:

> an alert firing on an objective this repository no longer declares is noise, and an objective
> nobody alerts on is a claim nobody checks. The second is the more likely and the more dangerous,
> because it looks like a control.

A catalogue with no checker is exactly that. So this is a contract, validated by
`contracts/validate.sh` like every other one, and the checker enforces the property the ADR commits
to: **every metric the estate emits owes an entry, and every entry names a metric the estate
actually emits.** Adding a metric without a catalogue entry fails the build, which is the mechanical
form of "the entry is part of its Definition of Done rather than a follow-up".

## What may not appear here

Anything that would differ between two deployments of the same code. Concretely: burn rates,
notification routes, receivers, severities, dashboards, recording rules and retention. The checker
holds a denylist of that vocabulary and fails on it, because the boundary ADR 0012 draws is easy to
agree with and easy to erode one convenient field at a time.

## An objective is a declaration; the baseline is the measurement

The two are separate on purpose. An objective is what the people who built a component are willing
to promise about it, with the reasoning written down beside it. The baseline records what the estate
actually did under a named workload model, with the manifest and the dataset digest that produced it.

Where a baseline cannot meet an objective, that is recorded rather than quietly relaxed. An
objective revised downwards without a note is indistinguishable from one that was always met.

## Two entries per metric, and why

Each SLI names the metric twice:

- `meterName` is what the **code registers**. For the Java tier that is a dotted Micrometer name -
  `ledger.transfers`, `ledger.outbox.lag`.
- `exposedName` is what a **scrape carries** - `ledger_transfers_total`, `ledger_outbox_lag_seconds`.

The mapping between them is Micrometer's, not this repository's: dots become underscores, a counter
gains `_total`, a base unit is appended. Restating those rules in a checker would be a second copy
of somebody else's convention, and a wrong copy would agree with itself. So `meterName` is asserted
against the component's source by `check-slo-catalogue.py`, and `exposedName` is asserted against a
**real scrape** by that component's own test. Each half is checked where it can actually be checked.

## Tags

Every tag named here must be bounded in cardinality. A route class is bounded; a request path is
not, because it carries an account reference. Unbounded label cardinality is the standard way to
take a monitoring system down, and it is discovered when the monitoring is needed most - so the
checker refuses a tag name from a denylist of the identifiers this estate is full of.

`business_date` is permitted on the batch components alone. Their metrics go to a node_exporter
textfile that each run rewrites, so exactly one date is ever present.

## Validating

```bash
python3 contracts/check-slo-catalogue.py
bash contracts/validate.sh          # runs it with every other contract check
```
