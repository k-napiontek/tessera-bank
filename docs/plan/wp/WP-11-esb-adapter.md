# WP-11 - esb-adapter, the bridge between eras

| | |
|---|---|
| **Ticket** | TB-1011 |
| **Branch** | `feat/TB-1011-esb-adapter` |
| **Stratum** | 2 - Java 8 + Spring Boot 2.7.18, ~2019 |
| **Depends on** | WP-09, WP-10 |
| **Status** | `Not started` |

## Objective

Connect 2023 to 1995. This component consumes a modern Kafka event, transforms it to canonical XML by
XSLT, calls a 2011 SOAP service, and writes a fixed-width record in COMP-3 packed decimal for a COBOL
batch program to read tonight. It is the most interesting engineering in the repository, because
every one of those steps is a real integration problem that banks genuinely solve this way.

## In scope

- Kafka consumer for the transfer-posted event, per `contracts/asyncapi/`.
- Canonical JSON to canonical XML transformation by XSLT, against `contracts/xsd/`.
- SOAP client calling `customer-master`, generated from its WSDL.
- **COMP-3 packed-decimal encoder** producing movement records byte-identical to what `ACCTPOST`
  expects, sign nibble included.
- Fixed-width movement file writer with correct field offsets and padding.
- Idempotent handling of duplicate events, since the outbox relay is at-least-once.
- A dead-letter path for messages that cannot be transformed or delivered.

## Out of scope

- Any change to the ledger, the monolith or the COBOL programs. This package adapts; it does not
  modify what it adapts between.
- Message broker infrastructure - platform repositories.

## Constraints

- **Spring Boot 2.7.18 on Java 8.** This is the exact version block the industry is pinned to and it
  is the reason this stratum exists. Do not upgrade it; see `CLAUDE.md`.
- The COMP-3 encoder must be tested against **real bytes**, not against its own understanding of the
  format. Fixtures come from `mainframe/` and equality is asserted byte for byte, sign nibble
  included - a positive value ends `0x0C`, a negative one `0x0D`.
- The XSLT is a file, not string concatenation in Java.
- At-least-once delivery means duplicates will arrive. Handling must be idempotent and tested with an
  actual redelivery, not assumed.
- Nothing may be written to the movement file unless the SOAP call succeeded, and a partial write
  must not corrupt the file for the batch run.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] A transfer event produces a movement record that `ACCTPOST` reads and applies correctly.
- [ ] COMP-3 encoding matches the mainframe fixtures byte for byte, including negatives and zero.
- [ ] A redelivered event produces no duplicate movement record.
- [ ] A transformation failure lands in the dead-letter path with enough context to diagnose it.
- [ ] Runs on JDK 8 with Spring Boot 2.7.18.

## Verification

End-to-end: publish a transfer event, confirm the SOAP call reaches `customer-master`, confirm the
movement file gains a record, then run the WP-05 EOD cycle and confirm the COBOL master balance
changes by the expected amount. Redeliver the same event and confirm the file is unchanged.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-INT-003 Modern events reach the mainframe in its own format | COMP-3 encoder, file writer |
| REQ-INT-004 Duplicate delivery does not duplicate a movement | idempotent handling |
| REQ-INT-005 Undeliverable messages are captured, not lost | dead-letter path |
