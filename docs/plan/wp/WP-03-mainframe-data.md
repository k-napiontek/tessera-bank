# WP-03 - Mainframe copybooks and synthetic data

| | |
|---|---|
| **Ticket** | TB-1003 |
| **Branch** | `feat/TB-1003-mainframe-data` |
| **Stratum** | 0 - COBOL-85, ~1995 |
| **Depends on** | WP-02 |
| **Status** | `Not started` |

## Objective

Establish the mainframe data layer: the copybooks that define every fixed-width record, and the
synthetic account master and movement files the batch programs will read. Getting the record layouts
and the packed-decimal representation exactly right here is what makes every later COBOL and Java
interop task tractable, because both sides will encode against these definitions.

## In scope

- `ACCTREC.CPY` - account master record: account number `PIC X(10)`, currency `PIC X(3)`, balance
  `PIC S9(13)V99 COMP-3`, status `PIC X(1)`, last movement date `PIC 9(8)`.
- `MOVEREC.CPY` - movement record: account number, amount, direction, value date, reference,
  originating system.
- `REJREC.CPY` - reject record: the movement plus a reason code.
- A synthetic account master file and a day of synthetic movements, generated rather than hand-typed.
- The generator script that produces them, so the data can be regenerated and varied.
- Documentation of the COMP-3 packed-decimal representation, including the sign nibble, since the
  Java side in WP-11 must reproduce it byte for byte.

## Out of scope

- Any COBOL program - `ACCTPOST` is WP-04, `EODREPT` is WP-05.
- JCL - WP-05.
- Any Java-side encoder or decoder - WP-11.

## Constraints

- COBOL-85 fixed format: columns 1-6 sequence, 7 indicator, 8-11 area A, 12-72 area B. Tabs are
  forbidden - they destroy column alignment and the compiler rejects the source.
- Record layouts must match `contracts/copybook/` exactly. If they need to differ, the contract
  changes first.
- **All data is synthetic.** No name, address or identifier may resemble a real person.
- Amounts must include the awkward cases deliberately: zero, the maximum representable value,
  negative balances on overdraft-permitted accounts, and a currency with a non-2 decimal scale.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] Copybooks compile when included by a trivial COBOL program.
- [ ] The generator produces deterministic output for a given seed.
- [ ] A hex dump of a generated master record matches the documented COMP-3 layout byte for byte.
- [ ] No data resembles a real person.

## Verification

Compile a throwaway COBOL program that copies each copybook, confirming the layouts are valid. Dump
generated records with `od -c` / `xxd` and check field offsets and the packed-decimal sign nibble
against the documented layout.

**Contract conformance (WP-02).** Hex-dump a generated master record and a generated movement
record and check every field against [`../../../contracts/copybook/column-map.md`](../../../contracts/copybook/column-map.md),
byte for byte. The COMP-3 amounts must cover a positive value, a negative value and zero, with
sign nibbles `0x0C`, `0x0D` and `0x0C` respectively.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-MF-001 Record layouts are defined once and shared | `mainframe/copybook/` |
| REQ-MF-002 Money on the mainframe is packed decimal, not binary or text | `ACCTREC.CPY` |
| REQ-DP-001 All test data is synthetic | the generator |
