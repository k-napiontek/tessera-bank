# Runbook - the outbox is backing up

**Applies to:** `services/ledger-api`
**Signal:** `ledger.outbox.lag` rising, or `SELECT count(*) FROM outbox_record WHERE dispatched_at IS NULL`
growing over successive checks.

## What this means

Money is still moving. The outbox backing up does **not** mean transfers are failing: the ledger
commits the postings and the event in one transaction and returns to the customer without waiting for
Kafka (ADR 0004). What has stopped is the estate hearing about it.

What is degraded, in order of how quickly it hurts:

1. **`edge/fraud-scoring` is not scoring.** Transfers post unscored. This is a control gap while it
   lasts, not a data problem.
2. **`integration/esb-adapter` is not carrying movements to the older strata.** If the backlog is
   still present at the end-of-day cut-off, the mainframe master will not have those movements and
   the next morning's `batch/recon` will report breaks. The breaks are real and will clear once the
   backlog drains, but they will be worked by an operator who does not know that.

Nothing is lost. Every pending row is still there and the relay never gives up on one.

## Triage, in order

**1. Is the relay running at all?**

```sql
SELECT count(*) FILTER (WHERE dispatched_at IS NULL) AS pending,
       max(attempts)                                 AS worst_attempts,
       min(created_at) FILTER (WHERE dispatched_at IS NULL) AS oldest
  FROM outbox_record;
```

`worst_attempts = 0` on old rows means nothing has tried them: the relay is not running. Check that
`tessera.outbox.relay-enabled` is not `false` on every instance, and that the instances are up.

**2. Is the broker refusing?**

```sql
SELECT id, message_key, attempts, last_error
  FROM outbox_record
 WHERE dispatched_at IS NULL
 ORDER BY id
 LIMIT 5;
```

`last_error` on the oldest row is the broker's own message. A batch stops at its first failure, on
purpose - events for one transfer must keep their order - so **one stuck row blocks everything behind
it**. That is the usual shape of this incident: the number pending is large, and exactly one row is
the reason.

Common causes: the topic does not exist and auto-creation is off; the credentials expired; the
partition's replicas are below `min.insync.replicas` and `acks: all` cannot be satisfied.

**3. Is it simply volume?**

`attempts` incrementing and `dispatched_at` filling in, with pending still growing, means the relay
is working and losing. Raise `LEDGER_OUTBOX_BATCH`, lower `LEDGER_OUTBOX_INTERVAL_MS`, or add
instances - the relay uses `FOR UPDATE SKIP LOCKED`, so instances do not contend.

## What not to do

- **Do not delete pending rows.** Every one is a transfer the estate has not been told about. A
  deleted row is a break that reconciliation will find and nobody will be able to explain.
- **Do not mark rows dispatched by hand** to clear the alert. Same outcome, harder to spot afterwards.
- **Do not reorder rows or skip the stuck one.** The batch stops at the first failure deliberately;
  publishing past it can deliver a reversal before the transfer it reverses.
- **Do not restart the service expecting duplicates to be a problem.** Republication is normal and
  every consumer de-duplicates on `transferRef` - the AsyncAPI document requires it.

## After it drains

If the backlog spanned an end-of-day cut-off, tell whoever works the recon breaks the next morning:
the breaks in that window are this incident, not a discrepancy in the ledger.

## Related

- [ADR 0004](../governance/adr/0004-transactional-outbox.md) - why the outbox exists at all
- [`contracts/asyncapi/ledger-events.yaml`](../../contracts/asyncapi/ledger-events.yaml) - the
  at-least-once guarantee consumers rely on
