# edge - the newest tier

**Stratum 4** | **Vintage ~2025** | **Go, Python 3.12, TypeScript + React** | **Built by WP-12, WP-13, WP-14**

Everything customer-facing and everything newest, written in whatever each team chose - which is why three languages appear in one tier. That is not untidiness; it is what happens when autonomous teams pick their own tools.

## Contents

| Directory | Stack | Holds | State |
|---|---|---|---|
| `api-gateway/` | Go | Authentication, rate limiting, correlation id, routing | **Built** - WP-12 |
| `web-banking/` | TypeScript + React | The customer application | Not started - WP-14 |
| `fraud-scoring/` | Python 3.12 | Asynchronous risk scoring off Kafka | **Built** - WP-13 |

## Building

```bash
make build-edge   # go build
make test-edge    # go test -race
make lint-edge    # gofmt and go vet
```

Two components, two toolchains, one target each way: `make test-gateway` runs the Go suite under the
race detector and `make test-fraud` runs pytest, including one test against a real Kafka. Three
languages in one tier is what happens when autonomous teams pick their own tools, and the build
reflects that rather than hiding it. The React application brings a third when it arrives.

The Python component is pinned to 3.12 and managed by `uv`, which fetches the interpreter itself -
so the tier builds on a machine that has never installed Python.

