# Runbook - the edge is refusing requests

**Applies to:** `edge/api-gateway`
**Signal:** `tessera_gateway_refusals_total` rising, or `tessera_gateway_upstream_failures_total`
rising, or customers reporting failures that never appear in the ledger's logs.

## First, the one distinction that matters

The gateway produces two families of failure and they have nothing to do with each other.

| Metric | Meaning |
|---|---|
| `tessera_gateway_refusals_total` | **The edge decided.** The request never reached the ledger. |
| `tessera_gateway_upstream_failures_total` | **The ledger did not answer usably.** The request may or may not have been applied. |

A 4xx that the ledger itself decided - insufficient funds, an idempotency conflict - appears in
neither: it is relayed untouched and counted only in `tessera_gateway_requests_total`. So "is the
gateway rejecting traffic" and "is the ledger rejecting traffic" are separate questions with
separate answers, and the first metric to read tells you which one you are asking.

## By reason

```promql
sum by (reason) (rate(tessera_gateway_refusals_total[5m]))
```

**`unauthenticated`** - a 401. Almost always one of three things: tokens have expired and the client
is not refreshing; the issuer has rotated its signing key and `TB_GATEWAY_JWT_KEYS` was not updated;
or the deployment is pointed at the wrong issuer or audience. The gateway logs the reason at `warn`
with the correlation id and deliberately does not serve it - `grep '"msg":"authentication refused"'`
on one instance names the failing check.

**During a key rotation the fix is to add the new key to the file, not to replace the old one.** The
gateway accepts every public key the file holds, so both verify while tokens signed by either are
still in flight. Replacing outright refuses every token minted before the rotation.

**`forbidden`** - a 403. The token authenticated and lacks the scope the route requires. If this
appears in volume after a client release, that client is asking for an operation its tokens were
never granted; the scope table is in `internal/routing`, and it mirrors the OpenAPI document.

**`rate_limited`** - a 429. Expected in small numbers. If it is broad, remember that **the limit is
per instance**: the effective rate is `TB_GATEWAY_RATE_PER_SECOND` times the number of gateways, so
scaling *in* tightens the limit on every caller at once ([ADR 0006](../governance/adr/0006-edge-rate-limit-is-per-instance.md)).
A fleet halved is a limit halved, and nothing about the configuration changed.

**`no_route`** - a 404 or 405. A client is calling something the OpenAPI document does not declare.
This is also what an unauthenticated probe of the ledger's actuator endpoints looks like from here.

**`payload_too_large`** - a 413, against `TB_GATEWAY_MAX_REQUEST_BYTES`.

## When the ledger is the problem

```promql
sum by (kind) (rate(tessera_gateway_upstream_failures_total[5m]))
```

**`timeout`** - a 504. The ledger has the request and did not answer within
`TB_GATEWAY_DOWNSTREAM_TIMEOUT`. **The request may well have been applied**, which is why the caller
is told 504 and not 502, and it is why the gateway does not retry a timeout: a second copy multiplies
the load on a ledger that is already struggling.

This page used to send you to `ledger.posting.latency` and the Hikari pool, and **WP-24c measured
both of them staying flat through the incident they are supposed to describe.** With the ledger's
connection pool drained from the inside - writers blocked on a lock, each holding a pooled connection
- `SLO-LEDGER-POSTING-LATENCY` came out at 0.99398 against a 0.99 target, met, and
`hikaricp_connections_pending` read 0 both before and after. What moved was the gateway's own
latency, missed at 0.94141 and 5.86x its error budget. The reason is that the ledger times the
posting it performed, not the wait for a connection to perform it in, and three quarters of the day's
requests are reads that queue behind the same exhausted pool without ever posting anything. **So
start from the edge's own latency and work inwards.** The ledger's two signals are still worth
reading - a figure that has moved is real - but neither moving is not evidence that the ledger is
fine. See [`../architecture/estate-under-load.md`](../architecture/estate-under-load.md).

**`unusable`** - a 502. No connection: nothing is listening on the ledger's port. The gateway retries
this one, because a connection error means nothing downstream happened - but only for a safe method
or a request carrying an `Idempotency-Key`.

**This page used to say a 502 meant "the ledger is down", and the converse does not hold.** WP-24c
suspended the ledger process for the window `SCN-LEDGER-OUTAGE` declares and produced **no 502s at
all**: gateway availability stayed at 1.00000 over 34 322 requests. A suspended or wedged process
still holds its listening socket and the kernel goes on accepting into its backlog, so every request
was answered late rather than refused. **An absence of `unusable` is not evidence the ledger is
running.** A ledger that is gone in every sense that matters to a customer shows up here as latency,
and its own success rate looks perfect throughout, because a ratio computed from a component's
counters cannot fall while the component is not counting.

Check readiness on the administrative port: `curl -s localhost:9090/readyz`. It dials the ledger's
port and reports `DOWN` when nothing is listening. It says nothing about whether the ledger is
*healthy* - that is deliberate, and the ledger's own `/actuator/health` is the place to ask.

## Tracing one customer's failure

Ask for the `correlationId` in the error document they were shown. It is on every gateway log line
for that request, on the ledger's, in the audit row and on the outbox event - one identifier across
four tiers.

```bash
grep '"correlation_id":"<id>"' gateway.log     # the edge's account of it
grep '"correlationId":"<id>"' ledger.log       # what the ledger did, if it got there
```

No match in the gateway's log at all means the request never arrived: look at whatever sits in front
of it, not at this component.

## What this runbook cannot tell you

The gateway logs no client address and no token, by design - an address identifies a person under
GDPR and a token in a log store is a replayable credential. So "which customer is causing this" is
answerable by token subject and correlation id only. If an incident genuinely needs source
attribution, that is a question for the layer that terminates TLS, and a decision to record it is a
data protection decision rather than an operational one.
