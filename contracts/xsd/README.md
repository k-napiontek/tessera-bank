# XML schemas

**~2011** | **Built by WP-02**

Canonical XML types for the WSDL, and the canonical transfer message the ESB transforms into by XSLT.

**Source:** [`canonical-data-model.md`](../../docs/architecture/canonical-data-model.md). Every type in `canonical-v1.xsd` traces to a concept defined there.

The schema enforces the model rather than merely describing it: it rejects a decimal amount, a
malformed currency code, and a transfer that does not carry exactly two movements.
