# WSDL contracts

**~2011** | **Built by WP-02**

The customer-master SOAP interface, authored WSDL-first as a 2011 bank would have: document/literal wrapped.

Implemented by `legacy/customer-master`, consumed by `integration/esb-adapter` via a generated client.

**Source:** [`canonical-data-model.md`](../../docs/architecture/canonical-data-model.md), by way of [`../xsd/canonical-v1.xsd`](../xsd/canonical-v1.xsd), which
this WSDL imports. Business types are never redefined here - only the operation wrappers and the
fault are declared locally.
