# WP-12 - api-gateway

| | |
|---|---|
| **Ticket** | TB-1012 |
| **Branch** | `feat/TB-1012-api-gateway` |
| **Stratum** | 4 - Go, ~2025 |
| **Depends on** | WP-08 |
| **Status** | `Not started` |

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

To be detailed before execution.

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
