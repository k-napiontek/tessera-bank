# ledger-api

**Stratum 3** | **Java 17, Spring Boot 3.2** | **Built by WP-08**

The ledger's HTTP surface, and the first Spring Boot application in this repository. Reached through
`edge/api-gateway`, never directly by a customer.

Everything it exposes is defined by [`contracts/openapi/ledger-core.yaml`](../../contracts/openapi/ledger-core.yaml),
which is the source of truth. The contract changes first; a test in this module fails the build if
the implementation and the document drift apart.

## Why this is a separate module

The same argument that put the adapters in `ledger-persistence`, applied once more.

`ledger-core` carries no framework on its compile classpath, and `DomainPurityTest` scans every
source in it for `org.springframework`, `com.fasterxml.jackson` and `jakarta.*`.
`HexagonalBoundariesTest` bans Jackson and Jakarta from `..ledger.domain..` and `..ledger.port..`
outright. An annotated request or response type cannot live anywhere near the domain, and the only
way to make room for one would be to narrow a control that currently works.

So the wire types live here, and the translation between them and the domain lives here with them.
The dependency runs one way: this module knows `ledger-core` and `ledger-persistence`, and neither
of them knows this one.

## What is where

| Package | Holds |
|---|---|
| `web/` | Controllers. Thin: resolve a path variable, call one use case, map the answer. No decisions. |
| `dto/` | The wire types, and the mapping to and from the domain. |
| `problem/` | RFC 9457 Problem Details, and the one enum of stable `type` URIs. |
| `idempotency/` | The filter that makes a retry safe, and the request fingerprint it turns on. |
| `correlation/` | The filter that gives every request an id, and the MDC it lives in. |
| `audit/` | The audit context: who is asking, under which request. |
| `outbox/` | The Kafka end of the relay, and the timer that drives it. |
| `metrics/` | The business metrics, measured at the boundary rather than inside the use cases. |
| `config/` | The bean graph, written out by hand because nothing below carries an annotation to scan. |

## Correlation

Every request has an id, whether or not the caller supplied one. `CorrelationIdFilter` accepts
`X-Correlation-Id` when it is a UUID, generates one when it is absent or malformed, puts it in the
SLF4J MDC, and echoes it on the response. From the MDC it reaches the Problem document, the audit row
and the outbox event, so one customer request is one identifier across the estate.

Three details that are not incidental:

- **It is ordered first.** The idempotency filter can reject a request before any controller runs,
  and its Problem document must carry the same id as the response header. That is exactly the path a
  support engineer is looking at.
- **A non-UUID is replaced, not propagated.** What arrives here reaches log lines and a Problem
  document; honouring arbitrary text would let a caller choose this service's log contents, and would
  break the join with every tier that keys on a UUID.
- **The header is re-applied after `response.reset()`.** Two paths replace a half-written response -
  the idempotency conflict and the replay - and `reset()` clears headers along with the body. Without
  the re-application the id vanishes from precisely the two responses that most need it, silently.

## Idempotency

The defining behaviour of the package. A client whose connection dropped mid-transfer has no way to
know whether the money moved, so it retries with the same `Idempotency-Key` - and must get the
original answer rather than a second transfer.

`IdempotencyFilter` claims the key, does the work and stores the response **in one transaction**,
and every use case below joins it. That is what makes the guarantee real rather than probable: a
retry arriving while the first request is still in flight blocks on the claim until the first
commits, and then replays its answer. Claim and work in separate transactions and there is a window
in which both requests believe they are the first - and both post.

Three decisions worth knowing:

- **The fingerprint is over canonical JSON**, not the raw bytes. A client that retries by
  re-serialising can reorder fields or change whitespace without meaning anything by it, and
  digesting the bytes would refuse a legitimate retry with a `409`. Array order is preserved,
  because in JSON an array's order is meaning rather than presentation.
- **Only a successful response is recorded.** A rejected transfer must stay retryable: the client
  may fix the amount and try again under the same key, and a stored `422` would answer that forever.
  A non-2xx rolls the transaction back, releasing the claim along with anything the request wrote.
- **A replay answers `200`, whatever the original returned.** `201 Created` is a statement that
  something was created now, and repeating it for a request that created nothing this time would be
  false. The body is the original's, byte for byte - never re-rendered, because re-rendering picks
  up whatever has changed since.

## Errors

Every failure is an RFC 9457 document served as `application/problem+json`. `type` is a stable URI
from `ProblemType` and is the part a client may branch on; `title` and `detail` are for humans and
may be reworded without a contract change.

`LedgerProblemHandler` is ordered ahead of Spring's own problem-details advice, which would
otherwise answer with `type: about:blank` - the RFC's way of saying "nothing machine-readable here".
Its last handler is the important one: without a catch-all, an unrecognised exception reaches the
container and the caller learns the class name of whatever failed, and a fragment of the SQL with
it. That is asserted directly rather than assumed.

`IdempotencyFilter` writes its own conflict document through `ProblemWriter`, because a filter sits
outside Spring MVC and an exception thrown there never reaches `@RestControllerAdvice`.

## The contract test

`OpenApiContractTest` walks every operation the document declares and validates both sides of every
exchange against the schema declared for it, then fails if any `operationId` was never reached. A
contract test that only checks the operations somebody remembered to call has a hole in it exactly
where the drift will be.

It validates with a **JSON Schema 2020-12** implementation rather than an OpenAPI-specific one.
OpenAPI 3.1 schemas *are* JSON Schema 2020-12, and this document uses it - `type: [string, 'null']`
cannot be expressed in 3.0 - while the OpenAPI validators in the Java ecosystem still read 3.1 by
converting it down to 3.0 and losing those constructs. Validating against a downgraded copy of the
contract would be a test that agrees with itself.

Both directions were demonstrated to fail before being relied on: adding a field the document does
not declare, and rendering `amountMinor` through `Money.toPlainString()`. The second produced
`"0.00"` and `string found, integer expected`, which is the defect this repository exists to prevent
appearing on a wire.

## Observability

Four signals, each answering a different question. The operator's view is
[`docs/runbooks/ledger-observability.md`](../../docs/runbooks/ledger-observability.md); what follows
is why it is shaped this way.

**Probes.** `/actuator/health/liveness` and `/readiness` must answer differently or neither is worth
having: a failing liveness probe restarts the process, a failing readiness probe removes it from the
load balancer. The database is in **readiness** - Spring's default group is `readinessState` alone,
which reports ready as soon as the context has started, so a ledger that cannot reach PostgreSQL
would be declared ready and sent traffic it can only reject. It stays out of **liveness** because
restarting a pod does not fix a database outage; it turns one outage into a crash-loop across every
instance at once.

**Metrics, measured at the boundary.** `MoneyMovementMetricsFilter` sits in the filter chain rather
than inside the use cases, for two reasons: the use cases live in `ledger-core`, which carries no
framework on its compile classpath and must not start now, and what an operator needs is what the
customer experienced. A replay is its own outcome, not a success - counting a `200` from the
idempotency store as a posting would inflate throughput with work nobody did and hide the signal that
matters, which is clients timing out. Rejections are counted but not timed: mixing a validation
failure that returns in a millisecond with a transfer that took two locks produces a percentile
describing neither.

**Metrics about the database, added by WP-23.** Until then this service reported how many transfers
posted and nothing about the PostgreSQL that posted them, while every transfer takes two row locks
and one global advisory lock. `DatabaseSignals` publishes per-table size, dead tuples and vacuum
activity from a **fixed** table list - reading `pg_stat_user_tables` as it comes would grow a time
series every migration, and unbounded label cardinality is the standard way to take a monitoring
system down. Pool utilisation and acquire wait are deliberately **not** re-derived here: Boot's
Hikari binder already publishes them, and a second copy of somebody else's instrument would be one
that could disagree with the first.

The two lock waits are separate meters and must never be summed. `ledger_lock_chain_seconds` is the
audit chain's service-wide advisory lock; `ledger_lock_account_seconds` is the row locks a
transaction takes on its own accounts. Averaged together they move for two unrelated reasons, which
is what would make **F-27** unanswerable - and F-27 is exactly the question WP-23 measured with them.
The figures are in [`docs/architecture/estate-under-load.md`](../../docs/architecture/estate-under-load.md);
what each signal is expected to be is in
[`docs/ways-of-working/slo-catalogue.md`](../../docs/ways-of-working/slo-catalogue.md).

One trap worth naming: a table that has never been autovacuumed reports **`NaN`, not zero**. Zero
reads as "vacuumed a moment ago", the exact opposite of the truth, and the first version of this got
it wrong in a way only a test caught - `ResultSet.wasNull()` reports on the column read immediately
before it, and five other columns were being read in between.

**Logs are JSON, in one format everywhere.** A pattern locally and JSON in production means the
format that matters is the one nobody reads during development. Boot gains structured logging
natively in 3.4; stratum 3 is pinned to 3.2, so the encoder is an explicit dependency. Everything in
the MDC is on every line, which is what carries the correlation id without any call site remembering
it - and is why `LogHygieneTest` holds the MDC to an allowlist.

**Tracing produces spans and propagates W3C `traceparent`, and ships nothing.** There is no exporter
and no collector address here; that is deployment configuration (ADR 0001). The correlation id and
the trace id coexist deliberately: a trace reaches as far as W3C propagation does, while the
correlation id also survives the SOAP call and the fixed-width record the ESB writes for a mainframe
where no tracing exists.

## Tests

| Test | What it holds |
|---|---|
| `ContextLoadsTest` | The application starts against real PostgreSQL and every migration applied. Fails first, and readably, when Docker is not running. |
| `LedgerProblemHandlerTest` | Every failure maps to its status and `type`, and no document carries a stack trace, a SQL fragment or a class name. |
| `RequestFingerprintTest` | What counts as "the same request": field order and whitespace do not, values, path, method and array order do. |
| `AccountEndpointsTest` | Opening, reading, and the money-is-never-a-decimal assertion. |
| `TransferEndpointsTest` | Transfers, reversals, and the replay that moves money once. |
| `HoldEndpointsTest` | Placing, capturing and releasing, and what each does to the two balances. |
| `OpenApiContractTest` | The implementation against the document, with a coverage assertion. |
| `CorrelationIdTest` | An id on every response, including the two that reset it. |
| `AuditTrailEndToEndTest` | The request's id reaches the audit row; a rejected transfer leaves none. |
| `KafkaOutboxContractTest` | A transfer relayed to a real broker, validated against the AsyncAPI document. |
| `HealthProbeTest` | With the database stopped, readiness is 503 and liveness is still 200. |
| `LogHygieneTest` | A marker a customer supplied never reaches a log line; the MDC is on an allowlist. |
| `BusinessMetricsTest` | Every outcome counted under its own tag, and all of it scrapeable. |
| `TracingTest` | A log line inside a span carries its trace id; the context leaves as `traceparent`. |

One container and one Spring context for the whole module, so the database is shared: every test
takes account references of its own from `LedgerApiTest.freshAccountReference()`. A test that
hard-codes one will pass alone and fail beside its neighbours.

```bash
make docker        # the tests need a running daemon
make test-services # all three modules
```

## Notes for the next change

- **The container is started in a static initialiser, not by `@Testcontainers`.** That extension
  stops the container when the declaring class finishes, while Spring caches its context - and the
  pool inside it - across every test class. The second class then holds connections to a container
  that no longer exists.
- **There is no authentication here**, and there should not be. `edge/api-gateway` (WP-12) owns it.
  The contract declares a bearer scheme because a contract that omits its security requirement is
  incomplete, not because this application checks one.
- **Spring Boot switches observability off inside `@SpringBootTest`.** Deliberately, so a test run
  cannot push to a real backend - but it leaves only a `SimpleMeterRegistry`, so `/actuator/prometheus`
  does not exist and there is no `Tracer` to assert on. `@AutoConfigureObservability` is what turns
  it back on, and without it a metrics test passes while verifying nothing.
- **`management.endpoint.prometheus.access` is a Boot 3.4 property.** This module is pinned to 3.2
  and the exposure list governs it instead.
- **The outbox relay is a scheduled bean, switchable by `tessera.outbox.relay-enabled`.** Every test
  context but `KafkaOutboxContractTest` turns it off: there is no broker for it to reach, and a
  scheduled task retrying against one adds a ten-second wait to every class.
- **`requestedAt` and `postedAt` report the same instant.** They diverge only once there is a queue
  in front of the ledger. There still is not one: the outbox queues the *announcement*, not the
  posting. Inventing a gap would be fiction.
