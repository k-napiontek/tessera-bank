# Canonical data model

The single definition of the business concepts that cross tier boundaries. **Built by WP-02.**

Four contracts express the same transfer: a COBOL copybook, an XSD and WSDL, an OpenAPI document and
an AsyncAPI document. Written independently they drift, and the drift stays invisible until the
integration tier encodes bytes the mainframe lays out differently. This document exists so that they
are not written independently.

## The rule

> **Every field in every contract traces to a field defined here.** No contract invents a concept.
>
> When a contract and this document disagree, the document is not patched to match the contract.
> The model is corrected first, then every contract derived from it is corrected together.

Four packages check themselves against this file: WP-03 (byte layout), WP-08 (REST contract test),
WP-10 (SOAP responses against the XSD) and WP-11 (COMP-3 encoder, byte for byte).

---

## 1. Conventions

### Naming

The canonical name is `lowerCamelCase`. Each era renames it to its own idiom, and only to its own
idiom - the concept never changes with the name.

| Era | Convention | Example |
|---|---|---|
| COBOL-85 | `UPPER-KEBAB`, prefixed per record, max 30 characters | `MOV-ACCT-REF` |
| XSD / WSDL | `lowerCamelCase` elements, `PascalCase` types | `accountRef`, `AccountRefType` |
| OpenAPI / AsyncAPI | `lowerCamelCase` properties, `PascalCase` schemas | `accountRef`, `Account` |

### Identifiers

Every cross-era identifier is fixed-width, uppercase, and safe in a sequential file. UUIDs appear
only where the mainframe never sees them.

| Identifier | Width | Pattern | Known to |
|---|---|---|---|
| `accountRef` | 16 | `^TB[0-9A-Z]{14}$` | all strata |
| `customerRef` | 12 | `^CU[0-9]{10}$` | all strata |
| `transferRef` | 20 | `^TB[0-9]{8}[0-9]{10}$` - `TB` + `CCYYMMDD` + sequence | all strata |
| `movementRef` | 23 | `<transferRef>-<legNo>` | strata 2-4; stratum 0 stores the two parts separately |
| `correlationId` | 36 | RFC 4122 UUID | strata 2-4 only |
| `idempotencyKey` | 16-64 | opaque, client-supplied | stratum 3 only |

`correlationId` and `idempotencyKey` are deliberately absent from the copybook. The 1995 core has no
concept of either, and inventing one for it would be a modern idiom leaking into stratum 0.

### Time

| Concept | Stratum 0 | Strata 1-4 |
|---|---|---|
| Date | `PIC 9(8)`, `CCYYMMDD` | `date`, ISO 8601 |
| Timestamp | `PIC 9(14)`, `CCYYMMDDHHMMSS`, UTC | `date-time`, RFC 3339, millisecond precision, `Z` |

**All times are UTC everywhere.** No local time, no offsets other than `Z`. The mainframe form loses
sub-second precision: this is a deliberate narrowing at the stratum 0 boundary, not a defect, and the
integration tier truncates rather than rounds when it crosses.

### Text and character set

Fixed-width text is left-justified and **space-padded**, never null-padded. Printable ASCII only.

Stratum 0 files are **ASCII**, because GnuCOBOL runs on a POSIX host. A real z/OS core would hold
EBCDIC and the integration tier would transcode. This is a recorded deviation from mainframe
fidelity, taken so that byte comparisons in WP-11 and WP-16 stay legible; COMP-3 fields are unaffected
because packed decimal is identical in both character sets.

---

## 2. Money

Money is a value type: an integer count of minor units plus the currency that gives them scale.

> **Money is never a floating-point number, and never a decimal string.** Not in a database column,
> not in JSON, not in XML, not on the wire.

### Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `amountMinor` | signed integer, up to 15 digits | yes | The amount in minor units. `123456789` with currency `PLN` is 1 234 567.89. |
| `currency` | string, 3 characters | yes | ISO 4217 alpha-3, uppercase. |

The decimal position is **not** stored. It is resolved from `currency` through the table below, at
presentation time only. Two amounts are comparable only when their currencies are equal; there is no
implicit conversion anywhere in the estate.

### ISO 4217 scale table

The one table every tier uses. A currency absent from it is rejected, not guessed.

| Currency | Scale | Minor unit | `amountMinor` 100 is |
|---|---|---|---|
| `PLN` | 2 | grosz | 1.00 PLN |
| `EUR` | 2 | cent | 1.00 EUR |
| `USD` | 2 | cent | 1.00 USD |
| `GBP` | 2 | penny | 1.00 GBP |
| `CHF` | 2 | rappen | 1.00 CHF |
| `JPY` | **0** | yen | 100 JPY |
| `KRW` | **0** | won | 100 KRW |
| `BHD` | **3** | fils | 0.100 BHD |
| `KWD` | **3** | fils | 0.100 KWD |
| `TND` | **3** | millime | 0.100 TND |

`JPY`, `KRW`, `BHD`, `KWD` and `TND` are carried specifically so that a hard-coded scale of 2 fails a
test rather than passing quietly. Funds currencies with scale 4 (`CLF`, `UYW`) are out of scope.

### COMP-3 packed decimal - stratum 0 representation

In the copybook, money is:

```cobol
05  MOV-AMOUNT   PIC S9(13)V99 COMP-3.
```

15 digits, signed, **8 bytes**: `ceil((15 + 1) / 2)`.

Packed decimal stores one decimal digit per 4-bit nibble, two per byte, with the **final nibble
holding the sign**. The digit string is zero-padded on the left to the full 15 digits.

| Sign nibble | Meaning |
|---|---|
| `0xC` | positive - the only positive sign this estate writes |
| `0xD` | negative - the only negative sign this estate writes |
| `0xF` | unsigned; accepted on read, never written |

Packed decimal has **no endianness**: it is a digit string, not an integer, so a Java encoder and a
COBOL runtime produce identical bytes without any byte-order handling. This is why WP-11 can compare
byte for byte.

**Zero is always positive.** `-0` must never be written; it would break byte-for-byte comparison in
WP-11 and produce a phantom break in WP-16.

#### Worked examples

Amount `1 234 567.89 PLN`, so `amountMinor = 123456789`:

```
digits (15, zero-padded)   0 0 0 0 0 0 1 2 3 4 5 6 7 8 9   sign C
nibble pairs               00 00 00 12 34 56 78 9C
bytes                      0x00 0x00 0x00 0x12 0x34 0x56 0x78 0x9C
```

Amount `-1 234 567.89 PLN`, so `amountMinor = -123456789`:

```
digits (15, zero-padded)   0 0 0 0 0 0 1 2 3 4 5 6 7 8 9   sign D
bytes                      0x00 0x00 0x00 0x12 0x34 0x56 0x78 0x9D
```

Amount `0.00 PLN`, so `amountMinor = 0`:

```
digits (15, zero-padded)   0 0 0 0 0 0 0 0 0 0 0 0 0 0 0   sign C
bytes                      0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x0C
```

Only the sign nibble differs between the first two. The maximum representable amount is
`9 999 999 999 999.99`, encoded `0x99 0x99 0x99 0x99 0x99 0x99 0x99 0x9C`.

#### The scale constraint at stratum 0

`V99` is an implied decimal point: COBOL stores 15 digits and applies the scale at compile time, so
`PIC S9(13)V99 COMP-3` and `PIC S9(15) COMP-3` occupy identical bytes. The stored digits therefore
**are** `amountMinor` - but only for a currency whose scale is 2.

The 1995 core was built for a domestic, single-currency bank and hard-codes that assumption. It
cannot represent `JPY` or `BHD` correctly.

> **The integration tier rejects any movement whose currency scale is not 2 before it reaches
> stratum 0.** The rejection is a business outcome with a reason code, not an exception. WP-11 owns
> the check; WP-04 never sees such a record.

This is a real constraint of the kind this repository exists to reproduce, not an oversight to be
tidied away.

---

## 3. Account

The ledger holds account references and **no customer identity at all**. The join to a person happens
in `customer-master`, deliberately, so most of the estate is out of scope for personal data. See
[`../ways-of-working/data-classification.md`](../ways-of-working/data-classification.md).

### Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `accountRef` | string(16) | yes | Estate-wide key. Immutable. |
| `customerRef` | string(12) | yes | Pseudonymous link to `customer-master`. Never a name. |
| `accountType` | enum | yes | `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE` |
| `currency` | string(3) | yes | ISO 4217. An account holds exactly one currency, for life. |
| `status` | enum | yes | `OPEN`, `BLOCKED`, `CLOSED` |
| `bookedBalance` | Money | yes | Settled position. Currency equals `currency`. |
| `availableBalance` | Money | yes | `bookedBalance` less active holds. |
| `openedDate` | date | yes | |
| `lastMovementDate` | date | no | Absent until the first movement posts. |

### Account types

A customer's current account is a **liability** of the bank - the bank owes the customer that money.
Cash and central-bank reserves are **assets**. Getting this right is what separates a ledger from a
`balance` column.

| Type | Increased by | Normal balance |
|---|---|---|
| `ASSET` | debit | debit |
| `LIABILITY` | credit | credit |
| `EQUITY` | credit | credit |
| `REVENUE` | credit | credit |
| `EXPENSE` | debit | debit |

`bookedBalance` is stored with its natural sign; the normal balance above says which sign is expected,
not which is permitted.

---

## 4. Movement

One side of a double-entry posting - one leg. Movements are **immutable**: a mistake is corrected by
a reversing movement that references the original, never by an update or a delete.

### Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `movementRef` | string(23) | yes | `<transferRef>-<legNo>`. |
| `transferRef` | string(20) | yes | The transfer this leg belongs to. |
| `legNo` | integer(2) | yes | `01` is the debit leg, `02` the credit leg. Deterministic, so file output is reproducible. |
| `accountRef` | string(16) | yes | The account moved. |
| `direction` | enum | yes | `DEBIT`, `CREDIT`. Stratum 0 encodes `D` and `C`. |
| `amount` | Money | yes | Always positive. Direction carries the sign, the amount never does. |
| `valueDate` | date | yes | The date the movement affects the balance. |
| `postedAt` | timestamp | yes | When the ledger recorded it. |
| `reference` | string(35) | no | Remittance information. 35 characters, matching the SEPA field. **No personal data.** |

`amount.currency` must equal the `currency` of `accountRef`. The estate performs no conversion.

### Rejections - a stratum 0 artefact

The mainframe writes a rejects file when a movement cannot be applied. A rejection is **not** a fifth
canonical concept: it is a `Movement` plus a reason, and it exists only at stratum 0.

| Field | Type | Meaning |
|---|---|---|
| `movement` | Movement | The record as it arrived, unaltered. |
| `reasonCode` | string(4) | Machine-readable. Codes are defined by WP-04. |
| `reasonText` | string(40) | Operator-readable. **No personal data.** |
| `detectedAt` | timestamp | When the batch rejected it. |

---

## 5. Transfer

The unit of intent: move money from one account to another. A transfer is the customer-facing
concept; movements are its accounting consequence.

### Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `transferRef` | string(20) | yes | Estate-wide key, assigned by the ledger. |
| `idempotencyKey` | string(16-64) | yes | Client-supplied. Required on every money-moving operation. |
| `debitAccountRef` | string(16) | yes | Account debited. |
| `creditAccountRef` | string(16) | yes | Account credited. Must differ from `debitAccountRef`. |
| `amount` | Money | yes | Strictly positive. |
| `status` | enum | yes | `ACCEPTED`, `POSTED`, `REJECTED`, `REVERSED` |
| `reference` | string(35) | no | Copied onto both movements. **No personal data.** |
| `requestedAt` | timestamp | yes | When the client submitted it. |
| `postedAt` | timestamp | no | Absent until `status` is `POSTED`. |
| `reversesTransferRef` | string(20) | no | Present only on a reversal, naming the transfer it reverses. |
| `correlationId` | string(36) | yes | Traces the request across every tier. Strata 2-4 only. |

### Status transitions

```
ACCEPTED -> POSTED   -> REVERSED
         -> REJECTED
```

`POSTED` is terminal for the original transfer; a reversal is a **new** transfer that names the
original in `reversesTransferRef`, never a mutation of it. Stratum 0 has no such field: the mainframe
sees a reversal as an ordinary pair of movements in the opposite direction.

---

## 6. Hold

A reservation against an account's available balance. A hold moves no money: it makes part of the
booked balance unavailable until it is captured into a transfer, released, or expires.

Holds exist at **strata 3 and 4 only**. The 1995 core has no such concept, which is why `ACCTREC`
stores `availableBalance` as a figure computed by the online system rather than deriving it - the
mainframe has nothing to derive it from.

### Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `holdRef` | string(20) | yes | `HL` + `CCYYMMDD` + sequence. |
| `accountRef` | string(16) | yes | The account whose available balance is reduced. |
| `amount` | Money | yes | Strictly positive. Currency equals the account's. |
| `status` | enum | yes | `PLACED`, `CAPTURED`, `RELEASED`, `EXPIRED` |
| `placedAt` | timestamp | yes | |
| `expiresAt` | timestamp | no | Absent means the hold does not expire on its own. |
| `capturedByTransferRef` | string(20) | no | Present only when `status` is `CAPTURED`. |
| `reference` | string(35) | no | **No personal data.** |

### Status transitions

```
PLACED -> CAPTURED
       -> RELEASED
       -> EXPIRED
```

Every transition out of `PLACED` is terminal. A hold is never re-opened; a new hold is placed instead.

`availableBalance` = `bookedBalance` less the sum of every `PLACED` hold on the account. Capture posts
a transfer and clears the hold in one transaction, so available balance never double-counts.

---

## 7. FraudDecision

The outcome of scoring a posted transfer. Produced by `edge/fraud-scoring`, consumed by the ledger
and the integration tier, so it crosses tiers and belongs here rather than inside one service.

A decision **never** reverses a posting by itself. The transfer is already posted when scoring runs;
a `BLOCK` triggers a reversal through the normal reversal path, with its own audit trail. Scoring
that could silently unpost money would be an unauditable side channel.

### Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `transferRef` | string(20) | yes | The transfer that was scored. |
| `decision` | enum | yes | `ALLOW`, `REVIEW`, `BLOCK` |
| `score` | integer, 0-1000 | yes | Higher is riskier. **An integer, not a float** - the same input must always produce the same bytes, and a float makes a decision boundary irreproducible across languages. |
| `reasonCodes` | array of string(8) | no | Why the model decided as it did. Machine-readable. |
| `modelVersion` | string(32) | yes | Which scoring model produced this. Without it a decision cannot be explained months later, which a regulator will ask for. |
| `decidedAt` | timestamp | yes | |
| `correlationId` | string(36) | yes | Ties the decision back to the original request. |

`FraudDecision` exists at **strata 2 to 4 only**. Neither the mainframe nor the SOAP tier has any
concept of it.

---

## 8. Invariants

These hold across the whole estate. Each becomes a test in the package that owns the behaviour.

1. **Double entry.** A transfer produces exactly two movements: one `DEBIT` and one `CREDIT`.
2. **Balance.** Within a transfer, the sum of debit amounts equals the sum of credit amounts.
3. **Single currency.** Every movement of a transfer shares one currency, equal to that of both
   accounts. No conversion anywhere.
4. **Positive amounts.** `Movement.amount` and `Transfer.amount` are strictly positive. Direction, not
   sign, expresses which way money moved.
5. **Immutability.** Movements are append-only. Corrections are reversals that reference the original.
6. **Conservation.** Under concurrent transfers, the sum of all account balances is unchanged.
7. **Idempotency.** The same `idempotencyKey` with the same request returns the original result. The
   same key with a different request is a conflict, not a new transfer.
8. **Scale.** `amountMinor` is interpreted only through `currency` and the ISO 4217 table above.
9. **Holds reserve, they do not move.** A hold changes `availableBalance` only. `bookedBalance`
   changes when the hold is captured into a transfer, never when it is placed.

---

## 9. Cross-era representation

The table WP-03, WP-08, WP-10 and WP-11 check themselves against. Every canonical field, in all four
eras. `-` means the field does not exist in that era, deliberately.

### Money

| Canonical | COBOL-85 | XSD | OpenAPI 3.1 | AsyncAPI 3.0 |
|---|---|---|---|---|
| `amountMinor` | `PIC S9(13)V99 COMP-3` (8 bytes) | `xs:long` | `integer`, `format: int64` | `integer`, `format: int64` |
| `currency` | `PIC X(3)` | `CurrencyCodeType` (`xs:string`, `[A-Z]{3}`) | `string`, `pattern: ^[A-Z]{3}$` | `string`, `pattern: ^[A-Z]{3}$` |

### Account

| Canonical | COBOL-85 | XSD | OpenAPI 3.1 | AsyncAPI 3.0 |
|---|---|---|---|---|
| `accountRef` | `ACCT-REF PIC X(16)` | `AccountRefType` | `string`, 16, pattern | `string`, 16, pattern |
| `customerRef` | `ACCT-CUST-REF PIC X(12)` | `CustomerRefType` | `string`, 12, pattern | - |
| `accountType` | `ACCT-TYPE PIC X(9)` | `AccountTypeType` (enum) | `string`, enum | - |
| `currency` | `ACCT-CURRENCY PIC X(3)` | `CurrencyCodeType` | `string` | - |
| `status` | `ACCT-STATUS PIC X(7)` | `AccountStatusType` (enum) | `string`, enum | - |
| `bookedBalance` | `ACCT-BOOKED-BAL PIC S9(13)V99 COMP-3` | `MoneyType` | `Money` | - |
| `availableBalance` | `ACCT-AVAIL-BAL PIC S9(13)V99 COMP-3` | `MoneyType` | `Money` | - |
| `openedDate` | `ACCT-OPENED-DATE PIC 9(8)` | `xs:date` | `string`, `format: date` | - |
| `lastMovementDate` | `ACCT-LAST-MOVE-DATE PIC 9(8)` | `xs:date` | `string`, `format: date` | - |

### Movement

| Canonical | COBOL-85 | XSD | OpenAPI 3.1 | AsyncAPI 3.0 |
|---|---|---|---|---|
| `transferRef` | `MOV-TRANSFER-REF PIC X(20)` | `TransferRefType` | `string`, 20, pattern | `string`, 20, pattern |
| `legNo` | `MOV-LEG-NO PIC 9(2)` | `xs:int` | `integer` | `integer` |
| `accountRef` | `MOV-ACCT-REF PIC X(16)` | `AccountRefType` | `string` | `string` |
| `direction` | `MOV-DIRECTION PIC X(1)` - `D` / `C` | `DirectionType` (enum) | `string`, enum | `string`, enum |
| `amount` | `MOV-AMOUNT PIC S9(13)V99 COMP-3` + `MOV-CURRENCY PIC X(3)` | `MoneyType` | `Money` | `Money` |
| `valueDate` | `MOV-VALUE-DATE PIC 9(8)` | `xs:date` | `string`, `format: date` | `string`, `format: date` |
| `postedAt` | `MOV-POSTED-TS PIC 9(14)` | `xs:dateTime` | `string`, `format: date-time` | `string`, `format: date-time` |
| `reference` | `MOV-REFERENCE PIC X(35)` | `ReferenceType` | `string`, `maxLength: 35` | `string`, `maxLength: 35` |

`movementRef` is derived at strata 2-4 from `transferRef` and `legNo`; stratum 0 stores only the two
parts, because a fixed-width file has no room for a redundant field.

### Transfer

| Canonical | COBOL-85 | XSD | OpenAPI 3.1 | AsyncAPI 3.0 |
|---|---|---|---|---|
| `transferRef` | via `MOV-TRANSFER-REF` | `TransferRefType` | `string` | `string` |
| `idempotencyKey` | - | - | `Idempotency-Key` header, required | - |
| `debitAccountRef` | via the leg `01` movement | `AccountRefType` | `string` | `string` |
| `creditAccountRef` | via the leg `02` movement | `AccountRefType` | `string` | `string` |
| `amount` | via `MOV-AMOUNT` | `MoneyType` | `Money` | `Money` |
| `status` | - | `TransferStatusType` (enum) | `string`, enum | `string`, enum |
| `reference` | `MOV-REFERENCE PIC X(35)` | `ReferenceType` | `string` | `string` |
| `requestedAt` | - | `xs:dateTime` | `string`, `format: date-time` | `string`, `format: date-time` |
| `postedAt` | `MOV-POSTED-TS PIC 9(14)` | `xs:dateTime` | `string`, `format: date-time` | `string`, `format: date-time` |
| `reversesTransferRef` | - | `TransferRefType`, optional | `string`, nullable | `string` |
| `correlationId` | - | `CorrelationIdType` | `X-Correlation-Id` header | `string`, `format: uuid` |

### Hold

| Canonical | COBOL-85 | XSD | OpenAPI 3.1 | AsyncAPI 3.0 |
|---|---|---|---|---|
| `holdRef` | - | - | `string`, 20, pattern | - |
| `accountRef` | - | - | `string`, 16, pattern | - |
| `amount` | - | - | `Money` | - |
| `status` | - | - | `string`, enum | - |
| `placedAt` | - | - | `string`, `format: date-time` | - |
| `expiresAt` | - | - | `string`, `format: date-time` | - |
| `capturedByTransferRef` | - | - | `string`, 20, pattern | - |
| `reference` | - | - | `string`, `maxLength: 35` | - |

`Hold` is REST-only. It never reaches stratum 0, and the SOAP tier has no use for it, so a whole
column of dashes is the correct answer rather than a gap to be filled.

### FraudDecision

| Canonical | COBOL-85 | XSD | OpenAPI 3.1 | AsyncAPI 3.0 |
|---|---|---|---|---|
| `transferRef` | - | - | - | `string`, 20, pattern |
| `decision` | - | - | - | `string`, enum |
| `score` | - | - | - | `integer`, 0-1000 |
| `reasonCodes` | - | - | - | `array` of `string` |
| `modelVersion` | - | - | - | `string`, `maxLength: 32` |
| `decidedAt` | - | - | - | `string`, `format: date-time` |
| `correlationId` | - | - | - | `string`, `format: uuid` |

`FraudDecision` is event-only: it is never fetched, only published and consumed.

Stratum 0 has no `Transfer` record at all. The mainframe receives movements, and the transfer is
reconstructed from the two legs sharing a `transferRef`. That asymmetry is the point: the 1995 core
was never given the concept.

### Stratum 0 record framing

The copybooks package the concepts above into three fixed-width records. The lengths below are
authoritative: `contracts/check-copybook-offsets.py` asserts against them, and WP-03 generates data to
match them.

| Record | Length | Carries | Read by |
|---|---|---|---|
| `ACCTREC` | 100 | `Account` | `mainframe/`, `batch/recon` |
| `MOVEREC` | 120 | `Movement` | `mainframe/`, `integration/esb-adapter` |
| `REJREC` | 200 | a rejection - `MOVEREC` verbatim, plus the reason | `mainframe/`, `legacy/backoffice` |

Four framing rules, which the checker enforces:

1. **Field order is the order of the tables above.** A record is read positionally; reordering it
   silently reinterprets every byte after the change.
2. **Every record is padded to its stated length with a trailing `FILLER`.** `ACCTREC` carries 21
   spare bytes, `MOVEREC` 13, `REJREC` 22.
3. **Lengths are round numbers.** 100, 120 and 200 are chosen so that a human reading a hex dump can
   find a record boundary without arithmetic, and so that block sizes divide evenly.
4. **A new field consumes `FILLER`, and the record length never changes.** This is the only way to
   extend a fixed-width file that thirty years of downstream programs already read. When the slack
   runs out, the file is versioned, not reformatted.

`REJREC` embeds `MOVEREC` **byte for byte** rather than restating its fields, so a rejected movement
can be re-presented to the next batch run without re-encoding it.

---

## 10. Data protection

| Field | Classification | Rule |
|---|---|---|
| `accountRef`, `customerRef` | Restricted - pseudonymous | May be logged. Never resolved to a person outside `customer-master`. |
| `reference`, `reasonText` | Restricted if misused | Free text. Validated to reject anything resembling personal data, and never populated from customer input without validation. |
| `correlationId`, `transferRef`, `movementRef` | Internal | Log freely. These are what a support engineer traces with. |
| Names, addresses, national identifiers, IBANs | Restricted | **Not in this model, at any tier.** |

An account reference is a pseudonymous identifier, and pseudonymised data is still personal data
under GDPR. It appears here because it is the minimum the ledger genuinely needs.

---

## 11. Changing this document

A change here is a change to every contract derived from it, so it is architecturally significant by
definition.

1. Change this document first.
2. Change all four contracts in the same pull request.
3. Re-run `contracts/validate.sh`.
4. Record an ADR if the change alters a byte layout, an invariant or a wire format.
5. Name the affected consumers in the pull request. A change to the COMP-3 layout breaks WP-03,
   WP-04, WP-05, WP-11 and WP-16 at once.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-INT-006 Business concepts are defined once and shared across eras | This document |
| REQ-LED-001 Money is minor units plus an ISO 4217 code, never floating point | Section 2 |
| REQ-LED-002 Currency scale is resolved from ISO 4217, per currency | Section 2, scale table |
| REQ-LED-003 Double-entry postings are balanced and immutable | Section 8, invariants 1, 2 and 5 |
| REQ-MF-001 Packed-decimal amounts are byte-identical across tiers | Section 2, COMP-3 |
| REQ-DP-002 The ledger holds no customer identity | Sections 3 and 10 |
