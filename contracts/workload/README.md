# workload - the bank day as a contract

**Stratum 4 fixture, era-agnostic** | **Added by WP-20**

What Tessera Bank's demand looks like, declared once so that every driver executing it is executing
the same day. `workload/` (WP-21) drives the modern spine from this model; WP-25 drives the older
strata from it. The model is a contract precisely so the second driver cannot drift from the first.

Nothing here describes an interface between two running components. It describes an interface between
**a plan and the tools that execute it**, which is the same reason `reporting/` is here: a format that
lives only beside the program that reads it is discoverable only by people who already know where to
look.

## Contents

| File | What it is |
|---|---|
| [`workload-model.schema.json`](workload-model.schema.json) | JSON Schema 2020-12. What a workload model is allowed to say. |
| [`tessera-day-v1.json`](tessera-day-v1.json) | `TB-WORKLOAD-DAY-V1`. The committed day - a mid-size Polish retail bank. |

## What the model states, and what it deliberately does not

It states who the customers are, what they do, how demand varies across the day, the week and the
month, and where the named windows fall. It states its volume **in real time at scale 1.0**, so that
a run at any other setting has to say so.

It names **no host, no URL, no topic and no file**. A model that knew where to send a request would
be half a driver, and the two drivers that consume it send to entirely different places - one to an
HTTP gateway, the other to a SOAP endpoint and a fixed-width file.

## Two things worth reading before you change it

**`online-cut-off` is not the reconciliation cut-off.** The instant at 20:00 is a point on the demand
curve: the moment the online day stops feeding the batch.
[ADR 0015](../../docs/governance/adr/0015-the-cut-off-is-the-movement-file.md) defines *the* cut-off
as the set of `MOV-TRANSFER-REF` values in the movement file the cycle consumed - exact, with no
window and no tolerance, and deliberately not a time of day. Two different things that would be one
word if nobody said otherwise, so the model says otherwise in the field's own `purpose`.

**The population is load-bearing, not decorative.** The gateway limits 10 requests per second with a
burst of 20, keyed by token subject plus route class, per instance -
[ADR 0006](../../docs/governance/adr/0006-edge-rate-limit-is-per-instance.md). One synthetic customer
therefore cannot generate meaningful load, and raising the limit for a load run would measure a
gateway nobody deploys. Thousands of distinct subjects is both the realistic shape and the working
one.

## No personal data, by construction

There is no field in this schema a name, an address or a national identifier could go in, and
`check-workload-model.py` refuses a schema that grows one: every object is closed
(`additionalProperties: false`), every string is bounded by an `enum`, a `pattern` or a stated prose
allowance, and a denylist of property names is checked against the schema itself. That is the control
[`data-classification.md`](../../docs/ways-of-working/data-classification.md) asks for, stated where
it can be enforced rather than promised in prose.

## Validating

```bash
python3 contracts/check-workload-model.py
```

Run by [`../validate.sh`](../validate.sh) along with every other contract check. It validates the
committed model against the schema with a hand-written checker - standard library only, no JSON
Schema package, for the same reason the two checks either side of it are hand-written - and then
proves the invariants a schema cannot express: that the cohort shares add to 1 and yield whole
customers, that every mix adds to 1, that the declared daily volume is what the population actually
generates, that the declared peak-to-trough ratio is what the curve actually has, and that every
operation named is one the ledger genuinely serves, read from
[`../openapi/ledger-core.yaml`](../openapi/ledger-core.yaml).

It does not prove that a schedule the engine emits follows the model. That is `workload/`'s own test,
and the two claims are checked from opposite sides on purpose.
