# GDPR data map

> **STUB.** Outline only. Filled by **WP-10**.

What personal data exists in the estate, where it lives, how long it is kept, and how erasure is achieved when the ledger cannot be deleted from.

## Planned contents

- Personal data inventory per component - customer-master holds identity, ledger-core holds only account references
- Lawful basis and purpose per data category
- Retention periods, and the statutory banking retention that competes with the right to erasure
- **The erasure problem**: the ledger is append-only and the audit trail is hash-chained, so deletion would destroy tamper-evidence
- **The resolution**: pseudonymisation and crypto-shredding in customer-master, with the transaction record and its account reference surviving intact
- Data minimisation by design: why most of the estate holds no personal data at all
- Cross-border transfer considerations
