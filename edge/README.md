# edge - the newest tier

**Stratum 4** | **Vintage ~2025** | **Go, Python 3.12, TypeScript + React** | **Built by WP-12, WP-13, WP-14**

Everything customer-facing and everything newest, written in whatever each team chose - which is why three languages appear in one tier. That is not untidiness; it is what happens when autonomous teams pick their own tools.

## Contents

| Directory | Stack | Holds |
|---|---|---|
| `api-gateway/` | Go | Authentication, rate limiting, correlation id, routing |
| `web-banking/` | TypeScript + React | The customer application |
| `fraud-scoring/` | Python 3.12 | Asynchronous risk scoring off Kafka |

