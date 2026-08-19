# reporting - outbound regulatory formats

**Stratum 4 producer, era-agnostic format** | **Added by WP-17**

The file formats this estate publishes *outward*, to a supervisor rather than to another component.
Everything else in `contracts/` describes an interface between two tiers of Tessera Bank; this
describes one between Tessera Bank and somebody who has never read this repository.

## Contents

| File | Format | Produced by |
|---|---|---|
| [`regulatory-extract-v1.md`](regulatory-extract-v1.md) | `TB-REGEXT-V1`, fixed-width, 200-byte records | [`batch/reporting`](../../batch/reporting/) |

## Why it is here and not in the component

Because a consumer integrating against this repository reads `contracts/`. A format that lives only
beside the program that writes it is discoverable only by people who already know where to look, and
follow-up F-34 records exactly that gap opening for the gateway's error surface. The extract is an
interface, so it is declared where the interfaces are.

## Validating

```bash
python3 contracts/check-extract-layout.py
```

Run by [`../validate.sh`](../validate.sh) along with every other contract check. It proves the layout
is a coherent fixed-width format - contiguous columns, lengths agreeing with their picture clauses,
every record type the same width. It does not prove that a generated file matches the layout: that is
`batch/reporting`'s own test, and the two claims are deliberately checked from opposite sides.
