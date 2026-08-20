# esb-adapter

**Stratum 2** | **Java 8, Spring Boot 2.7.18** | **Built by WP-11**

Connects 2023 to 1995. Consumes a Kafka transfer event, transforms it to canonical XML by XSLT,
calls the 2011 SOAP service, and - in WP-11b - writes a fixed-width movement record in COMP-3 packed
decimal for tonight's COBOL batch run.

The most interesting engineering in the repository, because every one of those steps is a real
integration problem that banks genuinely solve this way.

```bash
make jdk8              # which JDK 8 the build will use
make build-integration # target/esb-adapter-1.0.0-SNAPSHOT.jar
make test-integration  # the suite, including the end-to-end hop - needs Docker
```

**WP-11a is built: the 2019-to-2011 hop.** The 2011-to-1995 hop - COMP-3, `MOVEMENT.DAT` and the
overnight cycle - is WP-11b.

## What is here

| | |
|---|---|
| Build | Maven 3, inheriting [`platform/parent-pom`](../../platform/parent-pom/), `-Xlint:all -Werror` |
| Runtime | Spring Boot 2.7.18 on Java 8 - the last Boot line that supports it, which is the point |
| Tests | JUnit 5, because 2019 would have used it. Stratum 1 uses JUnit 4 for the same reason |
| Inbound | Spring Kafka, manual acknowledgement, `earliest` offset reset |
| Transformation | An XSLT **file**, run by the JDK. No Saxon, no dependency |
| Outbound | JAX-WS client generated from `contracts/wsdl/`, using the JDK's own runtime |

## The hop, in order

1. **Consume** `tessera.ledger.transfer-posted.v1`. Delivery is at-least-once and the contract says
   so.
2. **Transform** the JSON to `tb:canonicalTransfer` by XSLT, and **validate the result against
   `canonical-v1.xsd`** before it goes anywhere. A stylesheet that drops one element produces a
   document that looks entirely plausible; the schema is what notices.
3. **Refuse** a currency the mainframe cannot represent - see below.
4. **Call** `NotifyTransferPosted` on `customer-master`.

WP-11b adds a fifth step, and it is downstream of step 4 on purpose: nothing is written to the
movement file unless the SOAP call succeeded.

## Idempotency belongs to the far end

This component keeps **no record of what it has already sent**, and that is a decision rather than an
omission.

`NotifyTransferPosted` is idempotent on `transferRef` because the system of record claims the
transfer with a unique constraint and answers `alreadyApplied`. A second record kept here would be a
second source of truth about the bank's money, able to disagree with the first - which is precisely
the drift `batch/recon` exists to detect, manufactured on purpose.

WP-11b needs its own answer, because a file has no unique constraint.

## Two answers when something fails, and only two

| | What happens | Why |
|---|---|---|
| **Permanent** - malformed payload, schema violation, business fault | Recorded on the dead-letter channel, offset acknowledged | The message is wrong and will still be wrong in five minutes. Retrying it forever blocks every transfer behind it |
| **Transient** - the far end unreachable | **Not** acknowledged. The broker redelivers and the partition waits | Skipping past a failure can deliver a reversal before the transfer it reverses. WP-09 made the same choice in the outbox relay |

An exception nobody classified is treated as transient, because a bug in this component is not a
reason to discard somebody's payment.

The dead-letter channel is declared in
[`contracts/asyncapi/esb-adapter-events.yaml`](../../contracts/asyncapi/esb-adapter-events.yaml),
which **this component owns** - a dead letter is something that happened to a consumer, not to the
bank, so it does not belong on the ledger's channels. That split is the estate acting on what F-34
and F-51 both record: an error surface belongs in a contract rather than in a README.

The original payload is carried verbatim and the reason never quotes it. A dead-letter topic is
retained and widely readable, and the remittance reference is the one field a paying customer
controls.

## A currency the mainframe cannot represent is refused here

`PIC S9(13)V99 COMP-3` hard-codes two decimal places. JPY has none and BHD has three, so neither can
reach stratum 0 without being wrong by a factor of a hundred or a thousand - and wrong in a way that
prints as an ordinary number.

The estate's rule since WP-03 is defence in depth: this tier refuses such a movement **before it
arrives**, and the mainframe validates it again, because a 1995 core does not trust its feeds. An
**unknown** currency is refused for the same reason - defaulting to two would be right most of the
time, which is what would make the exceptions so hard to find.

Refused before the SOAP call, not after: telling the system of record about a transfer that was
never going to reach the mainframe would leave the two halves of the estate disagreeing.

## One value is a substitution, and it is not a fact

`tb:Transfer` makes **`requestedAt` mandatory** and the ledger's event carries no request timestamp
at all - `TransferPostedPayload` has `postedAt` and nothing else. This tier therefore cannot know
when the customer asked.

It sends `postedAt`. A transfer is requested no later than it is posted, so that is the tightest
upper bound this component can defend rather than a number invented to fill a mandatory element -
but it is still not the truth, and a consumer comparing the two timestamps will find them always
equal. Logged as a follow-up. The real fix is in another package's contract, and WP-11's Out of
scope forbids changing either from here.

`status` is the other derived value, and that one is sound: the event means the transfer was
`POSTED`.

## Running the tests

`make test-integration` runs `mvn verify`, which is where the end-to-end test lives. It brings up a
real Kafka, a real Oracle and a real Tomcat 8.5 with **customer-master's own WAR** on it, publishes
an event, and then looks at Oracle to see whether the money moved.

Not a stub, deliberately. Two independently generated clients meet at this hop - stratum 1 generated
its server interface from `customer-master-v1.wsdl` and this module generated a client from the same
document - and nothing whatsoever guarantees they agree until a real call is made.

The target depends on `build-legacy`, because the WAR has to exist. First run pulls the Oracle image
(~2GB) and fetches Tomcat 8.5.100 from the Apache archive.

## Two things that will bite the next person

- **`-Werror` and generated code.** `wsimport` emits one unfixable `[serial]` warning, so the
  generated tree compiles in its own execution with that category suppressed and the hand-written
  tree keeps the parent's flags. Same arrangement as stratum 1.
- **Boot's repackaged jar and failsafe.** `spring-boot-maven-plugin:repackage` moves the classes
  into `BOOT-INF/classes`, and failsafe puts the project artifact ahead of `target/classes` - so
  the application's own classes vanish and JUnit reports only *"failed to discover tests"*, naming
  nothing. `<classesDirectory>` in the failsafe configuration is the fix.
