# WP-12 - api-gateway

| | |
|---|---|
| **Ticket** | TB-1012 |
| **Branch** | `feat/TB-1012-api-gateway` |
| **Stratum** | 4 - Go, ~2025 |
| **Depends on** | WP-08 |
| **Status** | `Done` |

## Objective

Provide the edge: the single entry point where authentication, authorisation, rate limiting and
request correlation happen, so that no downstream service has to implement them. Written in Go, which
is what the industry reaches for at the edge and which adds a fourth build toolchain to the estate.

## In scope

- Reverse proxy routing to `ledger-core` per `contracts/openapi/`.
- Authentication and coarse authorisation at the edge.
- Rate limiting, per client and per endpoint.
- Correlation id generation and propagation to downstream services.
- Structured JSON logging and Prometheus metrics.
- Request and response size limits, and timeouts on every downstream call.

## Out of scope

- Business logic of any kind. If the gateway needs to understand a transfer, the design is wrong.
- TLS termination, which belongs to the platform repositories.
- Identity provider implementation - the gateway validates tokens, it does not issue them.

## Constraints

- Go standard library first. A dependency needs a justification recorded in the PR.
- The gateway must be stateless, so the platform repositories can scale it horizontally without a
  session store.
- Every downstream call carries a timeout and a bounded retry. An edge component without timeouts
  turns one slow service into a total outage.
- The correlation id is generated here if absent and propagated unchanged if present.

## Tasks

Detailed 2026-08-19. Three decisions were taken with the repository owner before the first line was
written, because each of them changes the code rather than the wording around it:

- **Two dependencies, both justified.** `github.com/golang-jwt/jwt/v5` and
  `github.com/prometheus/client_golang`. The constraint below says standard library first and a
  justification in the pull request; hand-rolled signature verification is where `alg: none` and
  algorithm confusion hide, and a hand-written exposition format is a second thing to get wrong for
  no gain. Everything else - routing, proxying, rate limiting, logging, configuration - is standard
  library.
- **The gateway validates the caller's token and forwards it unchanged.** It mints nothing, as the
  Out of scope section requires. The `securitySchemes` description in
  `contracts/openapi/ledger-core.yaml` says the token is *issued* here, which contradicts that, so
  the contract is corrected first - task 2 - and the implementation follows it.
- **The rate limiter holds state in memory, per instance.** A shared store is a component this
  repository is not allowed to deploy (ADR 0001), so the honest position is that *n* instances
  permit *n* times the configured rate, stated in the README and in an ADR rather than left for a
  reader to discover in production.

1. Detail this task list, record the three decisions above, and set the package `In progress` in
   `STATUS.md`.
2. **The contract first.** Correct the `securitySchemes` description in
   `contracts/openapi/ledger-core.yaml`: the gateway authenticates the customer and forwards the
   token it validated; it issues nothing. `bash contracts/validate.sh` stays green.
3. **Module skeleton, configuration, lifecycle.** `edge/api-gateway/go.mod`, configuration read from
   the environment and validated at boot - listen address, ledger URL, timeouts, body limits, JWT
   issuer, audience and keys, rate limits - plus `/healthz`, `/readyz` and graceful shutdown on
   SIGTERM. A missing or invalid setting fails the boot rather than defaulting silently, and
   readiness turns red when the ledger is unreachable.
4. **Correlation id and structured logging.** `X-Correlation-Id` is accepted when it is a UUID,
   generated when it is absent or malformed, echoed on the response and forwarded downstream, and
   present on every `log/slog` JSON line. Deliberately the same semantics as the ledger's
   `CorrelationIdFilter`, so the two tiers cannot disagree about the id for one request. A log
   hygiene test asserts that no `Authorization` value and no token substring reaches a log line.
5. **Authentication.** Bearer JWT validated on signature, pinned algorithm, `exp`, `nbf`, `iss` and
   `aud`. A rejection is an RFC 9457 `application/problem+json` 401, matching the estate's error
   style. Tests cover `alg: none`, algorithm confusion, expiry, wrong issuer, wrong audience,
   malformed and absent.
6. **Coarse authorisation.** A route table maps method and path pattern to a required scope; a token
   without it gets 403. The table knows routes, not transfers - no business logic crosses the edge.
7. **Rate limiting.** Token bucket keyed by subject and route class, 429 with `Retry-After`, per
   instance as decided above.
8. **Reverse proxy, timeouts, bounded retry, size limits.** Routes to `ledger-core` per
   `contracts/openapi/ledger-core.yaml`. Per-call deadline, hop-by-hop headers stripped,
   `Authorization`, `Idempotency-Key` and `X-Correlation-Id` forwarded, `http.MaxBytesReader` on the
   request and a cap on the response. A retry happens **only** where a replay is provably safe - a
   safe method, or a request carrying an `Idempotency-Key` - and only on a connection-level failure
   with no response received. A slow downstream produces 504, not a hung connection.
9. **Prometheus metrics.** Requests by route and status, latency histogram, rate-limit rejections,
   downstream timeouts and failures.
10. **Toolchain and documentation.** `make build-edge`, `test-edge` and `lint-edge`
    (`gofmt -l`, `go vet`), a `go` availability check in the manner of `jdk17`,
    `edge/api-gateway/README.md`, the root `README.md`, the traceability matrix rows for
    REQ-EDG-001, 002 and 003, and the ADRs the decisions above require.
11. **Verification and landing.** `go test ./...` and the live run against a real `ledger-api` on
    PostgreSQL, with actual output in the pull request, then `STATUS.md`.

## Definition of Done

- [ ] Requests route correctly and unauthenticated requests are rejected before reaching the ledger.
- [ ] Rate limiting triggers and returns the correct status with a `Retry-After`.
- [ ] A correlation id is present in every downstream request and every log line.
- [ ] A slow downstream service produces a timeout, not a hung connection.

## Verification

`go test ./...`, plus an integration run against a live `ledger-core`: authenticated request
succeeds, unauthenticated is rejected, the rate limit trips at the configured threshold, and the
correlation id appears in both gateway and ledger logs for the same request.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-EDG-001 Authentication happens once, at the edge | gateway auth |
| REQ-EDG-002 Every request is traceable end to end | correlation id propagation |
| REQ-EDG-003 A slow dependency cannot exhaust the edge | timeouts and limits |
