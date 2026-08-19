# WP-21 - workload-driver

| | |
|---|---|
| **Ticket** | TB-1021 |
| **Branch** | `feat/TB-1021-workload-driver` |
| **Stratum** | 4 - Go, ~2025 |
| **Depends on** | WP-20, WP-09, WP-12 |
| **Status** | `Not started` |

## Objective

Execute a WP-20 schedule against the running estate, so that the bank is busy and its metrics have
something in them.

The driver behaves like a real client rather than like a load tool, and that distinction decides
almost every design question in the package. A load tool sends requests; a customer application
holds an idempotency key across a retry, treats a lost response as an unknown outcome rather than a
failure, and backs off when it is told to. WP-14 learned all three the hard way, live, and a driver
that ignores them generates traffic no client in this estate would ever produce - which makes every
number it collects a measurement of the wrong system.

## In scope

- A Go module command that reads a workload model, plans a run with WP-20's engine, and executes it
  against `edge/api-gateway`.
- Token minting: an RS256 token per synthetic subject against the keypair the gateway is pointed at.
  `edge/web-banking/scripts/dev-token.mjs` mints one at a time; a population needs thousands.
- Population seeding: opening and funding the accounts the run will use, before the run starts.
- Outcome accounting that distinguishes *posted*, *replayed*, *rejected*, *refused* and *unknown*.
- Latency recorded against the **intended** send time from the schedule, not the actual one.
- Its own Prometheus metrics on a second port, named `tessera_workload_*`.
- A committed script that boots the estate for a run - PostgreSQL, the ledger, the gateway - which
  is the fixture F-56 asks for.

## Out of scope

- Bulk-loading a year of history. That is WP-22, and it goes in by `COPY` rather than over HTTP.
- SLOs, baselines and run reports - WP-23.
- Failure injection - WP-24.
- The older strata - WP-25.
- Any change to the gateway, the ledger or their configuration. If a run needs one, that is a
  finding about the estate and it is logged, not fixed here.

## Constraints

- **One idempotency key per attempt, reused by every retry.** The key is minted when the request
  stops changing and never regenerated on retry - WP-14's rule, and the ledger requires the header on
  all five money-moving operations. A driver that mints a fresh key per HTTP call is a driver that
  double-spends under packet loss, and it would post twice while reporting success.
- **A 429 is `refused`, not `failed`, and is never retried immediately.** The gateway's limit is
  per subject and per route class. Retrying into it converts a working control into a stampede and
  measures the retry loop rather than the bank.
- **A 5xx is an unknown outcome, not a failure.** On a money-moving request the line between
  "rejected" and "unknown" is 4xx against 5xx, which WP-14 found live when a stopped gateway produced
  a bare 500 for a transfer that may well have posted. The driver's accounting must keep that column
  separate or its own totals will not reconcile against the ledger's.
- **The driver never invents an account reference.** Every reference comes from the WP-20 population,
  so a run is reproducible and the ledger is not left holding rows nothing can explain.
- **This is a fixture, not a component of the bank.** It sits in the category
  `walkthrough.sh` and `dev-token.mjs` already occupy, and its README says so on the first line -
  otherwise the estate map acquires a component no bank has.
- No personal data. Subjects are pseudonymous references, and the access log carries no token.

## Tasks

To be detailed before execution.

## Definition of Done

- [ ] A planned run executes at the intended rate, with the realised send times matching the schedule
      within a stated tolerance.
- [ ] Every outcome class is accounted for, and the driver's own totals reconcile against
      `ledger_transfers_total` for the same window.
- [ ] A retry of a lost request reuses its key, proven by the ledger replaying rather than posting
      twice.
- [ ] Rate-limit refusals are counted separately and do not inflate the failure count.
- [ ] A run is reproducible: the same seed and model produce the same sequence of requests.
- [ ] Nothing in the estate was changed to make the run work.

## Verification

Boot the estate with the committed script, run a compressed day, and compare the driver's outcome
totals against the ledger's own metrics for the same window. Then kill the gateway mid-run and
confirm the unknown-outcome column moves rather than the failure column.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-003 Offered load is independent of the system's response | the open-model executor and intended-time latency |
