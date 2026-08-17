# Copybook contracts

**~1995** | **Built by WP-02**

Fixed-width record layouts for the account master and movement files, with COMP-3 packed-decimal amounts.

Consumed by `mainframe/` (as COBOL copybooks) and by `integration/esb-adapter` (which must encode identical bytes from Java).

**Source:** [`canonical-data-model.md`](../../docs/architecture/canonical-data-model.md) - see section 9 for the record framing, and section 2 for the COMP-3
byte layout with worked examples.

[`column-map.md`](column-map.md) gives every field its start position, length and picture clause.
[`../check-copybook-offsets.py`](../check-copybook-offsets.py) asserts those positions still hold.
