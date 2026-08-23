# RCA: a permanent data error made the integration tier discard transfers, and nothing said so

| | |
|---|---|
| **Incident** | INC-001 |
| **Severity** | P2 throughout. Not reclassified - see [Impact](#impact) for why it did not become P1 |
| **DORA** | **Not major.** No client was affected and no data was lost from a system of record; the loss is of *propagation* between two cores. Criterion by criterion in [DORA](#dora-classification) below |
| **Detected** | 2026-08-23 09:05:39Z, from the morning reconciliation read in `legacy/backoffice` |
| **Contained** | Not contained. The damage was complete before the control could see it - see [What went wrong in the response](#what-went-wrong-in-the-response) |
| **Resolved** | 2026-08-23 09:13:34Z, `opened_date` restored. Removal verified at 09:28:37Z on the corrected harness, by driving a further business date and watching the account post again |

**This was a deliberate exercise**, [WP-18a](../plan/wp/WP-18-incident-exercise.md), against a fault
injected on purpose into a synthetic estate. Everything below happened. The response log with
timestamps is [`workload/baselines/incident/RESPONSE.md`](../../workload/baselines/incident/RESPONSE.md)
and the evidence sits beside it.

---

## What happened

An operator's data fix moved one account's `opened_date` forward by a day in `legacy/customer-master`
and cleared its `last_movement_date` in the same statement. Both fields had to move: the estate's own
`ACCOUNT_MOVEMENT_CK` refuses an `opened_date` later than the account's movement watermark, so
**the constraint that exists to catch exactly this correction was satisfied by widening the
correction**.

From that moment every movement dated on the business day being processed was refused `ORA-02290` by
stratum 1. What was expected next - and what **F-106** predicts - is that the refusal reaches
`esb-adapter` as a generic SOAP fault, is classified transient, and is retried for ever, blocking the
partition so that the backlog is visible and recoverable.

**That is not what the estate does.** `DefaultErrorHandler` carries Spring Kafka's default
`FixedBackOff(interval=0, maxAttempts=9)`. The record was retried ten times in about 150 milliseconds,
the backoff was **exhausted**, the record was handed to the default recoverer, which logs it, and the
offset was committed. The consumer moved on. The dead-letter topic
`tessera.esb.transfer-posted.dlt.v1` was never written to.

Two transfers - `TB202608230000016816` and `TB202608230000018445` - were posted to the ledger, are
in its audit chain, and **do not exist for the mainframe**. Consumer lag returned to zero, the
mainframe rejected nothing, `REJECTS.DAT` was empty for every business date, and every instrument in
the estate read healthy.

The reconciliation on the day it happened was **correctly silent**: a transfer that never reaches the
movement file is in neither half of what `batch/recon` expects the master to hold - not in the file,
and not earlier-dated - so the master and the expectation agreed while the bank was short. It
surfaced a full cycle later, on **D+1**, when those value dates fell behind the business date, as
`VALUE_DRIFT` on three accounts.

## Impact

| | |
|---|---|
| Transfers lost between the cores | **2** |
| Accounts whose two cores disagree because of it | **3** |
| Value | 63.40 and 264.46, both PLN |
| Business dates affected | 2026-03-02, surfacing 2026-03-03 |
| Records lost from a system of record | **none** - the ledger holds both transfers and its audit chain is intact |
| Customer-visible impact | none. The ledger is what the customer sees; it is the mainframe that is short |

**Why it stayed P2.** Money movement was blocked and a control was not operating, which is P2. It
never became P1 because no balance a customer can see is wrong - the ledger is complete. What is
wrong is that the mainframe's master and the ledger disagree, and no process in this estate will ever
reconcile them again without somebody replaying the two transfers by hand.

**The recovery figure is the part worth reading.** After the fault was removed and a further business
date driven, **zero of the affected accounts cleared.** The fault is reversible; its cost is not.

## Why it happened

Three correct decisions, none of them a bug on its own.

1. **`CustomerMasterEndpoint` lets a `DataAccessException` become a generic SOAP fault** rather than
   the WSDL's declared `ServiceFault`, and its own comment says why: the declared fault means "your
   request was wrong", and a caller that cannot tell that from "we are broken" will retry the first
   and give up on the second. Correct.
2. **`CustomerMasterClient` reads it exactly that way**: `ServiceFaultMessage` is permanent and
   dead-lettered, `WebServiceException` is transient and retried. Correct.
3. **An Oracle check-constraint violation is technical by exception type and permanent in fact.** The
   same bytes fail identically for ever. It falls in the gap between 1 and 2, which is **F-106**.

What F-106 did not know, and this exercise established, is the fourth thing:

4. **`esb-adapter` configures no error handler**, so it inherits Spring Kafka's default
   `FixedBackOff(0L, 9)` and the default recoverer. The consequence is not a blocked partition. It is
   **ten retries in 150 milliseconds, then silent discard, with the offset committed** - and
   `DeadLetterRecorder` never runs, so **F-109**'s fire-and-forget send is not even reached.

`REQ-INT-005` says undeliverable messages are captured rather than lost. For this class they are
neither captured nor retried: they are dropped, and the drop is indistinguishable from success in
every signal the estate emits.

## Why it was not caught sooner

**The reconciliation is a lagging detector for this class, by design and correctly.**
[ADR 0015](../governance/adr/0015-the-cut-off-is-the-movement-file.md) makes the movement file the
cut-off, so a fault that stops a posting *reaching* the file produces no break on the day it happens.
One full cycle of blindness is the price of the timing/drift distinction being exact, and it is worth
paying. What the estate does not have is any **leading** detector to sit beside it.

Everything that could have been one was silent:

| Signal | What it said |
|---|---|
| Consumer lag | zero |
| Dead-letter topic | empty, and never written to |
| `REJECTS.DAT` | empty for every business date |
| Adapter error rate | 20 WARN lines out of 31 552 crossings, and they say *"the system of record could not be reached"* - which is false. It was reached, and it refused |
| Stratum 1 and stratum 2 metrics | **there are none at all** - F-100 and F-108. The entire hop is unobservable from inside |

**Detection latency here is the latency of a human reading a report**, and that is a property of this
repository rather than of the bank: [ADR 0012](../governance/adr/0012-slo-catalogue-boundary.md) puts
alerting in the platform repositories on purpose. Any time-to-detect this document quotes is a
property of the exercise.

### The finding that outgrew the incident

Working the break meant separating it from the noise, and the noise is the real discovery.

| Business date | Accounts in `VALUE_DRIFT` | New that morning | Of those, the incident |
|---|---|---|---|
| 2026-03-02 | 224 | - | **0** |
| 2026-03-03 | 271 | 47 | **3** |
| 2026-03-04 | 476 | 205 | **0** |

**F-104** leaves `CaptureRequest` and `ReversalRequest` with no `valueDate`, so a hold capture and a
reversal carry `LocalDate.now()`. `batch/recon` bounds the ledger side at
`value_date <= business_date` and cannot see them. **F-107**'s other writer - the real ESB, rather
than `generate.py` - puts them in the movement file anyway, so the master applies them permanently.

The two findings compose, and the population **compounds**: 224 accounts, then 271, then 476 over
three consecutive business dates, none of the growth after D+1 having anything to do with the
incident. Three points establish a direction rather than a rate, and the direction is enough.

**By `reconciliation-break.md`'s own escalation thresholds - more than ten accounts broken, absolute
drift over 10 000.00 - every morning in this estate is already an incident.** A control that declares
an incident every day is a control that gets turned off, which is precisely the failure ADR 0015
exists to prevent. Three accounts of genuine loss arrived inside forty-seven accounts of report, on a
floor of two hundred and twenty-four, and **nothing in the report, the screen or the runbook
separates them**. The responder did it with an ad-hoc SQL query against the ledger's `value_date`.

## What went wrong in the response

- **Containment had nothing to do, and the procedure has no category for that.** Every containment
  action `incident-management.md` offers - stop the driver, stop the consumer, hold the cycle -
  assumes the estate is still making the problem larger. Here the damage was complete within about
  150 milliseconds of the poisoned record being polled, roughly five minutes before the report that
  revealed it existed. An incident that is over before it is
  visible is a shape this procedure does not describe.
- **The runbook's `VALUE_DRIFT` step 4 sent the responder the wrong way.** "A difference equal to
  twice a movement's value means it was applied twice on one side, or with the wrong sign" is a real
  signature, and here `ledger = opening + 2 x (master - opening)` was one day's movements against two
  days' postings. Roughly two minutes lost. The step is not wrong; it needs the other reading beside
  it.
- **The estate could not answer "which of these breaks are new information".** There is no baseline
  the morning's report is read against, so the only way to tell 3 accounts from 47 was to query the
  ledger directly. That is not a step in any procedure this repository has.
- **The adapter's log says something false.** *"the system of record could not be reached"* is what an
  operator sees for a permanent data refusal. It sends the response to the network and the endpoint,
  which are fine.
- **Two defects were in the harness rather than in the bank**, and the response spent its middle
  section proving it. The injector rotated the movement file at the moment the day began, and because
  the adapter runs about a minute behind the ledger it took **449 of the day's own transfers** with
  it - so the first report of this incident named 451 lost transfers when the fault had cost two. The
  injector had also announced a fault Oracle refused, because its `UPDATE` violated the same
  constraint and `sqlplus` exits 0 on a SQL error unless told otherwise. **A fixture that does not
  read back what it planted will report a fault it never injected**, and every number downstream will
  look entirely plausible.

## What changes

| # | Change | Owner | Tracked as |
|---|---|---|---|
| 1 | `esb-adapter` must not inherit `FixedBackOff(0, 9)` and the logging recoverer. A permanent refusal has to reach the dead-letter path, and an exhausted retry must never commit the offset silently | WP-11 | **F-111** |
| 2 | Stratum 1 must distinguish a constraint violation from a reachability failure, so the hop can classify it permanent. The adapter's operator-facing message must stop saying the system of record could not be reached when it was | WP-10 / WP-11 | **F-112** |
| 3 | The reconciliation needs the machine-clock population out of it - F-104 in the contract, F-107 declared - or the report is unreadable within weeks | WP-08 / WP-11 | **F-113** |
| 4 | The morning report needs to say what is *new* since the previous one. Separating three accounts from forty-seven should not need a SQL query | WP-16 | **F-114** |
| 5 | `reconciliation-break.md` gains the second reading of a doubled difference, and the evidence step that separates new breaks from standing ones | WP-18a | landed with this RCA |
| 6 | `incident-management.md` gains the case where the damage is complete before detection and containment has nothing to do | WP-18a | landed with this RCA |
| 7 | The injector reads back what it planted, and separates seeding's movements by value date rather than by racing the file | WP-18a | landed, `01ac034` and `b6602ca` |

## DORA classification

Assessed against the criteria in [`incident-management.md`](../ways-of-working/incident-management.md#classification-criteria).

| Criterion | This incident |
|---|---|
| Clients and counterparts affected | **None.** 3 account references, all synthetic; the ledger the customer sees is complete |
| Data losses | **None from a system of record.** The ledger holds both transfers and its audit chain verifies. The loss is of propagation to the mainframe |
| Criticality of services affected | The integration hop, which is on the spine - but it kept operating for 31 550 of 31 552 crossings |
| Duration and downtime | No downtime. Eight minutes from detection to the fault being reversed, 09:05:39Z to 09:13:34Z. **Onset cannot be established from the estate at all**, only from the injector - which is the criterion this estate evidences worst |
| Geographical spread | Out of scope - one synthetic estate |
| Economic impact | Out of scope - no real money moves here |
| Reputational impact | Out of scope for a source repository |

**Not major.** No threshold in the RTS is approached. It is recorded and reported here anyway,
because the reason it is not major is the *size* of the exercise and not the *shape* of the defect:
the same mechanism at a bank's volume, unnoticed for a week, is a different classification entirely.
