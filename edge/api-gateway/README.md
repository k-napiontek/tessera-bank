# api-gateway

**Stratum 4** | **Go** | **Built by WP-12**

The single entry point: authentication, coarse authorisation, rate limiting, correlation id generation and propagation, so no downstream service has to implement them.

**No business logic.** If the gateway needs to understand what a transfer is, the design is wrong. Every downstream call carries a timeout - an edge component without timeouts turns one slow service into a total outage.

## What it does, in the order it does it

The chain is the design. Each step depends on what the one outside it established, and moving any of them changes what is enforced.

| Step | Refuses with | Because |
|---|---|---|
| Correlation id | - | A canonical UUID from the caller is honoured, anything else is replaced. Same rule as the ledger, so one request has one id across the estate. |
| Access log | - | One JSON line per request, carrying that id. No token, no client address, no query string, no body. |
| Metrics | - | Counts what the edge refused as well as what it forwarded. |
| Authentication | `401` | Bearer JWT: signature, pinned algorithm, `exp`, `nbf`, `iss`, `aud`. |
| Routing | `404`, `405` | The request must be one of the operations `contracts/openapi/ledger-core.yaml` declares. |
| Authorisation | `403` | The token's scopes must include the one that route requires. |
| Rate limiting | `429` | Token bucket per subject and route class, with `Retry-After`. |
| Proxy | `413`, `502`, `504` | Forwards to the ledger under a timeout and a bounded retry. |

Rate limiting sits below authentication because it keys on the subject: above it, one caller could exhaust the limit for everybody. Routing sits below authentication because an unauthenticated caller must not be able to probe which paths exist.

Every refusal is an RFC 9457 `application/problem+json` document in the estate's namespace, carrying the correlation id - so a client writes one error path for the whole bank.

## Two listeners

| Listener | Default | Serves |
|---|---|---|
| Customer | `:8080` | The contract's operations, and nothing else. |
| Administrative | `:9090` | `/healthz`, `/readyz`, `/metrics`. |

They are separate ports on purpose. The ledger serves its actuator endpoints beside its API, which is precisely what the gateway exists to keep off the internet; repeating that arrangement here would be a poor joke. The customer listener answers `404` to `/metrics` even with a valid token.

`/healthz` never consults the ledger - an orchestrator restarting a healthy gateway because its downstream is deploying turns a partial outage into a crash loop. `/readyz` dials the ledger's port, and says only what a dial proves: something is listening. Interpreting the ledger's own health document here would put a second opinion about the ledger's state at the edge, and two opinions disagree.

## Configuration

Read from the environment at boot. A setting that is present but unparseable fails the boot; it never falls back to a default. Every problem is reported at once, so a broken deployment is diagnosed in one restart rather than one variable at a time.

**Required - no default, because a default here is a security decision taken by whoever forgot to set it:**

| Variable | Meaning |
|---|---|
| `TB_GATEWAY_LEDGER_URL` | Base URL of `services/ledger-api`, including any path prefix such as `/v1`. |
| `TB_GATEWAY_JWT_ISSUER` | The only `iss` the gateway accepts. |
| `TB_GATEWAY_JWT_AUDIENCE` | The `aud` the gateway requires. |
| `TB_GATEWAY_JWT_KEYS` | PEM file of public keys. A private key in it fails the boot. |

**Optional:**

| Variable | Default | Meaning |
|---|---|---|
| `TB_GATEWAY_LISTEN` | `:8080` | Customer-facing address. |
| `TB_GATEWAY_ADMIN_LISTEN` | `:9090` | Health and metrics address. |
| `TB_GATEWAY_LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error`. |
| `TB_GATEWAY_DOWNSTREAM_TIMEOUT` | `5s` | Bounds one attempt at the ledger. |
| `TB_GATEWAY_DOWNSTREAM_ATTEMPTS` | `2` | Total attempts, capped at 3. |
| `TB_GATEWAY_SHUTDOWN_GRACE` | `15s` | How long in-flight requests have after SIGTERM. |
| `TB_GATEWAY_READ_HEADER_TIMEOUT` | `5s` | Bounds a client dawdling over its headers. |
| `TB_GATEWAY_MAX_REQUEST_BYTES` | `65536` | Largest body forwarded. |
| `TB_GATEWAY_MAX_RESPONSE_BYTES` | `1048576` | Largest body relayed back. |
| `TB_GATEWAY_RATE_PER_SECOND` | `10` | Sustained rate per subject and route class. |
| `TB_GATEWAY_RATE_BURST` | `20` | How far above that rate a caller may burst. |

## Two things this gateway does not pretend

**The rate limit is per instance.** The buckets live in this process, so *n* gateways permit *n* times the configured rate. A shared counter needs a store this repository is deliberately not allowed to deploy ([ADR 0001](../../docs/governance/adr/0001-source-only-repository.md)), and a limiter that claims to be global is worse than one that is honestly local: the first is relied upon, the second is understood. See [ADR 0006](../../docs/governance/adr/0006-edge-rate-limit-is-per-instance.md).

**It issues no token.** It validates the caller's and forwards it unchanged. Minting one would make the edge an identity provider, and an identity provider needs a signing key - a secret this component never holds. See [ADR 0007](../../docs/governance/adr/0007-gateway-validates-and-forwards.md).

## Retrying

A second attempt is made only when both hold:

- the request is replayable - a safe method, or one carrying an `Idempotency-Key`;
- the failure is one the ledger cannot have acted on.

A connection error can be raised *after* the ledger has read the request, so replaying a transfer that carries no idempotency key is how a customer is debited twice. A timeout is never retried: it means the ledger has the request and is struggling with it, and sending it again multiplies the load on a dependency at the moment it can least afford it.

A timeout answers `504` and a connection failure `502`, and the two say different things on purpose - after a timeout the request may well have been applied.

## Building and testing

```bash
make build-edge     # go build ./...
make test-edge      # go test -race ./...
make lint-edge      # gofmt -l and go vet
```

Or directly:

```bash
go -C edge/api-gateway test ./...
```

No Docker and no database: the tests drive a stand-in ledger from `httptest`, and the routing test reads `contracts/openapi/ledger-core.yaml` to prove the route table and the contract agree.

## Dependencies

Standard library first, per the work package. Two exceptions, both recorded here rather than assumed:

| Module | Why |
|---|---|
| `github.com/golang-jwt/jwt/v5` | Hand-rolled signature verification is where `alg: none` and algorithm confusion hide. This is not code to write for the practice of it. |
| `github.com/prometheus/client_golang` | The exposition format, its escaping and its collectors, from the implementation everything else in the ecosystem is tested against. |

Everything else - routing, proxying, rate limiting, logging, configuration, UUIDs - is standard library.

## Running it

```bash
TB_GATEWAY_LEDGER_URL=http://localhost:8080/v1 \
TB_GATEWAY_JWT_ISSUER=https://issuer.tesserabank.example \
TB_GATEWAY_JWT_AUDIENCE=tessera-bank-ledger \
TB_GATEWAY_JWT_KEYS=/path/to/jwt-keys.pem \
TB_GATEWAY_LISTEN=:8081 \
go -C edge/api-gateway run ./cmd/gateway
```

The ledger it fronts is started with `./gradlew :services:ledger-api:bootRun` against any PostgreSQL.
