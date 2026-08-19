# edge - the newest tier

**Stratum 4** | **Vintage ~2025** | **Go, Python 3.12, TypeScript + React** | **Built by WP-12, WP-13, WP-14**

Everything customer-facing and everything newest, written in whatever each team chose - which is why three languages appear in one tier. That is not untidiness; it is what happens when autonomous teams pick their own tools.

## Contents

| Directory | Stack | Holds | State |
|---|---|---|---|
| `api-gateway/` | Go | Authentication, rate limiting, correlation id, routing | **Built** - WP-12 |
| `web-banking/` | TypeScript + React | The customer application | Not started - WP-14 |
| `fraud-scoring/` | Python 3.12 | Asynchronous risk scoring off Kafka | Not started - WP-13 |

## Building

```bash
make build-edge   # go build
make test-edge    # go test -race
make lint-edge    # gofmt and go vet
```

Only the Go component exists so far, so these targets are the api-gateway's. The Python and
TypeScript components bring their own toolchains when they arrive - three languages in one tier is
what happens when autonomous teams pick their own tools, and the build reflects that rather than
hiding it.

