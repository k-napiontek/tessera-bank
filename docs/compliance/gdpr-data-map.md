# GDPR data map

What personal data exists in the estate, where it lives, how long it is kept, and how erasure is
achieved when the ledger cannot be deleted from.

> **What is implemented and what is described.** The inventory, the minimisation and the boundary
> are **implemented and tested** - one table holds identity, the contract carries none of it, and the
> tests prove no identity reaches a SOAP message. Retention periods and crypto-shredding are
> **described only**. Crypto-shredding is a feature, not a paragraph; nothing in this repository
> encrypts an identity field or manages a key, and saying otherwise here would be exactly the false
> assurance the Definition of Done's honesty clause is about. It is logged as a follow-up.

---

## 1. Inventory

Personal data lives in **one table, in one component**. That is the whole design.

| Component | Stratum | Holds | Classification |
|---|---|---|---|
| `legacy/customer-master`, table `customer` | 1 | `family_name`, `given_name`, `date_of_birth`, `national_id` | **Restricted** |
| `legacy/customer-master`, table `customer` | 1 | `customer_ref`, `onboarded_date` | Pseudonymous / internal |
| Everything else in the estate | 0, 2, 3, 4 | `customerRef`, `accountRef`, `transferRef`, `correlationId` | Pseudonymous |

`customer_ref` is a **pseudonymous identifier and therefore still personal data** under Article 4(5) -
pseudonymisation reduces risk, it does not take data out of scope. What it does is confine the
re-identification key to one component: nothing outside `customer-master` can turn `CU0000000042`
into a person.

The remittance reference (`tb:ReferenceType`, 35 characters, as in the SEPA field) is the one field
a paying customer controls and can therefore put anything into, including a name. It is classified
restricted-if-misused. WP-09 already acts on that: the audit row omits it while the domain event
carries it, because an audit row is retained for years and the event is how the reference reaches the
mainframe to be printed on a statement.

**No production data anywhere.** Every value in this repository comes from `SyntheticData`, which
sits in **test** scope on purpose - code that manufactures personal data has no business inside a
deployable artefact. Names carry their ordinal (`TESSERA-0001`) and identifiers are prefixed `SYN-`,
a shape no issuing authority uses. Neither is a value that merely happens not to match a person;
both are values that cannot.

## 2. Lawful basis and purpose

| Category | Purpose | Lawful basis |
|---|---|---|
| Name, date of birth, national identifier | Customer identification and verification | **Legal obligation** - AML/KYC identification duties |
| Account holding and metadata | Performing the payment account contract | **Contract**, Art. 6(1)(b) |
| Transaction records | Executing payments; statutory record-keeping | **Contract**, then **legal obligation** |
| Fraud scoring decisions | Detecting and preventing payment fraud | **Legal obligation** and **legitimate interest** |

The fraud tier is worth a sentence because it looks like profiling and is deliberately not the kind
that triggers Article 22. `edge/fraud-scoring` scores **one event at a time** with rules that are pure
functions of that event - it holds no customer history and cannot hold one, by construction and by
ADR 0008. Its decision is an input to a control, not an automated decision about a person taken
without human involvement.

## 3. Retention

**Described, not enforced.** Nothing in this repository deletes anything on a schedule, and F-28
records the same gap for the ledger's outbox and idempotency tables. The periods below are the ones
an EU retail bank would be working to; the actual figures are a regulatory question for the
institution, not an engineering decision.

| Data | Indicative period | Driver |
|---|---|---|
| Identification data (name, DOB, national id) | 5 years after the relationship ends | AMLD5 Art. 40 |
| Transaction records | 5 years after the transaction | AMLD5 Art. 40 |
| Accounting records | 5-10 years, per member state | National accounting law |
| Audit trail (`audit_record`) | For the life of the records it evidences | DORA, internal control |
| Fraud decisions | Shorter, and tied to the case rather than the customer | Proportionality |

**Statutory retention beats the right to erasure**, and this is the ordinary case rather than the
exception. Article 17(3)(b) disapplies erasure where processing is necessary for compliance with a
legal obligation. A customer who asks to be deleted five weeks after a payment is refused for the
payment record and answered for everything else - and the answer has to say which is which.

## 4. The erasure problem

The estate cannot honour erasure by deleting, and the reasons are structural rather than
inconvenient:

- **The ledger is append-only.** `journal_entry` and `posting` are never updated and never deleted;
  a correction is a reversal entry that references the original. That is what makes a ledger a
  ledger.
- **The audit trail is hash-chained.** Each row carries a hash of the one before it - ADR 0005 - so
  deleting a row breaks the chain from that point to the present. The control that detects tampering
  cannot distinguish an erasure request from an attacker.
- **The mainframe master is a flat file.** Stratum 0 rewrites the whole master every night;
  "deleting a record" there means rewriting history in a file that four other packages read.
- **Backups.** A restored backup would reinstate whatever was deleted, and nothing here reconciles a
  restore against erasure requests.

## 5. The resolution

**Erase the identity, keep the record.** The transaction record and its `accountRef` survive intact;
what stops existing is the ability to connect them to a person.

This works only because of how the estate is arranged, which is the point of section 1: the payment
records in the ledger, the mainframe and the reporting extracts carry a `customerRef` and no identity
at all. Break the link in `customer-master` and every one of them becomes anonymous **without a
single row being modified** - the hash chain still verifies, the master still foots, and the reports
still reproduce byte-for-byte at a recorded position.

Two mechanisms, in order of preference:

1. **Crypto-shredding.** Identity columns are encrypted per customer; erasure destroys that
   customer's key. The ciphertext remains and is unreadable by anyone, including the bank.
   **Not built.** It needs a key per subject, a key store, a destruction procedure that is itself
   audited, and a story for backups taken before the destruction. Logged as a follow-up.
2. **Pseudonymisation in place.** Overwrite the identity columns with tombstone values, keeping
   `customer_ref` as an opaque token. Simpler, and weaker: it is irreversible, so it must not run
   while any statutory retention period still covers the identification data itself.

Neither touches stratum 0, stratum 3 or the reporting extracts. That is the property worth
protecting, and section 1 is how it is protected.

## 6. Data minimisation by design

Most of this estate holds no personal data, and that is an architectural decision rather than an
accident:

- `canonical-v1.xsd` gives the wire a `customerRef` and **nothing else**. There is no name element
  in the canonical model, so no component can carry one even by mistake.
- The SOAP contract exposes no operation that returns identity. `GetAccount` and
  `GetAccountsByCustomer` answer with `tb:Account`, which carries a reference.
- `CustomerMasterEndpointReadTest` asserts that no family name, given name or national identifier
  from the fixture appears in any successful answer **or in any fault**. The fault path is checked
  explicitly because an error path is the second most common place personal data escapes a system,
  which is why the WSDL says so where the fault is defined.
- `AccountDao` repeats nothing from the driver to the caller. An Oracle message can carry the bind
  values that caused it, and a bind value here can be a customer's data - so faults are mapped by
  error **number** and given messages written here.
- The ledger's audit row omits the remittance reference; the outbound event carries it. The
  exclusions differ because the consumers do.

## 7. Cross-border transfer

No transfer outside the EU/EEA is described by this repository, and none could be inferred from it:
it contains no deployment topology, no region and no third-party processor - ADR 0001 puts all of
that in the companion platform repositories. A deployment that places any component outside the
EEA acquires a Chapter V obligation that this document does not discharge, and the component to look
at first is `customer-master`, because it is the only one holding identity.

## 8. Where the controls actually are

| Claim | Enforced by | Evidence |
|---|---|---|
| Only one component holds identity | Schema design | `V1__schema.sql`, the `customer` table |
| No identity crosses the wire | The canonical contract | `canonical-v1.xsd` declares no identity element |
| No identity reaches an answer or a fault | Test | `CustomerMasterEndpointReadTest` |
| No driver detail reaches a caller | Code | `AccountDao.faultFor`, mapping by error number |
| No real personal data anywhere | Generator in test scope | `SyntheticData`, `SyntheticDataTest` |
| Retention | **Nothing.** Described above, not implemented | - |
| Crypto-shredding | **Nothing.** Described above, not implemented | - |

The last two rows are the honest ones, and they are why this document does not claim the estate is
GDPR-compliant. It claims something narrower and checkable: the estate is **arranged so that erasure
is possible without destroying the accounting record**, and the arrangement is tested.
