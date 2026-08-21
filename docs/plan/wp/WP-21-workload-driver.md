# WP-21 - workload-driver

| | |
|---|---|
| **Ticket** | TB-1021 |
| **Branch** | `feat/TB-1021-workload-driver` |
| **Stratum** | 4 - Go, ~2025 |
| **Depends on** | WP-20, WP-09, WP-12 |
| **Status** | `Done` |

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

1. Set the package `In progress` in `STATUS.md` and branch from up-to-date `main`, per
   [`PROTOCOL.md`](../PROTOCOL.md).

2. **Teach the purity check the difference between the engine and the driver.**
   `workload/internal/purity/purity_test.go` asserts *transitively* that no package reaches `net`,
   `os` or a database driver, and its failure message already names this package: *"the engine sends
   nothing - WP-21 and WP-25 do"*. The six engine packages - `bankday`, `arrivals`, `population`,
   `model`, `money`, `manifest` - keep that guarantee unchanged, and the packages added here are
   listed as the ones permitted to reach the network. A planted `net/http` in `internal/bankday`
   must still fail, naming every package that reaches it. Relaxing the check to "anything may do
   anything" would retire a control by editing a test, which is the failure this control exists to
   catch.

3. **A token per subject, minted here.** `edge/api-gateway/internal/auth` verifies RS256 against a
   PEM public key set and takes the subject and the scopes from the token;
   `edge/web-banking/scripts/dev-token.mjs` mints one at a time. The reason a population needs
   thousands is the **rate limiter**: its bucket key is `subject + route class`
   (`ratelimit.Middleware`), so a run that drives a whole population through one token measures the
   limiter and reports it as the bank being slow. Minting is standard-library work - `crypto/rsa`,
   `crypto/rand`, `encoding/json`, `encoding/base64` - so `workload/go.mod` stays dependency-free,
   and the key pair is written where the gateway is already pointed. **Nothing in the gateway
   changes**, which its Out of scope forbids. Scopes come from `routing.Routes()`, so a token cannot
   be minted for an operation the estate does not route.

4. **A client that behaves like a customer application, not a load tool.** The outcome classifier is
   the heart of the package and it reads straight off `contracts/openapi/ledger-core.yaml`: **201**
   posted, **200** replayed, **409** and other 4xx rejected, **429** refused, **5xx and any
   transport failure** unknown. One `Idempotency-Key` per *attempt*, minted when the request stops
   changing and reused by every retry - 16 to 64 characters, which is what `TransferController` and
   `HoldController` bound it to. `Retry-After` is honoured and a 429 is never retried immediately.
   Tested against `httptest` handlers, one per class, including a bare 500 on a transfer - the case
   WP-14 hit live, and the one that decides whether the driver's totals can ever reconcile against
   the ledger's.

5. **Seed the population before the run.** Open and fund the accounts the schedule will use, drawn
   from `population.Population` so that no reference is invented and the ledger is left holding
   nothing it cannot explain. Seeding is itself idempotent - a second run against a seeded estate
   funds nothing twice - and it is a separate phase from the run, so that its latency never enters
   the measurement.

6. **Execute the schedule, open.** `arrivals.Process.Events(seed)` yields `Event{Seq, At, Minute}`
   lazily; the executor divides `At` by the compression factor and sends at that instant
   **regardless of what is still outstanding** - [ADR
   0016](../../governance/adr/0016-the-workload-model-is-open.md). The cost that ADR names is paid
   here rather than dodged: in-flight work is bounded by a pool that **records saturation
   explicitly**, never by blocking the scheduler, because a scheduler that waits for a free worker
   is a closed model wearing an open model's name. Latency is recorded from the intended send time.
   The test that pins this is a deliberately slowed stub: the offered rate must not fall, and the
   recorded latency must rise.

7. **Export the run as `tessera_workload_*`.** Prometheus text format on a second port, named after
   the gateway's own metrics (`tessera_gateway_requests_total`,
   `tessera_gateway_request_duration_seconds`, `tessera_gateway_refusals_total`): a counter per
   outcome class, a latency histogram measured from the intended send time, and a gauge for schedule
   lag - the signal that says the driver, and not the bank, is the thing that fell behind. A run
   whose lag is climbing is a run whose numbers describe the load generator.

8. **Boot the estate for a run.** `workload/scripts/estate-up.sh`: PostgreSQL, the ledger, the
   gateway and the key pair, in the shape `edge/web-banking/scripts/walkthrough.sh` already
   establishes. It lives beside the thing that needs it rather than under `edge/web-banking/`, where
   **F-56** guessed it would go - the run needs an estate and the web application does not - and
   `walkthrough.sh` is left alone rather than generalised, because a fixture that serves two
   purposes gets changed for one of them and breaks the other.

9. **The command and its run report.** `cmd/workload-run`, taking the model, the date, the seed and
   both dials exactly as `workload-plan` does, plus the gateway address and the metrics port. It
   writes the WP-20 manifest for the run it actually performed and prints the outcome table at the
   end. It also prints the reconciliation the Definition of Done asks for: the driver's own outcome
   totals against `ledger_transfers_total` for the same window, with the unknown column shown rather
   than folded into either side. Reproducibility is asserted the way WP-20 asserts it: the same seed
   and model produce the same sequence of requests, compared as bytes rather than as structs.

10. **Documentation, traceability and landing.** `workload/README.md` keeps its first line - a
    fixture, not a component of the bank - and gains the run. REQ-PERF-003 gets its evidence row in
    [`../../compliance/traceability-matrix.md`](../../compliance/traceability-matrix.md). **F-26**
    and **F-56** are resolved where this package closes what they describe, and anything the run
    reveals about the estate is logged as a new follow-up rather than fixed here, per the Out of
    scope. Then the Verification below, with real output into the pull request.

## Definition of Done

- [x] A planned run executes at the intended rate, with the realised send times matching the schedule
      within a stated tolerance. The scheduler's own lateness never exceeded 3 ms across the runs
      recorded in the pull request, and is published as `tessera_workload_schedule_lag_seconds`.
- [x] Every outcome class is accounted for, and the driver's own totals reconcile against
      `ledger_transfers_total` for the same window. Scheduled equals sent plus unsent; the three
      columns that must match did, in every run.
- [x] A retry of a lost request reuses its key, proven by the ledger replaying rather than posting
      twice. The same seed and date run twice against one ledger: 406 posted, then 0 posted and 421
      replayed, the ledger's own counter agreeing exactly.
- [x] Rate-limit refusals are counted separately and do not inflate the failure count, and are
      reconciled as never having reached the ledger.
- [x] A run is reproducible: the same seed and model produce the same sequence of requests, compared
      as rendered bytes rather than as structs.
- [x] Nothing in the estate was changed to make the run work. The diff touches `workload/`, the
      plan, the matrix and two READMEs, and nothing else.

## Verification

Boot the estate with the committed script, run a compressed day, and compare the driver's outcome
totals against the ledger's own metrics for the same window. Then kill the gateway mid-run and
confirm the unknown-outcome column moves rather than the failure column.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-003 Offered load is independent of the system's response | the open-model executor and intended-time latency |
