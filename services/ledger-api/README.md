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
| `config/` | The bean graph, written out by hand because nothing below carries an annotation to scan. |

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
- **No metrics, no structured logging, no audit chain and no outbox.** All WP-09.
- **`requestedAt` and `postedAt` report the same instant.** They diverge only once there is a queue
  in front of the ledger, which is WP-09's work. Inventing a gap now would be fiction.
