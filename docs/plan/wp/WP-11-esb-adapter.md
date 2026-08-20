# WP-11 - esb-adapter, the bridge between eras

| | |
|---|---|
| **Ticket** | TB-1011 |
| **Branch** | `feat/TB-1011-esb-soap-hop` (11a), `feat/TB-1011-esb-comp3-hop` (11b) |
| **Stratum** | 2 - Java 8 + Spring Boot 2.7.18, ~2019 |
| **Depends on** | WP-09, WP-10b |
| **Status** | `Done` - both halves merged; see `STATUS.md` |

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

Detailed 2026-08-20. The package lands as **two halves on one ticket**, WP-11a and WP-11b, each its
own branch and pull request, tracked as two rows in `STATUS.md`. Detailed out it spans a Spring Boot
module, a Kafka consumer, an XSLT transformation, a generated SOAP client, a packed-decimal encoder
and a fixed-width file the overnight COBOL cycle reads - comparable to WP-09, which landed as two
pull requests, and to WP-10, which was the first package split in the plan instead. `STATUS.md`
records that as the right answer at this size. This file stays a single document because four others
link to it.

**The split falls on the era boundary each half crosses**, not on an arbitrary line through the
middle:

- **WP-11a is 2019 to 2011.** A modern Kafka event arrives, becomes canonical XML by XSLT, and is
  delivered to a 2011 SOAP service.
- **WP-11b is 2011 to 1995.** The same transfer becomes a fixed-width record in COMP-3 packed
  decimal that tonight's COBOL match-merge applies to the account master.

That boundary is forced by a constraint this package already carries: **nothing may be written to the
movement file unless the SOAP call succeeded.** The file half is therefore downstream of the SOAP
half in the running code as well as in the plan, and a split anywhere else would put half of one
transaction in each pull request.

Three decisions were taken with the repository owner before any code:

- **The SOAP hop is tested against the real `customer-master` WAR**, deployed to a real Tomcat 8.5
  against real Oracle, exactly as WP-10b's own deployment test does it. Not a stub. This estate
  tests against real PostgreSQL, real Kafka, real Oracle and a real Tomcat, and the reason is
  recorded in `STATUS.md`: the WP-14 walkthrough found three defects that a passing hermetic suite
  of 120 tests had not. A stub verifies what the ESB *says*; only the real endpoint verifies that
  `customer-master` understands it - which is the entire risk when two independently generated
  clients meet.
- **Two halves on one ticket**, as above.
- **Maven, inheriting the corporate parent POM.** `platform/parent-pom` already names
  `integration/esb-adapter` as a consumer, and its enforcer rule refuses any JDK outside
  `[1.8,1.9)` - so the Java 8 pin is enforced here by the same mechanism that enforces it at
  stratum 1, rather than by a second one that can disagree with it.

### WP-11a - the event, the transformation and the SOAP hop

Branch `feat/TB-1011-esb-soap-hop`. Eight tasks, roughly one commit each, test-first throughout.

1. Detail this task list, split the `STATUS.md` row into 11a and 11b, correct the "next actionable
   package" blockquote, and set 11a `In progress`.
2. **The dead-letter channel, in the contract first.** A message this component cannot transform or
   deliver has to go somewhere, and if that somewhere is a Kafka topic then it is an interface and
   belongs in `contracts/` **before** the code that writes to it - `PROTOCOL.md` phase 2. Decide and
   record whether it belongs in `ledger-events.yaml` beside the topic it dead-letters, or in a
   contract this component owns: `ledger-events.yaml` is the producer's document and the failure is
   the consumer's, which is the same question F-34 and F-51 both raise about error surfaces having
   no contract. Whichever way it goes, the decision is recorded, not made silently.
3. **Maven module and the Java 8 gate.** `integration/esb-adapter/pom.xml`, inheriting
   `tessera-parent`, importing the `spring-boot-dependencies` **2.7.18** BOM so every Spring version
   is pinned by one import. JUnit 4 or 5 is a genuine choice here and unlike at stratum 1 it is not
   obvious - Boot 2.7 ships JUnit 5 by default and 2019 was past the changeover - so pick one and
   say why. `ToolchainTest` and a bytecode-version test as at stratum 1, because generated SOAP
   sources land in this module too. `make build-esb` and `make test-esb` following the existing
   `test-legacy` pattern.
4. **The consumer, and idempotency.** Consume `tessera.ledger.transfer-posted.v1` against a real
   Kafka through Testcontainers - `confluentinc/cp-kafka:7.6.1`, the image `KafkaOutboxContractTest`
   already uses. **De-duplicate on `transferRef`**, which the contract states is stable across
   republication. A sequential test proves nothing about idempotency: WP-10a proved that by
   mutation, where a read-then-write version passed all fourteen sequential tests and failed under
   two simultaneous deliveries. Test the redelivery, and test it concurrently.
5. **JSON to canonical XML, by XSLT.** The stylesheet is a **file**, never string concatenation in
   Java, and its output is `tb:canonicalTransfer` - the global element `canonical-v1.xsd` already
   declares for exactly this purpose, carrying one `tb:Transfer` and exactly two `tb:Movement`. The
   output is **validated against the canonical schema before it goes anywhere**, so a transformation
   that produces a plausible-looking document with a missing element fails here rather than at the
   far end. XSLT consumes XML and the event is JSON, so an intermediate document is unavoidable:
   decide between the JDK's own XSLT 1.0 over a Jackson-produced intermediate and Saxon-HE with
   XSLT 3.0's `json-to-xml()`, and record it - Saxon is a dependency and this estate justifies
   dependencies rather than avoiding them, but it justifies them in writing.
6. **The SOAP client, generated from the WSDL.** `wsimport` over
   `contracts/wsdl/customer-master-v1.wsdl`, read in place because it imports the canonical schema
   by a relative path. Nothing generated is committed and a test asserts it, as at stratum 1. This
   is the **second** independently generated client for that contract; the first is
   `customer-master`'s own server side, and the two meeting is what task 7 exists to check.
7. **The SOAP hop, against the real thing.** Boot the `customer-master` WAR on Tomcat 8.5 against
   Oracle and call `NotifyTransferPosted` through it. Assert the balance moved, and assert a
   redelivery answers `alreadyApplied` - the far end is idempotent too, and this is the first time
   both idempotency mechanisms are exercised together. **Reject a currency whose ISO 4217 scale is
   not 2 before the call**, because stratum 0 cannot represent one and the integration tier is where
   the estate says that check belongs. WP-11b asserts the same thing again at the encoder, which is
   the defence in depth the decision log already describes for this rule.
8. **Documentation and landing.** Module README, `integration/README.md`, TD-001 and TD-002 checked
   against what now exists, traceability for REQ-INT-004 and REQ-INT-005, `STATUS.md`, pull request,
   merge.

### WP-11b - COMP-3, the movement file and the overnight cycle

Branch `feat/TB-1011-esb-comp3-hop`. Seven tasks.

1. Set 11b `In progress` and carry forward whatever 11a learned.
2. **The COMP-3 encoder.** `PIC S9(13)V99 COMP-3`: fifteen digits in eight bytes, the last nibble the
   sign. **Tested against real bytes, never against its own understanding of the format** - the
   fixtures are `mainframe/data/`'s, and `comp3.py` is the reference implementation that already
   produces them. Positive and zero end `0x0C`, negative ends `0x0D`. Assert byte for byte,
   including a negative amount and zero, and including the maximum representable balance the WP-03
   generator deliberately seeds. A currency of scale other than 2 is refused here as well as at the
   SOAP hop.
3. **The fixed-width writer.** `MOVEREC`, 120 bytes, per `contracts/copybook/MOVEREC.CPY` and the
   column map. Two records per transfer - leg 01 the debit, leg 02 the credit - sharing one
   `MOV-TRANSFER-REF`, which is how the batch rebuilds a transfer this tier keeps no record of. The
   offsets come from the copybook and are asserted against `contracts/check-copybook-offsets.py`'s
   view of it, not from counting characters by hand.
4. **Writing safely.** `ORGANIZATION IS SEQUENTIAL`, never line sequential - a COMP-3 amount can
   contain `0x0D`, and a fixed-width record is padded with `0x20` that line sequential strips, so
   every record would lose its length. **A partial write must not corrupt the file for the batch
   run**: decide the mechanism - append under a lock, or write-and-rename - and test the failure,
   not only the happy path.
5. **The dead-letter path completed.** 11a routes a message that cannot be transformed or delivered;
   this half adds the one that cannot be *encoded*, which is a different failure with a different
   cause. The test asserts what a person diagnosing it at 02:00 actually gets: enough context to
   identify the transfer without the payload leaking a remittance reference into a log.
6. **The end-to-end run.** Publish a transfer event; confirm the SOAP call reached
   `customer-master`; confirm `MOVEMENT.DAT` gained two records; run the WP-05 cycle with
   `run-eod.sh --movements` and confirm the COBOL master balance changed by the expected amount.
   Then redeliver the event and confirm the file is unchanged. This is the first time in this
   repository that a single transfer crosses all four eras, and it is the package's whole claim.
7. **Documentation and landing.** Module README, the traceability section for REQ-INT-003, the
   Definition of Done ticked with the byte-comparison as evidence, `STATUS.md`, pull request, merge.

### What will be hard, and is supposed to be

- **Two independently generated clients meeting.** `customer-master` generated its server interface
  from the WSDL; this module generates a client from the same document. Nothing guarantees they
  agree until a real call is made, which is why task 7 of 11a makes one.
- **The COMP-3 sign nibble.** The trap CLAUDE.md records for stratum 0 applies here in reverse: an
  encoder that writes `0x0F` for positive produces a file every reader accepts and the mainframe
  reads as unsigned. `comp3.py` and the fixtures are the arbiter.
- **Ordering.** The copybook says the file is ascending by `MOV-ACCT-REF`, and this tier writes in
  the order events arrive. The cycle's `STEP010` sorts, which is why that step exists - so this
  module must **not** try to sort, and the reason belongs in a comment where somebody would
  otherwise add one.

## Definition of Done

The half that satisfies each box is named, because two pull requests cannot both tick all five.

- [x] A transfer event produces a movement record that `ACCTPOST` reads and applies correctly. *(11b)*
- [x] COMP-3 encoding matches the mainframe fixtures byte for byte, including negatives and zero. *(11b)*
- [x] A redelivered event produces no duplicate movement record. *(11a for the consumer, 11b for the file)*
- [x] A transformation failure lands in the dead-letter path with enough context to diagnose it. *(11a; 11b adds the encoding failure)*
- [x] Runs on JDK 8 with Spring Boot 2.7.18. *(11a)*
- [x] The SOAP request is understood by a really-deployed `customer-master`, not by a stub. *(11a)*
- [x] Checked against [`../../ways-of-working/definition-of-done.md`](../../ways-of-working/definition-of-done.md).

## Verification

```bash
make jdk8                                        # names the JDK 8 this tier will use
make test-integration                            # the module: Kafka, XSLT, SOAP, and (11b) COMP-3
make test-legacy                                 # the far end of the SOAP hop is still green
make eod                                         # the overnight cycle still runs (11b)
bash contracts/validate.sh                       # the contracts still agree with the model
make test                                        # every other tier still green
```

Needs Docker: real Kafka, real Oracle and a real Tomcat 8.5 all start during this suite. 11b's
four-era run adds GnuCOBOL and python3, because it executes the WP-05 cycle for real.

The target is `make test-integration`, not `make test-esb`. Task 3 of 11a named the latter; the
module that landed follows the tier naming every other stratum uses - `test-legacy`,
`test-services`, `test-integration` - and this block is corrected to the command that exists.

End-to-end, and it is 11b's claim rather than 11a's: publish a transfer event, confirm the SOAP call
reaches `customer-master`, confirm the movement file gains a record, then run the WP-05 EOD cycle and
confirm the COBOL master balance changes by the expected amount. Redeliver the same event and confirm
the file is unchanged.

**Contract conformance (WP-02).** Compare the COMP-3 encoder's output byte for byte against the
WP-03 fixtures, including a negative amount and zero. Also assert that a currency whose ISO 4217
scale is not 2 is rejected before it reaches stratum 0 - the copybook cannot represent it.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-INT-003 Modern events reach the mainframe in its own format | COMP-3 encoder, file writer |
| REQ-INT-004 Duplicate delivery does not duplicate a movement | idempotent handling |
| REQ-INT-005 Undeliverable messages are captured, not lost | dead-letter path |
