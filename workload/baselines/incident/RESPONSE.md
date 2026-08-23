
## The envelope

**09:12:36Z - named from the evidence, then checked.** The two transfers that exist in no file
anywhere are `TB202608230000016816` and `TB202608230000018445`, and both touch **`TB000000000003R2`** -
once as the debit, once as the credit. Stratum 1 holds that account with `opened_date = 2026-03-03`
while stratum 3 holds it as opened on 2026-08-23.

`ENVELOPE.json` was opened at **09:12:36Z**, after the account had been named:

```json
"accountRef": "TB000000000003R2",  "transferRef": "TB202603020000008403",
"originalOpenedDate": "2026-03-01", "injectedOpenedDate": "2026-03-03",
"positionInWindow": 803, "transfersInWindow": 8022, "seed": 4711
```

The account matches. **The detection path held: the report and the back office were enough.**

**Two of the envelope's own claims are wrong**, and finding that out is worth more than the match:

- *"blocks the partition for ever"* - it does not. Consumer lag was zero throughout.
- *"the next redelivery then succeeds and the partition drains"* - there is no redelivery. The
  messages were consumed, retried ten times, discarded, and their offsets committed.

The injector was written from **F-106**, which says this class of failure is retried for ever and
blocks the partition. The estate does something worse than F-106 predicts.

## Containment and resolution

**Containment: nothing to contain.** The driver had stopped, the cut-off had been taken, and no
further movement was being lost. The procedure's cheapest containment action - stop the driver - was
already true, and its next one, hold the overnight cycle, would have protected nothing because the
cycle was not the thing going wrong. **The containment section had no work in it, and that is a
finding rather than a success**: an incident whose damage is already complete before anybody can see
it cannot be contained, and this procedure has no category for that.

**09:13:33Z - resolution, as an `emergency` change.** `opened_date` restored to 2026-03-01 by
`incident-exercise.sh --recover`.

**09:13:34Z - the reversal, and what it proved.** The first run of the reversal waited for a backlog
to drain:

```
  held before    0 messages behind the consumer, 0 records in the file
  lag            0 -> 0
  movement file  0 -> 0 records
16001 compared, 14893 matched, 1108 broken
FAIL  the reconciliation still disagrees - this is not a recovery
```

`held before 0` is the whole incident in one line. **There was nothing to drain, so there was nothing
to recover.** The fault reverses; the money it lost does not come back, because a message the
consumer has already acknowledged is not replayable by anything in this estate.

## Attempt two, on a corrected harness

The response above found two defects in the fixture rather than in the bank, and both had to be
fixed before the capture could be evidence of anything:

1. **The injector rotated the movement file to separate seeding's records from the day's**, one
   second after the day began - and the adapter runs a minute behind the ledger, so 449 of the day's
   own transfers went out with it. The exercise reported 451 transfers lost when the fault had cost
   **two**. Fixed by splitting at the cut-off on `MOV-VALUE-DATE`, which has no race in it.
2. **The reversal asserted zero drift**, which this estate cannot reach while F-104 and F-107 are
   open. Fixed by driving one more business date to prove the account can post again, and reading the
   reconciliation against day D's own floor.

The fault was then injected again, under the same seed, against the corrected harness. Detection was
no longer blind - the mechanism was known by then - so **attempt one is the exercise of record for
everything about detection**, and attempt two is what the numbers below are measured from. Its
reports are in [`first-attempt/`](first-attempt/).

**09:27:40Z - the same fault, legible.**

| | 20260302 (D) | 20260303 (D+1) |
|---|---|---|
| VALUE_DRIFT | 224 | 271 |
| TIMING | 3 | 0 |
| Absolute drift | 1 143 901.62 | 1 458 862.42 |
| Cut-off | 8 134 transfers | 7 418 transfers |

**Day D carries no trace of the fault at all**, exactly as [WP-18a](../../../docs/plan/wp/WP-18-incident-exercise.md)
predicted: the transfers it blocked are same-day and not in the file, so they are in neither half of
what the reconciliation expects, and the control is correctly silent while the bank is short.

**09:28:04Z - what the fault actually cost.** Two transfers, `TB202608230000016816` and
`TB202608230000018445`, reached the ledger and no movement file. Nothing else was swallowed - the
split held.

**09:28:23Z - and what the report showed instead.** 47 accounts entered drift on D+1. **Three of them
are the incident.** The other 44 hold a posting the ledger dated by the machine clock - F-104 - which
the ESB wrote into the movement file anyway - F-107 - so the master applied it and the reconciliation
excluded it.

**Three accounts of real loss inside forty-seven accounts of report, on a standing floor of 224.**
Nothing in the report, the screen or the runbook separates them. The responder did it with a SQL
query against the ledger's `value_date`, which is not a step in any procedure this repository has.

## Verification of recovery

**09:28:37Z - the fault removed, and the removal proved by driving another day.**
`incident-exercise.sh --recover` restored `opened_date` to 2026-03-01 and then drove **2026-03-04**
against the same estate, because a row read back says what was written and only a movement that
crosses says the estate accepts one again.

```
  behind the consumer, before: 0 messages
  reversed at    09:28:37Z
  posted         7589     7589   ok
  movement records for TB000000000003R2 since the reversal: 1
OK    the account the fault refused is posting to the mainframe again
```

**The fault is reversible and its removal is verified.** `behind the consumer, before: 0` is also the
second confirmation that nothing was ever held.

**09:31Z - and the money did not come back.**

```
  the floor, day D            224 accounts in drift with no fault in the estate
  the break, day D+1          271
  after the reversal, day D+2 476
  cleared by the reversal     0
```

Zero cleared. The two transfers were acknowledged and discarded; nothing in this estate replays a
message the consumer has committed an offset past. **The fault reverses. Its cost does not.**

## What the three mornings say about the control itself

| Business date | VALUE_DRIFT | new that morning | TIMING | Absolute drift |
|---|---|---|---|---|
| 20260302 (D) | 224 | - | 3 | 1 143 901.62 |
| 20260303 (D+1) | 271 | 47, of which **3** are the incident | 0 | 1 458 862.42 |
| 20260304 (D+2) | 476 | 205, of which **0** are the incident | 0 | 2 057 324.14 |

**The false-positive population is not a floor, it compounds.** 224, then 271, then 476 over three
consecutive business dates, and none of the growth after D+1 has anything to do with the incident.
Every hold capture and every reversal the ledger dates by the machine clock joins it permanently -
F-104 keeps the ledger's side of the comparison from ever seeing them, F-107 puts them in the
movement file, and the master applies them for good.

Three points do not establish a rate. They establish a direction, and the direction is enough:
**by the reconciliation-break runbook's own escalation thresholds - more than ten accounts broken,
absolute drift over 10 000.00 - every morning in this estate is already an incident.** A control that
declares an incident every day is a control that will be turned off, which is the exact failure
[ADR 0015](../../../docs/governance/adr/0015-the-cut-off-is-the-movement-file.md) exists to prevent.
