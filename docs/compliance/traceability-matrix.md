# Requirements traceability matrix

> **Partially filled.** The requirement catalogue below is complete - all 68 ids, each with its
> owning work package. The per-package sections exist only for packages that have been executed:
> WP-02 to WP-09, WP-10a, WP-10b, WP-11a, WP-11b, WP-12, WP-13, WP-14, WP-15, WP-16, WP-17, WP-19,
> WP-20, WP-21, WP-22, WP-23 and WP-24a. Every work package adds its own as
> part of the Definition of Done, and WP-18 verifies that none is missing.

Requirement to design to code to test, for the whole estate. This is the artefact an auditor samples: every requirement must resolve to an implementation and to a test that would fail without it. Each work package updates it as part of its Definition of Done.

---

## Requirement catalogue

**The authority for requirement IDs.** Every `REQ-*` id in this repository is defined here, extracted
from the Traceability section of the work package that owns it. 68 requirements across
twenty-four packages.

A requirement is **owned** by exactly one package - the one that implements and verifies it. Other
packages may *contribute* to a requirement without owning it; a contract that makes something
possible is not the same as an implementation that satisfies it, and the distinction is the whole
point of a traceability matrix.

> **Never invent a requirement id.** Take it from this table. If a genuinely new requirement appears,
> add it to the owning work package's Traceability section first, then here. An id that means two
> different things in two places converts an unknown into a false assurance, which is worse than
> having no matrix at all.

**Interface behaviour** (`REQ-API-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-API-001 | Money-moving requests are idempotent | [WP-08](../plan/wp/WP-08-ledger-api.md) |
| REQ-API-002 | The implementation cannot drift from its contract | [WP-08](../plan/wp/WP-08-ledger-api.md) |
| REQ-API-003 | Errors are machine-readable and leak nothing | [WP-08](../plan/wp/WP-08-ledger-api.md) |

**Architecture** (`REQ-ARC-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-ARC-001 | Domain layer is free of framework dependencies | [WP-07](../plan/wp/WP-07-ledger-persistence.md) |

**Audit trail** (`REQ-AUD-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-AUD-001 | The audit trail is append-only and tamper-evident | [WP-09](../plan/wp/WP-09-ledger-audit-outbox.md) |

**Customer master** (`REQ-CM-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-CM-001 | Customer and account metadata have a single system of record | [WP-10](../plan/wp/WP-10-customer-master.md) |
| REQ-CM-002 | The interface is contract-first SOAP | [WP-10](../plan/wp/WP-10-customer-master.md) |

**Operational resilience** (`REQ-DORA-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-DORA-001 | Operational resilience is tested, not assumed | [WP-18](../plan/wp/WP-18-incident-exercise.md) |

**Data protection** (`REQ-DP-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-DP-001 | All test data is synthetic | [WP-03](../plan/wp/WP-03-mainframe-data.md) |
| REQ-DP-002 | Personal data never reaches a log | [WP-09](../plan/wp/WP-09-ledger-audit-outbox.md) |

**Edge** (`REQ-EDG-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-EDG-001 | Authentication happens once, at the edge | [WP-12](../plan/wp/WP-12-api-gateway.md) |
| REQ-EDG-002 | Every request is traceable end to end | [WP-12](../plan/wp/WP-12-api-gateway.md) |
| REQ-EDG-003 | A slow dependency cannot exhaust the edge | [WP-12](../plan/wp/WP-12-api-gateway.md) |

**Estate fidelity** (`REQ-EST-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-EST-001 | Stratum 1 is authentically dated in style and stack | [WP-10](../plan/wp/WP-10-customer-master.md) |
| REQ-EST-002 | The estate contains genuinely different UI eras | [WP-15](../plan/wp/WP-15-backoffice.md) |

**Event delivery** (`REQ-EVT-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-EVT-001 | Events cannot be published without their postings committing | [WP-09](../plan/wp/WP-09-ledger-audit-outbox.md) |

**Fraud scoring** (`REQ-FRD-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-FRD-001 | Scoring never blocks money movement | [WP-13](../plan/wp/WP-13-fraud-scoring.md) |
| REQ-FRD-002 | Every decision is explainable | [WP-13](../plan/wp/WP-13-fraud-scoring.md) |
| REQ-FRD-003 | Decisions are reproducible from their recorded version | [WP-13](../plan/wp/WP-13-fraud-scoring.md) |

**Governance** (`REQ-GOV-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-GOV-001 | The repository states its purpose and boundaries | [WP-01](../plan/wp/WP-01-foundation.md) |
| REQ-GOV-002 | Deliberate technical debt is registered, not hidden | [WP-01](../plan/wp/WP-01-foundation.md) |
| REQ-GOV-003 | Work is planned in the repository and resumable cold | [WP-01](../plan/wp/WP-01-foundation.md) |
| REQ-GOV-004 | Execution rules are binding and machine-readable | [WP-01](../plan/wp/WP-01-foundation.md) |
| REQ-GOV-005 | Controls not enforced are registered as exceptions | [WP-01](../plan/wp/WP-01-foundation.md) |
| REQ-GOV-006 | Documentation is complete and traceable | [WP-18](../plan/wp/WP-18-incident-exercise.md) |

**Cross-era integration** (`REQ-INT-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-INT-001 | Every interface is defined by a contract before implementation | [WP-02](../plan/wp/WP-02-contracts.md) |
| REQ-INT-002 | Each era's contract is idiomatic to that era | [WP-02](../plan/wp/WP-02-contracts.md) |
| REQ-INT-003 | Modern events reach the mainframe in its own format | [WP-11](../plan/wp/WP-11-esb-adapter.md) |
| REQ-INT-004 | Duplicate delivery does not duplicate a movement | [WP-11](../plan/wp/WP-11-esb-adapter.md) |
| REQ-INT-005 | Undeliverable messages are captured, not lost | [WP-11](../plan/wp/WP-11-esb-adapter.md) |
| REQ-INT-006 | Business concepts are defined once and shared across eras | [WP-02](../plan/wp/WP-02-contracts.md) |
| REQ-INT-007 | Contract conformance is checked, not assumed | [WP-02](../plan/wp/WP-02-contracts.md) |

**Ledger** (`REQ-LED-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-LED-001 | Journal entries always balance | [WP-06](../plan/wp/WP-06-ledger-domain.md) |
| REQ-LED-002 | Postings are immutable; corrections are reversals | [WP-06](../plan/wp/WP-06-ledger-domain.md) |
| REQ-LED-003 | Money is exact and currency-aware | [WP-06](../plan/wp/WP-06-ledger-domain.md) |
| REQ-LED-004 | Account type determines sign convention | [WP-06](../plan/wp/WP-06-ledger-domain.md) |
| REQ-LED-005 | Concurrent transfers cannot lose an update or deadlock | [WP-07](../plan/wp/WP-07-ledger-persistence.md) |
| REQ-LED-006 | Materialised balances are verifiable, not assumed | [WP-07](../plan/wp/WP-07-ledger-persistence.md) |
| REQ-LED-007 | Postings cannot be updated or deleted | [WP-07](../plan/wp/WP-07-ledger-persistence.md) |

**Mainframe batch** (`REQ-MF-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-MF-001 | Record layouts are defined once and shared | [WP-03](../plan/wp/WP-03-mainframe-data.md) |
| REQ-MF-002 | Money on the mainframe is packed decimal, not binary or text | [WP-03](../plan/wp/WP-03-mainframe-data.md) |
| REQ-MF-003 | Movements are applied to the master in a single sequential pass | [WP-04](../plan/wp/WP-04-acctpost.md) |
| REQ-MF-004 | Invalid movements are rejected with a reason, never silently dropped | [WP-04](../plan/wp/WP-04-acctpost.md) |
| REQ-MF-005 | Every batch run produces balancing control totals | [WP-04](../plan/wp/WP-04-acctpost.md) |
| REQ-MF-006 | The end-of-day cycle is runnable and reproducible | [WP-05](../plan/wp/WP-05-eodrept.md) |
| REQ-MF-007 | The cycle produces an auditable report with balancing totals | [WP-05](../plan/wp/WP-05-eodrept.md) |

**Operations** (`REQ-OPS-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-OPS-001 | Every scheduled process has a runbook | [WP-05](../plan/wp/WP-05-eodrept.md) |
| REQ-OPS-002 | The service exposes business-level metrics and structured logs | [WP-09](../plan/wp/WP-09-ledger-audit-outbox.md) |
| REQ-OPS-003 | Operators can see and work reconciliation breaks | [WP-15](../plan/wp/WP-15-backoffice.md) |
| REQ-OPS-004 | Operator actions are attributable and audited | [WP-15](../plan/wp/WP-15-backoffice.md) |
| REQ-OPS-005 | The incident process is exercised, not merely documented | [WP-18](../plan/wp/WP-18-incident-exercise.md) |

**Performance and capacity** (`REQ-PERF-*`)

Added by the workload strand. Capacity is a different concern from operations, and folding it into
`REQ-OPS-*` - where 002 is already the metric surface and 005 the incident process - would leave the
prefix meaning nothing in particular.

| ID | Requirement | Owned by |
|---|---|---|
| REQ-PERF-001 | Demand is described as a versioned model, not embedded in a tool | [WP-20](../plan/wp/WP-20-workload-model.md) |
| REQ-PERF-002 | A load run is reproducible from its recorded manifest | [WP-20](../plan/wp/WP-20-workload-model.md) |
| REQ-PERF-003 | Offered load is independent of the system's response | [WP-21](../plan/wp/WP-21-workload-driver.md) |
| REQ-PERF-004 | Query cost is measured at production cardinality, not at fixture size | [WP-22](../plan/wp/WP-22-ledger-data-volume.md) |
| REQ-PERF-005 | Every service states its SLI, its objective and its error budget | [WP-23](../plan/wp/WP-23-slo-baseline.md) |
| REQ-PERF-006 | Normal is recorded before it is needed | [WP-23](../plan/wp/WP-23-slo-baseline.md) |
| REQ-PERF-007 | Degradation is exercised, not assumed | [WP-24](../plan/wp/WP-24-failure-injection.md) |
| REQ-PERF-008 | Every stratum is exercised at volume, not only the one that is easy to drive | [WP-25](../plan/wp/WP-25-estate-drivers.md) |

**Reconciliation** (`REQ-REC-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-REC-001 | Old and new cores are reconciled every cycle | [WP-16](../plan/wp/WP-16-recon.md) |
| REQ-REC-002 | Timing differences are distinguished from genuine drift | [WP-16](../plan/wp/WP-16-recon.md) |
| REQ-REC-003 | Breaks are investigated, never auto-corrected | [WP-16](../plan/wp/WP-16-recon.md) |

**Reporting** (`REQ-REP-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-REP-001 | Regulatory reports are generated from the ledger | [WP-17](../plan/wp/WP-17-reporting.md) |
| REQ-REP-002 | Reports are reproducible for historical dates | [WP-17](../plan/wp/WP-17-reporting.md) |
| REQ-REP-003 | Report totals reconcile independently to the ledger | [WP-17](../plan/wp/WP-17-reporting.md) |

**Customer interface** (`REQ-UI-*`)

| ID | Requirement | Owned by |
|---|---|---|
| REQ-UI-001 | Customers can transfer between accounts | [WP-14](../plan/wp/WP-14-web-banking.md) |
| REQ-UI-002 | Retrying a transfer cannot move money twice | [WP-14](../plan/wp/WP-14-web-banking.md) |
| REQ-UI-003 | Available balance is never presented as spendable when held | [WP-14](../plan/wp/WP-14-web-banking.md) |

---

## WP-02 - canonical data model and contracts

Ticket TB-1002, merged as `e6f968b`. WP-02 defines interfaces; it implements none of them, so it can
only *own* requirements about contracts existing and agreeing. Everything else it touches is recorded
below as a contribution, with the owning package named.

**Status values.** `Met` - satisfied and verified now. `Contract` - the contract enforces it as far as
a contract can, and the implementation that must also satisfy it does not exist yet.

### Owned by WP-02

| Requirement | Design | Evidence | Status |
|---|---|---|---|
| **REQ-INT-001** Every interface is defined by a contract before implementation | [`contracts/`](../../contracts/) | `bash contracts/validate.sh` exits 0 with no implementation anywhere in the repository | Met |
| **REQ-INT-002** Each era's contract is idiomatic to that era | copybook (1995), WSDL/XSD (2011), OpenAPI (2023), AsyncAPI (2023) | COBOL-85 fixed format checked by column; document/literal wrapped checked by the WSDL naming rule; `@redocly/cli lint`; `@asyncapi/parser` | Met |
| **REQ-INT-006** Business concepts are defined once and shared across eras | [`canonical-data-model.md`](../architecture/canonical-data-model.md) | Cross-era representation table - every field of every concept in all four eras, side by side | Met |
| **REQ-INT-007** Contract conformance is checked, not assumed | [`validate.sh`](../../contracts/validate.sh), [`check-copybook-offsets.py`](../../contracts/check-copybook-offsets.py) | The checker was shown to fail on a resized field and on a changed record length before it was accepted | Met |

### Contributed by WP-02, verified by the owning package

| Requirement | Owner | What WP-02 contributes | Status |
|---|---|---|---|
| **REQ-LED-001** Journal entries always balance | WP-06 | The XSD rejects a transfer carrying one movement; OpenAPI and AsyncAPI both pin `movements` to `minItems: 2, maxItems: 2` | Contract |
| **REQ-LED-002** Postings are immutable; corrections are reversals | WP-06 | `reversesTransferRef` on `Transfer`; `POST /transfers/{transferRef}/reversals` creates a new transfer and no endpoint mutates a posted one | Contract |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | `Money` is minor units plus ISO 4217 in every contract. No `number` or float type exists anywhere in the OpenAPI document; the XSD rejects `1234567.89` as an amount. The scale table carries JPY (0) and BHD (3) so a hard-coded 2 fails rather than passes quietly | Contract |
| **REQ-LED-004** Account type determines sign convention | WP-06 | `AccountTypeType` enumerates `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`, with the normal balance of each recorded in the canonical model | Contract |
| **REQ-API-001** Money-moving requests are idempotent | WP-08 | `Idempotency-Key` is required on all five money-moving operations in the OpenAPI document | Contract |
| **REQ-API-002** The implementation cannot drift from its contract | WP-08 | The OpenAPI document exists before the implementation, giving the WP-08 contract test something to assert against | Contract |
| **REQ-API-003** Errors are machine-readable and leak nothing | WP-08 | Every error response is an RFC 9457 `Problem` served as `application/problem+json`, and the schema carries no personal data | Contract |
| **REQ-MF-001** Record layouts are defined once and shared | WP-03 | `ACCTREC`, `MOVEREC` and `REJREC` with a column map; `check-copybook-offsets.py` asserts field offsets and record lengths against the canonical model | Contract |
| **REQ-MF-002** Money on the mainframe is packed decimal, not binary or text | WP-03 | `PIC S9(13)V99 COMP-3` fixed at 8 bytes, with worked examples verified against an encoder for a positive amount, a negative amount, zero and the maximum | Contract |
| **REQ-INT-003** Modern events reach the mainframe in its own format | WP-11 | The copybook byte layout the encoder must reproduce, plus the recorded constraint that stratum 0 carries scale-2 currencies only | Contract |
| **REQ-INT-004** Duplicate delivery does not duplicate a movement | WP-11 | At-least-once delivery is stated in the AsyncAPI contract, with the de-duplication key named, rather than left for consumers to discover | Contract |
| **REQ-CM-002** The interface is contract-first SOAP | WP-10 | `customer-master-v1.wsdl`, document/literal wrapped, authored by hand and importing the canonical XSD rather than redefining it | Contract |
| **REQ-EVT-001** Events cannot be published without their postings committing | WP-09 | The AsyncAPI document states that the event is relayed from the transactional outbox, never from the request thread | Contract |
| **REQ-DP-002** Personal data never reaches a log | WP-09 | No contract carries a name, address, national identifier or IBAN. What may be logged is stated explicitly in the canonical model | Contract |
| **REQ-UI-003** Available balance is never presented as spendable when held | WP-14 | `Hold` is defined, and `availableBalance` is specified as booked less every hold still `PLACED` | Met |

---

## WP-06 - ledger domain

Ticket TB-1006. Pure Java 17, no framework on the compile classpath. 86 tests, 0.28s, no database
and no network.

### Owned by WP-06

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-LED-001** Journal entries always balance | `JournalEntry` validates at construction, so an unbalanced entry cannot exist | `JournalEntryPropertiesTest` - for any generated postings the factory returns a balanced entry or rejects them, never anything else. Demonstrated to fail when the balancing check is removed and when a one-minor-unit tolerance is introduced | Met |
| **REQ-LED-002** Postings are immutable; corrections are reversals | `Posting` and `JournalEntry` expose no mutator; `JournalEntry.reverse` returns a new entry naming the original | `ReversalTest`, including a reflective check that no public method starts with set, add, remove, update or delete | Met |
| **REQ-LED-003** Money is exact and currency-aware | `Money` as `long` minor units plus `CurrencyCode`; per-currency ISO 4217 scale; `Math.addExact` so overflow throws rather than wraps; mixing currencies throws | `MoneyTest` and `MoneyPropertiesTest`. Demonstrated to fail when `Math.addExact` is replaced by `+`, and when the scale is hard-coded to 2 - which turns 1000 JPY into 10.00 | Met |
| **REQ-LED-004** Account type determines sign convention | `AccountType.signedEffect(Direction, Money)` - the rule exists in exactly one place | `AccountTypeTest`. Demonstrated to fail when `LIABILITY` is given the wrong normal balance | Met |

### Contributed by WP-06, verified by the owning package

| Requirement | Owner | What WP-06 contributes | Status |
|---|---|---|---|
| **REQ-ARC-001** Domain layer is free of framework dependencies | WP-07 | No framework is on the compile classpath at all, so a Spring import fails to compile rather than merely failing a rule. `DomainPurityTest` additionally scans every production source for forbidden imports and for `double`, `float` and `BigDecimal`. WP-07 replaces it with ArchUnit | Contract |
| **REQ-LED-007** Postings cannot be updated or deleted | WP-07 | `JournalEntryRepository` offers `append` and no update or delete, so the schema constraints in WP-07 have a port that agrees with them | Contract |
| **REQ-UI-003** Available balance is never presented as spendable when held | WP-14 | `Balance.available()` is derived from booked less every hold still `PLACED`, never stored, and reports a negative figure honestly rather than flooring at zero | Met |

---

## WP-03 - mainframe copybooks and synthetic data

Ticket TB-1003. The first package to turn the TB-1002 contracts into bytes on disk.

### Owned by WP-03

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-MF-001** Record layouts are defined once and shared | `mainframe/copybook/` holds the files `cobc` compiles against; `contracts/copybook/` is the source | `check-identity.py` asserts byte-identity in both directions. Demonstrated to fail on a single changed character | Met |
| **REQ-MF-002** Money on the mainframe is packed decimal, not binary or text | `comp3.py` - two digits per byte, sign in the final nibble, `0x0C` positive, `0x0D` negative, zero always positive | `test_comp3.py` asserts the canonical model's worked examples as **literal bytes**, not recomputed. Demonstrated to fail when the sign nibbles are swapped and when zero is written negative. Sign nibbles also read out of a real `xxd` dump | Met |
| **REQ-DP-001** All test data is synthetic | `generate.py` emits account and customer *references* from the canonical patterns and nothing else. There are no names, addresses or identifiers of any kind - nothing in these files relates to a person because there is nothing about people in them | `check-records.py` validates every field; the record layouts have no field that could hold personal data | Met |

### Contributed by WP-03, verified by the owning package

| Requirement | Owner | What WP-03 contributes | Status |
|---|---|---|---|
| **REQ-MF-004** Invalid movements are rejected with a reason, never silently dropped | WP-04 | Two reject fixtures in the movement file: a JPY movement, whose ISO 4217 scale of 0 `PIC S9(13)V99` cannot represent, and a movement against an account that does not exist | Contract |
| **REQ-INT-003** Modern events reach the mainframe in its own format | WP-11 | The byte-exact COMP-3 output the Java encoder must reproduce, including a positive amount, a negative amount, zero and the maximum representable value | Contract |
| **REQ-MF-003** Movements are applied to the master in a single sequential pass | WP-04 | Both files sorted ascending by account reference, which is what makes a single-pass match-merge possible | Contract |

---

## WP-04 - ACCTPOST, the balanced-line match-merge

Ticket TB-1004. The first COBOL application program in the estate.

### Owned by WP-04

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-MF-003** Movements are applied to the master in a single sequential pass | One pass over two sorted files, the master held in one record area and written once per key. Never read into a table - match-merge exists because the master does not fit in memory | `test-acctpost.py`, 13 scenarios. The empty-movement case asserts a pass that applies nothing produces a **byte-identical** master, which caught a read-ahead defect that wrote record 1 twice | Met |
| **REQ-MF-004** Invalid movements are rejected with a reason, never silently dropped | Six reason codes, each writing a `REJREC` carrying the movement verbatim in its first 120 bytes so it can be re-presented without re-encoding | Every code has a scenario that produces it. Demonstrated to fail on a version that counts rejections but does not write them - six scenarios go red at once | Met |
| **REQ-MF-005** Every batch run produces balancing control totals | Records read, applied, rejected and value moved; `read = applied + rejected` checked by the program, which sets a non-zero return code when it does not hold | Asserted on a mixed run and on the full 302-movement file | Met |

### Contributed by WP-04, verified by the owning package

| Requirement | Owner | What WP-04 contributes | Status |
|---|---|---|---|
| **REQ-LED-004** Account type determines sign convention | WP-06 | The same normal-balance rule implemented independently in COBOL: an `ASSET` increases on the debit side, a `LIABILITY` on the credit side. A scenario asserts debiting cash *increases* it, which is the error that looks correct until the balance sheet is drawn | Met at this tier |
| **REQ-INT-003** Modern events reach the mainframe in its own format | WP-11 | `R004` makes the stratum 0 scale-2 constraint executable: a movement in a currency of scale 0 or 3 is rejected on arrival, even though the integration tier should already have stopped it. Defence in depth - a 1995 core does not trust its feeds | Contract |
| **REQ-OPS-003** Operators can see and work reconciliation breaks | WP-15 | The rejects file the back office will work, with a machine-readable code and an operator-readable text carrying no personal data | Contract |

---

## WP-05 - EODREPT, the cycle and its runbook

Ticket TB-1005. The mainframe tier completed: the report, the job graph and the runner.

### Owned by WP-05

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-MF-006** The end-of-day cycle is runnable and reproducible | `run-eod.sh` executes the four steps `EODCYCLE.JCL` declares, checking each return code the way JCL checks `COND` and stopping the cycle on the first failure. The work directory is re-seeded from the input master every run and the run timestamp comes from the business date, not the clock. A SHA-256 marker refuses a second application of the same movement file | `test-eod-cycle.py`, 14 scenarios. Two runs over the same inputs are compared **byte for byte** with `cmp` on the master, the rejects and the report. A ragged movement file aborts at `STEP010` with `RC=12` and no later step runs. A second run of the same file exits 8; `--rerun` overrides it; a different file for the same date is allowed | Met |
| **REQ-MF-007** The cycle produces an auditable report with balancing totals | `EODREPT` control-breaks on currency with per-currency subtotals, a currency recap, and a reject recap counted from `REJECTS.DAT` by reason code. The count is printed beside the figure `ACCTPOST` reported; equal prints `*** IN BALANCE`, different prints `*** OUT OF BALANCE` and ends the step `RC=12`, absent prints `NOT SUPPLIED` rather than claiming a reconciliation that was not performed | `test-eodrept.py`, 18 scenarios. Pagination asserted at the boundary: 55 accounts put **54 detail lines on page one and one on page two**. The final currency's subtotal is asserted separately because no record follows it to trigger the break. A deliberate mismatch is asserted to produce `RC=12`. On the full run: 162 rejects counted, 162 reported, in balance | Met |
| **REQ-OPS-001** Every scheduled process has a runbook | [`docs/runbooks/eod-cycle.md`](../runbooks/eod-cycle.md): the step graph with inputs and outputs, the three totals to check on a clean run, a failure mode per step with its diagnostic and action, restart and recovery, when `--rerun` is legitimate, where rejects land, and the escalation path | Written against the behaviour the tests assert, not against intent. Each failure-mode diagnostic is a message the code actually emits | Met |

### Contributed by WP-05, verified by the owning package

| Requirement | Owner | What WP-05 contributes | Status |
|---|---|---|---|
| **REQ-MF-005** Every batch run produces balancing control totals | WP-04 | A second, independent count of the same run. `EODREPT` counts the rejects file itself and fails the step when its total disagrees with `ACCTPOST`'s - a control total nobody re-derives is a number, not a control | Met at this tier |
| **REQ-OPS-003** Operators can see and work reconciliation breaks | WP-15 | The reject recap an operator reads first: counts by reason code with the text taken from the reject record, so the back office sees the shape of a night's failures before opening the file | Contract |
| **REQ-REC-001** Old and new cores are reconciled every cycle | WP-16 | `ACCTNEW.DAT` in a known-good state after a reproducible cycle, plus the per-currency totals the reconciliation compares against. The produced master is validated against `contracts/copybook/column-map.md` with the same checker the generator is held to | Contract |

---

## WP-07 - Ledger persistence

Ticket TB-1007. The ledger's ports, implemented against real PostgreSQL.

### Owned by WP-07

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-LED-005** Concurrent transfers cannot lose an update or deadlock | `SELECT ... FOR UPDATE` on the account rows, acquired through `AccountLocks.lockInOrder`, which sorts by account reference. Every transaction takes the same accounts in the same order, so one waits and then proceeds instead of the pair blocking each other. The order is arbitrary; that it is the same order every time is the mechanism. The port takes one reference at a time, so the rule lives in the adapter rather than widening a WP-06 interface to suit infrastructure | `AccountLocksConcurrencyTest`: six threads move money around a ring of five accounts, in both directions over the same pairs, asserting **total value across the ring is unchanged**. Demonstrated to fail with the ordering removed - PostgreSQL reports `deadlock detected` (SQLState 40P01) five times and the run aborts. The lock order is also asserted directly, because a property that only shows up as a flaky failure under load is one no test can be trusted to hold | Met |
| **REQ-LED-006** Materialised balances are verifiable, not assumed | Two independent derivations. `balanceOf` reads the materialised `balance` row - the fast path an API call takes - while `BalanceReconciliation` sums the postings in SQL and compares. The reconciliation reimplements the sign convention that `AccountType.signedEffect` holds in Java; the duplication is deliberate, because a check written against the same code it checks proves nothing | `BalanceReconciliationTest`: zero drift over a generated ledger spanning all five account types, and **a deliberately corrupted balance row detected**, down to a single minor unit. Demonstrated to fail on four mutations - inverted sign convention, inner join dropping accounts with no postings, a one-unit tolerance, and reporting nothing at all | Met |
| **REQ-LED-007** Postings cannot be updated or deleted | `JournalEntryRepository` offers `append` and nothing else, so the Java side cannot express a mutation. For everything that is not the Java side, a trigger raises on `UPDATE` or `DELETE` against `posting`. A correction is a reversing entry | `SchemaConstraintTest` watches both an `UPDATE` and a `DELETE` raise. `HexagonalBoundariesTest` additionally fails the build if any source in the module contains such SQL - the trigger catches it at runtime, the scan catches SQL that was never run | Met |
| **REQ-ARC-001** Domain layer is free of framework dependencies | The domain is a **separate module with no framework on its compile classpath**, so a Spring import fails to compile rather than failing a rule. ArchUnit polices the direction of the dependency from the persistence module, which has both sides on its classpath and can therefore see a violation | `HexagonalBoundariesTest`: the domain and its ports depend on no Spring, Jakarta, Flyway, JDBC or Jackson package, and never on `..adapter..`. The import is asserted non-empty first, because a rule over an empty set of classes passes vacuously. Demonstrated to fail when a port imports `java.sql.Connection`. `DomainPurityTest` in `ledger-core` is retained alongside it | Met |

### Contributed by WP-07, verified by the owning package

| Requirement | Owner | What WP-07 contributes | Status |
|---|---|---|---|
| **REQ-LED-001** Journal entries always balance | WP-06 | The invariant enforced a second time in the schema, by a deferrable constraint trigger checked at commit. The domain rejects an unbalanced entry; so now does the database, for callers that never go through the domain | Met at this tier |
| **REQ-LED-004** Account type determines sign convention | WP-06 | A third independent implementation of the normal-balance rule, in the reconciliation SQL, asserted to agree with the Java one across all five account types. COBOL has the fourth, in `ACCTPOST` | Met at this tier |
| **REQ-REC-001** Old and new cores are reconciled every cycle | WP-16 | The pattern reconciliation will follow between tiers, proven within one: derive the same figure two independent ways and compare, report drift as data rather than logging it, and never auto-correct - overwriting a balance from the postings destroys the evidence of how it drifted | Contract |
| **REQ-LED-002** Postings are immutable; corrections are reversals | WP-06 | The immutability the domain states, made physical: the trigger on `posting` refuses `UPDATE` and `DELETE` outright, so a correction has to be a reversing entry even for a caller that never touches the Java | Met at this tier |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | `bigint` minor units and a `char(3)` code, never `numeric` and never a float. Two composite foreign keys make currency agreement structural: a posting must be in its entry's currency and in its account's, so no conversion can occur by accident | Met at this tier |

---

## WP-08 - Ledger API

Ticket TB-1008. The ledger behind HTTP, and the idempotency that separates a banking API from a CRUD
API.

### Owned by WP-08

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-API-001** Money-moving requests are idempotent | `IdempotencyFilter` claims the key, does the work and stores the response **in one transaction**, and every use case below joins it. The claim is an upsert - `INSERT ... ON CONFLICT (key) DO UPDATE` - so a retry arriving while the first request is still in flight blocks on the row lock until the first commits, then replays its answer. `DO NOTHING` would not wait, and neither would a `SELECT` followed by an `INSERT`. Only a 2xx is recorded, so a rejected transfer stays retryable under the same key; a replay answers `200` with the stored body byte for byte, never re-rendered. The fingerprint is SHA-256 over the method, the resolved path and **canonical** JSON, so a client that retries by re-serialising is not refused for reordering its fields | `JdbcIdempotencyStoreTest`: eight threads retrying one key produce **one execution and one identical answer**. Demonstrated to fail with `DO UPDATE` replaced by `DO NOTHING` - the same test reports `expected: 1 but was: 8`, which is eight executions of one payment. `TransferEndpointsTest` asserts the replay is byte-identical and the balance moved once, that a changed amount under the same key is `409` and posts nothing, and that a rejected transfer does not burn the key. `RequestFingerprintTest` fixes what counts as the same request | Met |
| **REQ-API-002** The implementation cannot drift from its contract | `OpenApiContractTest` walks every operation the document declares and validates **both sides of every exchange** against the schema declared for it, then fails if any `operationId` was never reached - a contract test that only checks what somebody remembered to call has a hole exactly where the drift will be. Validation is by a JSON Schema 2020-12 implementation rather than an OpenAPI-specific one: OpenAPI 3.1 schemas *are* 2020-12 and this document uses `type: [string, 'null']`, which the Java OpenAPI validators lose by converting down to 3.0. Every asserted field is traced to `canonical-data-model.md` in the test's own javadoc | `OpenApiContractTest`, 11 operations covered. Demonstrated to fail in both directions: adding an undeclared field to a response (`additionalProperties: false` catches it) and rendering `amountMinor` through `Money.toPlainString()`, which produced `"0.00"` and `string found, integer expected` - money as a decimal on the wire, caught by the contract rather than by review | Met |
| **REQ-API-003** Errors are machine-readable and leak nothing | `LedgerProblemHandler` maps the domain's whole failure vocabulary to RFC 9457 documents served as `application/problem+json`, with a stable `type` URI from one enum so a reworded title is not a contract change. It is ordered ahead of Spring's own problem-details advice, which answers `type: about:blank`. Its **last** handler is the control: a catch-all that logs the failure in full and reports a fixed sentence, so an unrecognised exception cannot carry a class name, a SQL fragment or a stack frame to a caller. `IdempotencyFilter` writes its own conflict document, because a filter sits outside Spring MVC and nothing thrown there reaches an advice | `LedgerProblemHandlerTest` asserts each mapping and, for an unrecognised failure, that the body contains no `SQLException`, no `SELECT`, no table name and no `bank.tessera` frame. The whitelabel page and stack traces are disabled in `application.yml`, and a missing `Idempotency-Key` is asserted to return `problem+json` rather than Spring's default body | Met |

### Contributed by WP-08, verified by the owning package

| Requirement | Owner | What WP-08 contributes | Status |
|---|---|---|---|
| **REQ-LED-005** Concurrent transfers cannot lose an update or deadlock | WP-07 | The composition WP-07's pieces were built for. `Transfer` locks both accounts through `UnitOfWork.inTransactionLocking` *before* reading either balance - a use case that read first and locked afterwards would pass every single-threaded test and lose money under load. `TransferConcurrencyTest` runs the ring through the use case rather than the adapter and asserts total value is conserved | Met at this tier |
| **REQ-LED-002** Postings are immutable; corrections are reversals | WP-06 | The correction path, made reachable: `ReverseTransfer` posts a new opposite entry through `JournalEntry.reverse` - the only construction path the domain offers - and never touches the original. `V3` adds the `reverses` column the schema was missing, with a unique index so a transfer cannot be reversed twice | Met at this tier |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | Money crosses the wire as `amountMinor` plus a currency code and never as a decimal, asserted by the contract test rather than assumed. A currency the ISO 4217 table does not carry is rejected at the boundary, not defaulted | Met at this tier |
| **REQ-DP-002** The ledger holds no customer identity | WP-09 | No response carries a name, an address or an identifier of any kind - only account and customer references. The idempotency table stores a digest of the request rather than the request, so a client's body is never at rest here, and the conflict message names neither the key nor the fingerprint | Met at this tier |
| **REQ-UI-002** Retrying a transfer cannot move money twice | WP-14 | The server-side half, in full. The UI's contribution is to reuse a key across a retry rather than mint a new one | Met at this tier |
| **REQ-EDG-002** Every request is traceable end to end | WP-12 | `X-Correlation-Id` is echoed onto every Problem document when the gateway sends one. **Revised by WP-09:** it is now also generated here when the gateway sends none. The original reasoning - a correlation id minted per tier correlates nothing - held for echoing and not for absence: a request that arrived without one was untraceable, which is worse than an id that correlates only from this tier inward | Contract |

---

## WP-09 - Audit chain, outbox and observability

Ticket TB-1009. Landed as two pull requests: the audit chain and the outbox first
([#24](https://github.com/k-napiontek/tessera-bank/pull/24)), observability second
([#25](https://github.com/k-napiontek/tessera-bank/pull/25)).

### Owned by WP-09

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-AUD-001** The audit trail is append-only and tamper-evident | Two mechanisms, doing different jobs. `audit_record` (`V7`) refuses `UPDATE` and `DELETE` by row trigger and `TRUNCATE` by statement trigger - the second matters because `TRUNCATE` fires no row trigger, so without it one statement empties the trail while the control never runs. Above that, every row carries the SHA-256 of its contents chained onto its predecessor's hash, so a change made by somebody who can drop a trigger is still detectable. The canonical form is **length-prefixed**, which is the actual control: concatenating fields lets `{"a": "bc"}` and `{"ab": "c"}` hash identically, and an auditor could be shown either row with the chain verifying for both. The entry is normalised before hashing, because `timestamptz` keeps microseconds and a `uuid` column returns lowercase - hash first and verification reports tampering on rows nobody touched. Appends serialise on an advisory lock held to commit, so sequence order and chain order are the same order. See [ADR 0005](../governance/adr/0005-hash-chained-audit-trail.md) | `AuditChainTest`: a tampered row is named by `seq` with the reason "altered after it was written", and a deleted row is caught at its successor with a different reason - they are different incidents. The tamper is performed with the trigger disabled, which is the scenario rather than a workaround. `AuditEntryTest` feeds the encoding the two entries a naive concatenation would flatten together. The advisory lock is proved load-bearing by a deterministic interleaving: with it removed, the second append is refused | Met |
| **REQ-EVT-001** Events cannot be published without their postings committing | `outbox_record` (`V8`) is written by `Transfer` and `ReverseTransfer` **inside the transaction that writes the postings**, through the `EventOutbox` port - which opens no transaction and talks to no broker. `OutboxRelay` then claims pending rows with `FOR UPDATE SKIP LOCKED`, **publishes, and only then marks dispatched**. The order is the whole guarantee: marking first turns every failed publish into a permanently lost event. A batch stops at its first failure, so events for one transfer keep their order. `KafkaEventPublisher` awaits the broker's acknowledgement, keyed by `transferRef`. See [ADR 0004](../governance/adr/0004-transactional-outbox.md) | `OutboxTransactionTest`: a posted transfer leaves an undispatched row, and a transfer refused by the overdraft policy leaves **neither a posting nor an outbox row** - both halves, because either alone is satisfied by a broken implementation. `OutboxRelayTest.republishesAfterFailureBeforeMark` rolls back the relay's transaction after a successful publish, which is what a crash does to it, and asserts the event is published again rather than lost. Demonstrated to fail with the two steps swapped. `KafkaOutboxContractTest` publishes through a real broker and validates the payload against the AsyncAPI message schema; demonstrated to fail when `Money` is serialised as a bare number | Met |
| **REQ-OPS-002** The service exposes business-level metrics and structured logs | `MoneyMovementMetricsFilter` counts `ledger.transfers` by `operation` and `outcome`, and times `ledger.posting.latency`. It sits in the filter chain rather than inside the use cases: those live in `ledger-core`, which carries no framework on its compile classpath, and what an operator needs is what the customer experienced. **A replay is its own outcome**, not a success - counting a `200` from the idempotency store as a posting would inflate throughput with work nobody did and hide clients timing out. Rejections are counted but not timed, because a validation failure returning in a millisecond and a transfer that took two locks share no meaningful percentile. `ledger.outbox.pending` and `ledger.outbox.lag` are gauges read from the table, so a restarted instance reports the truth rather than starting from zero. Logs are JSON in one format everywhere, carrying the MDC - Boot 3.2 has no native structured logging, so the encoder is an explicit dependency rather than a property | `BusinessMetricsTest` drives a posting, a rejection and a replay and asserts each under its own tag **through `/actuator/metrics`**, then asserts the business metrics appear in the `/actuator/prometheus` scrape - a metric the registry holds but nothing can scrape is a metric nobody has. Demonstrated to fail when the replay outcome is folded into `posted`. `HealthProbeTest` stops the container and asserts readiness answers `503` while liveness stays `200`; demonstrated to fail with `db` removed from the readiness group | Met |
| **REQ-DP-002** Personal data never reaches a log | Two halves. **The audit trail** records references, statuses, amounts in minor units and dates, and deliberately not the remittance `reference` - the one field a paying customer controls, classified restricted-if-misused, in a row retained for years. **The logs** carry an MDC of `correlationId`, `traceId` and `spanId` and nothing else; all three are internal identifiers that resolve to no person. The relay logs a message key and never a payload, and `LedgerProblemHandler`'s catch-all reports a fixed sentence rather than an exception message | `LogHygieneTest` drives a real transfer whose remittance reference is a distinctive synthetic marker, on the happy path **and the error path**, captures every log event at `DEBUG`, and fails if the marker appears anywhere. Demonstrated to fail by adding one controller log statement that logs the request. A second assertion holds the MDC to an allowlist, because everything in the MDC is on every line and a request-scoped map is where somebody will one day park a customer name. `AuditTrailTest.freeTextIsNeverRecorded` asserts the audit half | Met |

### Contributed by WP-09, verified by the owning package

| Requirement | Owner | What WP-09 contributes | Status |
|---|---|---|---|
| **REQ-EDG-002** Every request is traceable end to end | WP-12 | The ledger's half, in two parts. **The correlation id:** `CorrelationIdFilter` accepts `X-Correlation-Id` when it is a UUID, generates one when it is absent or malformed, puts it in the MDC, and echoes it on the response - including on the two paths that call `response.reset()`, which clears headers and would otherwise drop it silently. A non-UUID is replaced rather than propagated: what a caller sends here reaches log lines and a Problem document, and honouring arbitrary text would let a caller choose this service's log contents. The id reaches the audit row and the event payload, so one request is one identifier across the estate. **Tracing:** spans are produced and the context leaves as W3C `traceparent`, which is what the Go gateway and the Python consumer speak; a B3 mismatch does not error, it silently starts a new trace at every hop. No exporter and no collector address live here (ADR 0001). The two identifiers coexist deliberately - a trace reaches as far as W3C propagation does, while the correlation id also survives the SOAP call and the fixed-width record the ESB writes for a mainframe where no tracing exists | Met at this tier |

---

## WP-12 - api-gateway

Ticket TB-1012. Landed as [#27](https://github.com/k-napiontek/tessera-bank/pull/27).

### Owned by WP-12

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-EDG-001** Authentication happens once, at the edge | `auth.Middleware` validates a bearer JWT and forwards it unchanged; the gateway mints nothing, because an edge component holding a signing key can mint any identity in the bank. The accepted algorithms are **pinned to an asymmetric set**, which is the whole defence against algorithm confusion: a verifier that honours the token's own `alg` accepts HS256 signed with the RSA *public* key, and that key is public. `alg: none` is absent for the same reason. `exp` is required, so a token cannot be valid forever, and `iss`, `aud` and a non-empty `sub` are checked with 30 seconds of leeway for skew and no grace beyond it. Several public keys are accepted so a rotation is not an outage; a **private** key in the file fails the boot. Authorisation is coarse and separate: `routing.Routes()` names the scope each operation needs, and the ledger decides whether this caller may act on this account - splitting one decision across two tiers is how it ends up enforced in neither. See [ADR 0007](../governance/adr/0007-gateway-validates-and-forwards.md) | `auth` package: `alg: none`, HS256-over-the-public-key, an unknown signing key, expiry, `nbf`, a missing `exp`, wrong issuer, wrong audience and a missing subject are each refused in their own case; a rejection is asserted to name none of them, because telling a caller which check failed tells them which one to work on. `gateway` package, end to end: an unauthenticated, expired or read-only token **never reaches the stand-in ledger** - the assertion is on the ledger having seen nothing, not merely on the status code | Met |
| **REQ-EDG-002** Every request is traceable end to end | `correlation.Middleware` honours a canonical UUID from the caller and replaces anything else, which is `CorrelationId.resolve` in the ledger, rule for rule - two tiers that disagree about which ids are acceptable produce two ids for one request. The id is set on the response **before** the handler runs, so a 500 is not the one answer that loses it; it is forwarded to the ledger, put on every access line, and written into every Problem document. The access line deliberately omits the token, the client address, the query string and the body: a credential in a log store is replayable, an IP address is personal data under GDPR, and a query string is where callers put credentials | `logging` package: one line per request, carrying the resolved id; a request carrying a token, a cookie and a forwarded address is asserted to produce a log containing none of them, and a query string carrying `access_token=` is asserted absent while the path survives. `correlation` package fixes the resolve rule against eight rejected spellings, including a value containing a newline and a log-line fragment. `gateway` package: one supplied id appears on the client's response, on the ledger's request and in the gateway's log | Met |
| **REQ-EDG-003** A slow dependency cannot exhaust the edge | Every attempt at the ledger carries its own deadline, and the retry budget is bounded at three by the configuration itself. A retry happens only when the request is replayable - a safe method, or one carrying an `Idempotency-Key` - **and** the failure is one the ledger cannot have acted on. A timeout is never retried: it means the ledger has the request and is struggling, so a second copy multiplies the load exactly when it can least afford it. A timeout answers `504` and a connection failure `502`, which say different things: after a timeout the request may well have been applied. Request and response bodies are capped and the decision is taken **before** anything is written, because a 200 already on the wire cannot be turned into an error. Rate limiting caps one caller's share per route; it is per instance, and every place that describes it says so. See [ADR 0006](../governance/adr/0006-edge-rate-limit-is-per-instance.md) | `proxy` package: a ledger that never answers produces `504` in under a second rather than a hung connection, and the refusal is asserted to leak neither the internal host nor the transport error. **A POST without an idempotency key is asserted to be sent exactly once** - the rule the whole retry design exists to protect, since a connection error can be raised after the ledger has read the request. The same POST with a key is retried, and its body is asserted to arrive intact on the second attempt. `ratelimit` package: the burst is exact under 200 concurrent callers with the race detector on, one subject cannot spend another's budget, refills are capped at the burst, and idle buckets are forgotten | Met |

### Contributed by WP-12, verified by the owning package

| Requirement | Owner | What WP-12 contributes | Status |
|---|---|---|---|
| **REQ-API-002** The implementation cannot drift from its contract | WP-08 | A second consumer of the same document. The gateway's route table is checked against `contracts/openapi/ledger-core.yaml` in both directions: an operation the contract declares and the gateway does not route is unreachable, and a route the contract does not declare is a hole opened at the edge. Anything else is `404` before it is forwarded - which matters because the ledger serves its actuator endpoints beside its API, and a gateway that forwards what it does not recognise publishes them | Met at this tier |
| **REQ-DP-002** Personal data never reaches a log | WP-09 | The edge's half. The access line carries a correlation id, a method, a path and a status - no token, no cookie, no client address, no query string and no body. The rate limiter keys on the token's subject rather than on an address, both because two customers in one branch share an address and because an address identifies a person | Met at this tier |

---

## WP-13 - fraud-scoring

Ticket TB-1013. Landed as [#29](https://github.com/k-napiontek/tessera-bank/pull/29).

### Owned by WP-13

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-FRD-001** Scoring never blocks money movement | The service consumes `tessera.ledger.transfer-posted.v1` **after** the transfer has posted and the ledger has already answered the customer. It shares nothing with the money-movement path: no call into the ledger, no lock, no database, and the ledger's outbox relay does not wait for it. A `BLOCK` is an opinion published on a topic - reversing a transfer happens through the ledger's own reversal path, with its own audit trail, so this service can be stopped, redeployed or left broken without a customer noticing | The live run in the pull request: with the service **stopped**, transfers post normally through the gateway and the ledger; started again, the whole backlog is consumed and every decision published. `auto.offset.reset=earliest` is what makes the backlog a backlog rather than a gap. The unit suite proves the loop never calls anything but its two ports | Met |
| **REQ-FRD-002** Every decision is explainable | Each rule carries an 8-character code and a description, and the verdict reports the codes that fired, **in catalogue order** so two decisions are comparable. Weights are coarse on purpose: a rule engine standing in for a model cannot support three significant figures of risk, and what the weights must get right is the ordering - structuring outranks merely large. The reason codes ride on the published payload, so the explanation is in the message rather than in a log line somebody has to still have | `test_rules.py` fires each rule in isolation and asserts the code it produces, and asserts a combination: a structured, out-of-hours reversal scores 350+150+100 and reports `("AMT_STRC", "REVERSAL", "OFFHOURS")`. The contract test proves a code longer than the contract's 8 characters would be refused on the wire. `test_kafka_integration.py` reads a real decision off a real broker and asserts its `reasonCodes` | Met |
| **REQ-FRD-003** Decisions are reproducible from their recorded version | Two mechanisms. **Every rule is a pure function** of one event and the parameters - no clock, no randomness, no lookup, no memory - so a replayed event scores identically; the out-of-hours rule reads the event's own `postedAt` rather than the wall clock. **The recorded version covers the parameters as well as the code**: `modelVersion` is the catalogue version plus a length-prefixed digest of the thresholds in force, because a decision recorded as `rules-2026.08.1` alone could only be reproduced if nobody had touched the configuration since. This is why the service has **no velocity rule** - it would be the most useful rule here and it would make the claim false. See [ADR 0008](../governance/adr/0008-fraud-rules-are-pure-functions.md) | `test_rules.py` parses `rules.py` over its syntax tree and fails if it imports `datetime`, `time`, `random`, `os`, `socket`, `pathlib` or `requests` - reading the tree rather than the text, so the module can explain in prose what it may not do. `test_scoring.py` asserts a changed threshold changes `modelVersion`, and that two parameter sets a naive concatenation would flatten together do not share a digest. `test_service.py` scores the same message twice and compares every field except `decidedAt`, which is deliberately excluded: when scoring happened is a fact about the run, not about the transfer | Met |

### Contributed by WP-13, verified by the owning package

| Requirement | Owner | What WP-13 contributes | Status |
|---|---|---|---|
| **REQ-EVT-001** Events cannot be published without their postings committing | WP-09 | The first consumer of that outbox, and the proof it was worth building: `KafkaEventSource` commits its offset **only after** the decision has been acknowledged by the broker, which is the same ordering argument the relay makes about marking a row dispatched. A real-broker test asserts that a handled event is not rescored after a restart, and that a poison message does not stop the ones behind it | Met at this tier |
| **REQ-EDG-002** Every request is traceable end to end | WP-12 | The last tier the identifier reaches. The `correlationId` on the transfer event is carried onto the decision payload and onto every log line here, so one customer request is one identifier from the gateway, through the ledger, to the decision taken about it | Met at this tier |
| **REQ-DP-002** Personal data never reaches a log | WP-09 | The remittance `reference` - the one field a paying customer controls - is dropped by the log formatter along with anything named like a credential, whatever a caller passes. `test_observability.py` asserts a caller attaching one does not get it logged | Met at this tier |

---

## WP-17 - reporting

Ticket TB-1017. Landed as [#31](https://github.com/k-napiontek/tessera-bank/pull/31).

### Owned by WP-17

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-REP-001** Regulatory reports are generated from the ledger | Three reports off one read: a daily position per account and currency, a movement summary for the business date, and a fixed-width regulatory extract in `TB-REGEXT-V1`, whose layout is declared in [`contracts/reporting/`](../../contracts/reporting/) before the writer that satisfies it. Every figure is summed from `posting`, never read from the `balance` table - the materialised balance reflects now, and a report built on mutable state is not auditable. The reader is a read-only `REPEATABLE READ` connection, because a reporting job that *can* write to the ledger is one that eventually will | `test_ledger.py` runs against real PostgreSQL with the ledger's own Flyway migrations applied in order from `services/ledger-persistence`, so the reader fails the day WP-07's schema moves rather than passing against a transcription. `test_extract.py` parses the contract and asserts the writer's offsets are the contract's offsets - two independent statements of one layout, demonstrated to fail when one byte is moved. `check-extract-layout.py` runs in `contracts/validate.sh` and was demonstrated to fail on a resized field, a changed record length and a picture disagreeing with its width | Met |
| **REQ-REP-002** Reports are reproducible for historical dates | A report is cut at a **ledger position**, which is an `audit_record.seq`. That column is the only one in the ledger where sequence order is commit order, because `JdbcAuditLog` holds `pg_advisory_xact_lock` from before it reads the chain head until commit - so if seq P is visible, every seq below it has committed. `max(posting.id)` and `created_at` are both allocated at transaction *start* and would admit rows on the rerun that the first run could not see. Accounts are bounded by their `ACCOUNT_OPENED` row for the same reason. The position and the chain head hash are stamped on every report, so a figure names both how far along the chain it was cut and which chain. No report body carries a wall clock; the run instant lives in the manifest. See [ADR 0009](../governance/adr/0009-reports-are-cut-at-an-audit-position.md) | `test_reproducibility.py` runs, then posts a further transfer **and a backdated one**, reruns at the recorded position, and asserts all three files are identical byte for byte. **Demonstrated to fail** with the position filter removed: the backdated 4 200 leaks in and every figure moves. Its counterpart asserts a fresh cut after new activity is *not* identical, so byte-identity is a property of the position rather than of a ledger that happened not to change | Met |
| **REQ-REP-003** Report totals reconcile independently to the ledger | Independently is the load-bearing word. The report sums postings in Python and applies the normal-balance rule from `accounting.py`; the ledger maintains `balance.booked_minor` in Java through `AccountType.signedEffect`. Two implementations of one rule, required to agree. Both reports carry per-currency control totals and **raise rather than print** when debits do not equal credits - for complete entries that is the definition of double entry, so a currency where they disagree is one where a posting went missing between the query and the file. The extract's trailer carries a hash total instead: absolute minor units across every currency, meaningless as money and exactly right as a check, since any lost or duplicated record changes it and no exchange rate is needed to recompute it | `test_reconciliation.py` compares the report's balances against the ledger's own `balance` rows over a mixed set of account types - with liabilities alone the sign convention cancels out and a report that had it backwards would reconcile perfectly - and pins all five types with hand-computed figures no shared code path can fake. It also compares a control total against a direct SQL aggregate. `test_position_report.py` and `test_movement_report.py` assert an imbalanced currency fails the report | Met |

### Contributed by WP-17, verified by the owning package

| Requirement | Owner | What WP-17 contributes | Status |
|---|---|---|---|
| **REQ-AUD-001** The audit trail is append-only and tamper-evident | WP-09 | The first reader to depend on the chain's *ordering* rather than on its verification. Every report stamps the chain head hash beside the position, so a file re-cut against a restored database whose history diverged carries a different hash for the same sequence number and is detectable rather than merely unlikely | Met at this tier |
| **REQ-DP-002** Personal data never reaches a log | WP-09 | The remittance `reference_text` is never selected, never rendered and dropped by the log formatter along with anything named like a credential. The extract has **no free-text field at all** - every `PIC X` field carries an identifier or an enumeration - so a name, an address or an email address cannot be represented in it, which `test_extract.py` asserts field by field against the declared pictures | Met at this tier |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | A fourth tier that refuses floating point. `money.py` holds minor units as `int` with the scale resolved per currency, and `test_money.py` parses the module's syntax tree and fails on a true division, a `float`, a `Decimal` or a `round` - parsed rather than grepped, so the module can explain in prose what it may not do. JPY at scale 0 and BHD at scale 3 are carried so a hard-coded 2 fails rather than passes | Met at this tier |

---

## WP-14 - web-banking

Ticket TB-1014. Stratum 4, TypeScript + React. 112 tests against a mocked gateway, no network.

### Owned by WP-14

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-UI-001** Customers can transfer between accounts | A three-step journey - enter, confirm, result - where the confirmation step is the point at which the request stops changing rather than ceremony. Everything goes through `edge/api-gateway`; the application holds no address for the ledger. The typed amount is converted to minor units in the string domain (`minorUnitsFromDecimal`), never through `parseFloat` and a multiplication, and more decimals than the currency carries are **refused rather than rounded** - rounding here would move an amount the customer did not agree to move. The outcome is one of five states, each rendered as itself | `Transfer.test.tsx` drives the journey through MSW: the amount `1234.56` arrives as `"amountMinor":123456`, a third decimal on a PLN transfer is refused, zero is refused, and paying an account into itself is refused. `accessibility.test.tsx` completes the whole journey from the keyboard alone | Met |
| **REQ-UI-002** Retrying a transfer cannot move money twice | The idempotency key belongs to the **attempt**, not to the HTTP call. It is minted once when the customer confirms, and reused by every retry of that request - after a rejection, after a timeout, after a dropped connection. Any change to the request mints a new one, because a different body under the same key is a `409` rather than a retry. The comparison is over the request that goes on the wire, so whitespace the encoder discards does not count as a change | `transferAttempt.test.ts` pins both halves: the same request keeps its key, and a change to the amount, the payee or the reference replaces it. `Transfer.test.tsx` proves it end to end - a retry after a `422` sends a byte-identical body under an identical key, and editing the details sends a different one. Only the pair proves the rule; either alone is satisfied by a constant | Met |
| **REQ-UI-003** Available balance is never presented as spendable when held | Booked and available are two labelled figures on every card, always, and where they differ the card states how much is held in words rather than leaving the customer to subtract. A negative available balance prints honestly rather than flooring at zero, matching `Balance.available()`. Both are read as exact `bigint` minor units - `amountMinor` is an `int64` and `Number.MAX_SAFE_INTEGER` is roughly a ninth of that, so a JSON number is read from its **source text** through the reviver rather than from the rounded double | `Dashboard.test.tsx` asserts both labels and both figures are present, that the held amount is stated when they differ and absent when they agree, that `-15.00` renders as itself, and that an amount of 9 223 372 036 854 775 807 minor units displays exactly. `money.source.test.ts` parses `money.ts` and fails on a division, a fractional literal or `toFixed`, and was demonstrated to fail with a division planted | Met |

### Contributed by WP-14, verified by the owning package

| Requirement | Owner | What WP-14 contributes | Status |
|---|---|---|---|
| **REQ-LED-004** Account type determines sign convention | WP-06 | The one place outside the ledger that has to know the rule, because a statement cannot be checked against its own opening and closing balances without it. `signedEffect` mirrors `AccountType.signedEffect`, and `ledger.test.ts` was demonstrated to fail when the rule is collapsed to "credit is positive" - which leaves liabilities correct and every asset account backwards, the failure mode a customer-only test would never catch | Met at this tier |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | A fifth tier that refuses floating point, and the hardest one: JavaScript has no exact numeric type to fall back on, so money is a `bigint` and an amount is parsed from the JSON source text rather than from `JSON.parse`'s double. The scale table carries JPY at 0 and BHD at 3 so a hard-coded 2 fails rather than passes | Met at this tier |
| **REQ-EDG-001** Every customer request is authenticated at the edge | WP-12 | The client that carries the token, and the reason it is only ever a bearer: the token is held in memory for the life of the tab and written to neither `localStorage` nor `sessionStorage`, never logged and absent from the DOM once sign-in completes. Four tests hold that line | Met at this tier |
| **REQ-EDG-002** Every request is traceable end to end | WP-12 | An `X-Correlation-Id` is generated per request rather than left for the gateway to mint, so the identifier a customer would be asked to quote is one this application knows. A rejected transfer shows the `correlationId` from the problem document beside the failure | Met at this tier |

---

## WP-10a - customer-master: build, schema and PL/SQL

Ticket TB-1010. Stratum 1, Java 8 and Oracle. 73 tests, of which 43 run against real Oracle 23ai
Free through Testcontainers. The SOAP endpoint and the WAR on Tomcat 8.5 are WP-10b, so the two
requirements that turn on the interface are met in part here and completed there.

### Owned by WP-10a

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-CM-001** Customer and account metadata have a single system of record | One Oracle schema holding `customer`, `account` and `applied_transfer`, with the numbering series for both references as sequences - `customer-master` owns onboarding, which is what lets the ledger accept an account reference the caller supplies rather than allocating one. The canonical patterns are **check constraints**, not Java validation, so a second application or a correction script at two in the morning is held to them too. Identity lives here and nowhere else: `canonical-v1.xsd` gives the estate a `customerRef` and no other field about a person. The component also holds its own balances, which is a duplication the contract forces and `batch/recon` exists to find - see [ADR 0010](../governance/adr/0010-customer-master-holds-its-own-balances.md) | `SchemaTest` asserts against the data dictionary rather than against the script it just read: `booked_balance` is `NUMBER(15,0)`, and a non-canonical account reference, an undefined status, an undefined account type, an account belonging to nobody and a movement predating the account are each refused **by Oracle**. Demonstrated to fail by deleting one check constraint, which failed exactly the one test | Met |
| **REQ-EST-001** Stratum 1 is authentically dated in style and stack | Java 8 with JavaBean accessors and no lambda where an anonymous class was the idiom; JUnit 4; Maven 3 inheriting a corporate parent POM; business logic in PL/SQL packages and **no SQL in the Java at all**, so the DAO calls a procedure and maps what comes back. The pin is enforced rather than described - `maven-enforcer-plugin` refuses any JDK outside `[1.8,1.9)` and is bound in `<plugins>` so a child cannot inherit the parent and skip it | `ToolchainTest` asserts the JVM that ran the suite reports `1.8` **and** that the class library is Java 8's, by proving `java.util.List.of` is absent - `-source 1.8` on a newer JDK compiles against a newer class library and the property alone would not notice. `mvn validate` on the parent exits 0 under JDK 8 and 1 under JDK 17, naming the legacy-strata rule | Met in part - WSDL-first JAX-WS and the WAR on Tomcat 8.5 complete it in WP-10b |
| **REQ-CM-002** The interface is contract-first SOAP | Not yet. WP-02 discharged the contract half; the endpoint is WP-10b | - | Contract |

### Contributed by WP-10a, verified by the owning package

| Requirement | Owner | What WP-10a contributes | Status |
|---|---|---|---|
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | The sixth tier to refuse floating point, in the dialect that would most readily have accepted it. `booked_balance` is `NUMBER(15,0)` - a signed count of minor units matching `AmountMinorType` and `PIC S9(13)V99 COMP-3` - never `NUMBER(15,2)`, which would put the scale in a second place that can disagree with the currency. `Money` pairs the amount with its ISO 4217 code and refuses to be constructed without one, and `PKG_POSTING` raises rather than adding minor units of one currency to a balance held in another, because no tier in this estate converts | `SchemaTest.bookedBalanceIsFifteenDigitsOfMinorUnits` reads the precision and scale from `user_tab_columns`; `AccountDaoTest.keepsTheSignOfAnOverdrawnBalance` proves the sign survives the round trip, and `PkgPostingTest.raisesWhenALegIsInAnotherCurrency` proves the refusal | Met at this tier |
| **REQ-LED-004** Account type determines sign convention | WP-06 | The third independent implementation of the rule, after the ledger's Java and `web-banking`'s TypeScript, and the first in PL/SQL. `signed_effect` decides from the account type, not from the word "debit": a `LIABILITY` falls on a debit and an `ASSET` rises on one | `PkgPostingTest` asserts both directions, and the naive rule was planted - `v_debit_is_positive := FALSE` - which failed exactly `aDebitIncreasesAnAsset`, with the sign reversed. Written that way the package balances perfectly and reports half the estate's money backwards | Met at this tier |
| **REQ-INT-004** Duplicate delivery does not duplicate a movement | WP-11 | The receiving half. `PKG_POSTING.apply_transfer` claims `applied_transfer` with an `INSERT` and catches `DUP_VAL_ON_INDEX`; the primary key is the mechanism rather than a note about one, so two simultaneous deliveries race and the loser applies nothing | `PkgPostingConcurrencyTest` runs eight deliveries at once and asserts exactly one believed it was first. Proved to have teeth by mutation: a `SELECT`-then-`INSERT` version passes all fourteen sequential tests in `PkgPostingTest` and fails this one - the same defect, in the same shape, as the ledger's `ON CONFLICT DO NOTHING` | Met at this tier |
| **REQ-DP-001** All test data is synthetic | WP-03 | The only source of a name, a date of birth or a national identifier in this repository, and it lives in **test** scope so that code manufacturing personal data is not inside a deployable artefact. Values are constructed rather than chosen: a name carries its ordinal so no bare surname can occur, and an identifier is prefixed `SYN-`, a shape no authority issues | `SyntheticDataTest` asserts both shapes over 200 generated customers, plus determinism from the seed; `SyntheticSeedTest` proves the generator and the schema agree by seeding a real Oracle and reading every balance back | Met at this tier |

---

## WP-19 - web-banking design system

Ticket TB-1019. Stratum 4, TypeScript + React. A presentation change: **no requirement is added and
none changes owner.** What moved is the evidence, because the markup three requirements are verified
through was rewritten around them.

The regression gate is the whole of this package's claim to have changed nothing: 123 tests stood on
`main` before it and 123 stand after it, none edited, alongside 46 added here.

### Re-verified by WP-19, still owned by WP-14

| Requirement | What changed | What did not | Status |
|---|---|---|---|
| **REQ-UI-001** Customers can transfer between accounts | The journey gained a three-step indicator - Details, Confirm, Result - so the confirmation reads as a step rather than as the end. No focusable element was added ahead of the form, because `accessibility.test.tsx` walks the journey by counting tab stops | Every state, every transition and every message. `Transfer.test.tsx` and `accessibility.test.tsx` pass unedited | Met |
| **REQ-UI-002** Retrying a transfer cannot move money twice | Nothing. `transferAttempt.ts` was not opened | The key is still minted at confirmation and reused by every retry. `transferAttempt.test.ts` passes unedited | Met |
| **REQ-UI-003** Available balance is never presented as spendable when held | Available now leads the card and booked follows it, quieter and never absent, and a **balance meter** shows the held share of the booked balance as a proportion. The meter is an addition to the sentence, never a replacement: it renders nothing when the two balances agree, nothing when the account is overdrawn and there is no positive whole to take a share of, and it carries an accessible name stating the held amount, because a picture only a sighted reader can interpret would discharge the requirement for some readers and not others | Two labelled figures on every card, always; the held amount stated in words; a negative available balance printed honestly rather than floored. `Dashboard.test.tsx` passes unedited, and `BalanceMeter.test.tsx` adds six cases for the picture | Met |

### Introduced by WP-19

A control rather than a requirement, recorded here because it is how the presentation layer is held
to account and there is no other register for it.

| Control | What it does | Verified by |
|---|---|---|
| Colour contrast, computed rather than judged | `styles/contrast.test.ts` reads `styles/tokens.css`, computes WCAG 2.2 relative luminance for every declared colour, and asserts each declared pair clears 4.5:1 for text or 3:1 for a control edge. Every colour token must appear in a pair or in a list of exemptions with a stated reason, so a colour nobody thought about fails the build rather than shipping. The same shape as `money.source.test.ts`, aimed at the failure a stylesheet actually has: not a crash, but a colour a fifth of readers cannot make out, which nothing reports | Demonstrated to fail both ways - `--ink-muted` lightened to `#949494` failed three pairs, and an unpaired token failed the accounting test. It is why `--amber-500` fills the meter and carries no word: PKO's call-to-action amber reaches 2.6:1 on white |


---

## WP-10b - customer-master: SOAP endpoint, WAR and deployment

Ticket TB-1010. Stratum 1, Java 8 on Tomcat 8.5. **No requirement is added** - a package that
completes an interface satisfies the requirement that was already waiting for it, and inventing an id
for the second half of one package's work would put two ids on one obligation. What changes is that
the two requirements WP-10a could only meet in part are now met in full.

125 tests: the 118 that run in surefire, of which 43 need real Oracle, and 7 that deploy the WAR to a
real Tomcat 8.5 and call it over HTTP.

### Completed by WP-10b, owned by WP-10

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-CM-002** The interface is contract-first SOAP | `wsimport` runs over `contracts/wsdl/customer-master-v1.wsdl` **in place** - the WSDL imports the canonical schema by a relative path, so a copy without its sibling directory breaks generation - and produces `CustomerMasterPortType`. `CustomerMasterEndpoint` implements that generated interface, so it cannot compile unless it answers exactly the operations the contract declares. Nothing generated is committed. The generated tree compiles in its own execution with `[serial]` suppressed rather than the module's `-Werror` gate being weakened for the code a person wrote. SOAP 1.1, document/literal wrapped, and the awkward signature JAX-WS produces for `NotifyTransferPosted` - `void` plus two `Holder` out-parameters - was left as generated, because a binding customisation to make it prettier is the code deciding what the contract says. The 2011 reasoning is recorded in [ADR 0013](../governance/adr/0013-contract-first-soap-for-the-customer-master.md) | `GeneratedCodeTest` fails the build if a generated artefact appears under `src/main/java`, and asserts the generated port type carries the three operations the WSDL declares - demonstrated by planting an `ObjectFactory.java` in the source tree, which it named and refused. `CustomerMasterDeploymentIT` builds its client from the WSDL the running container publishes and asserts the authored `wsdl:documentation` prose survives to the wire: a WSDL the runtime derives from Java has the same operations, the same namespace and none of the sentences | **Met** |
| **REQ-EST-001** Stratum 1 is authentically dated in style and stack | The half WP-10a could not reach: a Servlet 3.0 descriptor with the JAX-WS RI's listener and servlet declared by hand, `sun-jaxws.xml`, the runtime shipped in `WEB-INF/lib` because Tomcat is a servlet container and has none, and the database arriving through a JNDI `resource-ref` so no connection string appears anywhere in the artefact. **JAX-WS RI 2.2.10, never 2.3.x**: JDK 8 carries the 2.2 API in `rt.jar`, on the bootclasspath, where no webapp classloader can override it - a 2.3 runtime against 2.2 interfaces fails at deployment with a `LinkageError` naming neither | `DeploymentDescriptorTest` holds four documents to the same strings - the address in the contract, the `url-pattern` in `sun-jaxws.xml`, the servlet mapping in `web.xml`, and the JNDI name the endpoint looks up - none of which a compiler checks. `CustomerMasterDeploymentIT` fetches Tomcat 8.5.100 into `target/`, deploys the WAR and calls all three operations, so "the WAR deploys" stops being inferred from a `mvn package` that succeeded. `BytecodeVersionTest` now also sweeps the generated classes | **Met** |

### Contributed by WP-10b, verified by the owning package

| Requirement | Owner | What WP-10b contributes | Status |
|---|---|---|---|
| **REQ-INT-001** Every interface is defined by a contract before implementation | WP-02 | The first implementation in this repository generated *from* its contract rather than checked against it afterwards. `SoapResponseConformanceTest` builds a validator from the WSDL's own inline schema, handed to the schema factory with the WSDL's location as its base URI so the `../xsd/` import resolves to the real file, and validates every response against it - including what `AccountMapper` actually produces, not only objects the test assembled | Met at this tier |
| **REQ-INT-004** Duplicate delivery does not duplicate a movement | WP-11 | The receiving half, now reachable over the wire. A redelivery of an identical `NotifyTransferPosted` answers `alreadyApplied=true` and moves no money | `CustomerMasterEndpointNotifyTest` and `CustomerMasterDeploymentIT` both send the message twice and assert the balance moved once. Proved to have teeth by mutation: hard-coding `alreadyApplied` to false failed exactly the redelivery assertion | Met at this tier |
| **REQ-DP-001** All test data is synthetic | WP-03 | The claim that identity does not cross the wire, asserted rather than read off the schema. Every family name, given name and national identifier in the fixture is checked absent from a successful answer **and** from a fault - the fault path explicitly, because an error path is the second most common place personal data escapes a system, which is why the WSDL says so where the fault is defined | `CustomerMasterEndpointReadTest.aSuccessfulAnswerCarriesNoIdentity` and `.aFaultCarriesNoIdentity` | Met at this tier |


---

## WP-11a - esb-adapter: the event, the transformation and the SOAP hop

Ticket TB-1011. Stratum 2, Java 8 on Spring Boot 2.7.18. 39 tests, of which 4 bring up a real Kafka,
a real Oracle and a real Tomcat 8.5 carrying `customer-master`'s own WAR.

WP-11 is **split in the plan** on the era boundary each half crosses. This half is 2019 to 2011;
**REQ-INT-003**, which is about the mainframe's own format, belongs to WP-11b and is untouched here.

### Owned by WP-11a

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-INT-004** Duplicate delivery does not duplicate a movement | **No de-duplication in this component, deliberately.** `NotifyTransferPosted` is idempotent on `transferRef` because the system of record claims the transfer with a unique constraint and answers `alreadyApplied`; the adapter treats that as success. A second record kept here would be a second source of truth about the bank's money, able to disagree with the first - which is the drift `batch/recon` exists to detect, manufactured on purpose. WP-11b needs its own answer, because a file has no unique constraint | `TransferBridgeIT.aRedeliveredEventMovesTheMoneyOnlyOnce` publishes the identical event twice against the really-deployed system of record and asserts the balance moved once; `TransferPostedListenerTest.aRedeliveryIsHandledAgainRatherThanSuppressed` pins the design by asserting the handler is called both times rather than the second being swallowed | **Met in part - a file has no unique constraint. Completed by WP-11b below** |
| **REQ-INT-005** Undeliverable messages are captured, not lost | Exactly two answers, and the distinction is the whole control. A **permanent** failure - malformed payload, schema violation, business fault - is recorded on the dead-letter channel and the offset acknowledged, so one bad message cannot block every transfer behind it. A **transient** failure is not acknowledged at all: the broker redelivers and the partition waits, which is what ordering costs and what WP-09 chose on the other side of this topic. An exception nobody classified counts as transient, because a bug in this component is not a reason to discard a payment. The channel is declared in `contracts/asyncapi/esb-adapter-events.yaml`, which this component owns | `TransferPostedListenerTest` covers all four routes including the unclassified one; `DeadLetterRecorderTest` validates the payload **against the contract itself**, read from `contracts/` at test time, and was demonstrated to fail by removing one required field; `TransferBridgeIT` proves a real dead letter reaches a real topic | **Met** |

### Contributed by WP-11a, verified by the owning package

| Requirement | Owner | What WP-11a contributes | Status |
|---|---|---|---|
| **REQ-CM-002** The interface is contract-first SOAP | WP-10 | The other side of the proof. `customer-master` generated its server interface from `customer-master-v1.wsdl`; this module generates a **client** from the same document, and neither is authoritative. Until WP-11a nothing had ever called that endpoint except a test written beside it | `TransferBridgeIT` calls the really-deployed WAR over HTTP with the generated client and asserts the balances in Oracle moved. A stub would have verified what this component says and never that the far end understands it | Met at this tier |
| **REQ-INT-001** Every interface is defined by a contract before implementation | WP-02 | Three contracts in one hop, none of them written by the code that uses them: the event is shaped by `ledger-events.yaml`, the transformation's output by `canonical-v1.xsd`, and the call by `customer-master-v1.wsdl`. The dead-letter channel was added to `contracts/` **before** the code that writes to it, in its own commit | `CanonicalTransformerTest` validates the transformation's output against the canonical schema and refuses an event one field short; `GeneratedCodeTest` fails the build if generated code is committed | Met at this tier |
| **REQ-EST-001** Stratum 1 is authentically dated in style and stack | WP-10 | Re-verified from the outside. The endpoint stratum 1 publishes is now consumed by a different module with a different toolchain, which is the only way to find out whether a 2011 SOAP service is genuinely callable rather than merely deployable | `TransferBridgeIT` | Met at this tier |
| **REQ-DP-001** All test data is synthetic | WP-03 | Test fixtures only, and the same shapes stratum 1 uses - `TESSERA-0001`, `SYN-0000000001`. The dead-letter path is also held to it from the other direction: the failure reason may never repeat the remittance reference, because a dead-letter topic is retained and widely readable | `DeadLetterRecorderTest.theReasonNeverRepeatsTheRemittanceReference` strips `originalPayload` and asserts the reference appears nowhere else in the record | Met at this tier |

---

## WP-11b - esb-adapter: COMP-3, the movement file and the overnight cycle

Ticket TB-1011. Stratum 2, Java 8 on Spring Boot 2.7.18. 77 unit tests and 5 integration tests, of
which one - `FourEraTransferIT` - brings up a real Kafka, a real Oracle, a real Tomcat 8.5 carrying
`customer-master`'s own WAR **and** runs the real GnuCOBOL overnight cycle.

The second half of the split. WP-11a was 2019 to 2011; this half is 2011 to 1995, and it is where
**REQ-INT-003** is finally satisfied rather than only contracted.

### Owned by WP-11b

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-INT-003** Modern events reach the mainframe in its own format | `Comp3` packs fifteen digits into eight bytes, high nibble first, `0x0C` for positive and zero, `0x0D` for negative, never `0x0F` and never a negative zero. `MovementRecord` lays those bytes into `MOVEREC`'s 120: `PIC X(n)` left-justified and space-padded, `PIC 9(n)` right-justified and zero-padded, never `0x00`, and **`MOV-AMOUNT` always positive with `MOV-DIRECTION` carrying the sign**, as the copybook's own header requires. A value that would not fit is refused rather than truncated, and so is a character with no single-byte form - both would produce a file that reads perfectly and says the wrong thing. The scale-2 rule is asserted here as well as at the SOAP hop, which is the defence in depth the decision log has described since WP-03 | `Comp3Test` holds the encoder to bytes it did not produce: the worked examples in the canonical data model as literals, **and** `ACCT-BOOKED-BAL` read straight out of `mainframe/data/out/ACCTMAST.DAT`, whose fixtures seed zero, the maximum representable balance, `-1250.00`, `-0.01` and `+0.01` for exactly this. Proved to have teeth by mutation: writing `0x0F` for positive fails 7 of 12. `MovementRecordTest` asserts a record it builds against a record the WP-03 generator wrote, **all 120 bytes**, and takes its field offsets from `contracts/check-copybook-offsets.py --json MOVEREC` rather than counting characters | **Met** |
| **REQ-INT-004** Duplicate delivery does not duplicate a movement | Completed for the file, which is the half WP-11a could not do. **The file is its own unique constraint**: under one exclusive lock the writer looks for `MOV-TRANSFER-REF` among the records already there - a seek at a 120-byte stride, over the key field only, never over the file as text - and appends only if it is absent. Deliberately **not** `alreadyApplied`: a process that dies between the SOAP call returning and the record landing would see the redelivery told "already applied", write nothing, and leave the mainframe short by that transfer for ever. See [ADR 0014](../governance/adr/0014-the-movement-file-is-its-own-unique-constraint.md) | `MovementFileWriterTest` asserts the file is byte-identical after a redelivery, and runs six deliveries of one transfer concurrently to assert it still holds exactly two legs - the case a sequential test misses, which is how WP-10a's read-then-write defect was found. `TransferBridgeTest.aTransferTheFarEndAlreadyHeldButTheFileDoesNotStillGetsWritten` pins the design: it is the only test that fails when the bridge is changed to trust `alreadyApplied`. `FourEraTransferIT` redelivers against the whole real estate | **Met** |
| **REQ-INT-005** Undeliverable messages are captured, not lost | Extended to the two stages WP-11a declared in the contract and could not yet reach. `ENCODE` is permanent - a currency of the wrong scale or an amount outside `S9(13)V99` will be exactly as wrong on redelivery. `WRITE` is transient by default, because a disk that was briefly unwritable is not a reason to discard a payment, with one permanent case: a movement file that is **already** not a whole number of records is refused rather than appended to, since adding good records to a corrupt file only moves the abend to 02:00 and another program's name. No contract change was needed - WP-11a declared both stages when it wrote the channel | `MovementFileWriterTest` covers both classifications and asserts a failed write leaves the file at its previous length; `TransferBridgeTest` asserts a JPY event reaches neither hop | **Met** |

### Contributed by WP-11b, verified by the owning package

| Requirement | Owner | What WP-11b contributes | Status |
|---|---|---|---|
| **REQ-MF-006** The end-of-day cycle is runnable and reproducible | WP-05 | Exercised from outside stratum 0 for the first time. The adapter writes a standalone file and hands its path to `run-eod.sh --movements`; it writes nothing into the cycle's work directory and does **not** sort, because `STEP010` does. Until now every run of the cycle was fed by the WP-03 generator | `FourEraTransferIT` runs the real cycle against a movement file this tier produced and asserts `REJECTS.DAT` is empty and the new master's balances moved by the same amount Oracle's did | Met at this tier |
| **REQ-MF-002** Money on the mainframe is packed decimal, not binary or text | WP-03 | The claim finally has two tiers in it. Until now `comp3.py` was the only implementation, so "packed decimal" was a statement about one program. A Java encoder built independently from the canonical model now produces the same bytes for the same values, including the ones that separate a correct encoder from a plausible one - zero, the maximum, and the amounts whose fifteenth digit shares the last byte with the sign | `Comp3Test` compares against the generator's own output rather than against its own arithmetic; `MovementRecordTest` compares a whole 120-byte record | Met at this tier |
| **REQ-MF-001** Record layouts are defined once and shared | WP-03 | Shared with a consumer outside stratum 0 for the first time, and read as data rather than transcribed. `MOVEREC` now has a writer in another language, another era and another build system, and it derives every offset from the copybook instead of restating it | `MovementRecordTest` fails naming the field if one is resized | Met at this tier |
| **REQ-INT-001** Every interface is defined by a contract before implementation | WP-02 | `contracts/check-copybook-offsets.py` gained a `--json` view - added **before** the writer that consumes it, in its own commit - so the layout the Java writer uses comes from the contracts checker rather than from a second hand count that happens to agree | `MovementRecordTest` derives every offset from that view; the checker's own self-test gained the `MOV-AMOUNT` placement and fails if the offset arithmetic changes | Met at this tier |
| **REQ-INT-007** Contract conformance is checked, not assumed | WP-02 | Conformance to a **fixed-width binary** contract, which nothing in the estate had checked from another language before. The check is a byte comparison against a file the owning tier produced, not a re-derivation of the layout | `Comp3Test`, `MovementRecordTest`, and `FourEraTransferIT` end to end | Met at this tier |
| **REQ-DP-001** All test data is synthetic | WP-03 | Every account, customer and remittance reference in the new tests is generated or invented; the dead-letter `reason` still never repeats the remittance reference, which is now reachable through `MOV-REFERENCE` as well | `DeadLetterRecorderTest.theReasonNeverRepeatsTheRemittanceReference`, unchanged and still passing | Met at this tier |

---

## WP-20 - workload model: the bank day as a contract

Ticket TB-1020. Stratum 4, Go 1.25, standard library only. 68 top-level tests across seven packages
and one command, none of which needs Docker, a database or a broker - a consequence of the design rather than a convenience,
since an engine that had to be run against something would not be an engine that performs no I/O.

The first package in the workload strand, and the one that makes the rest possible. The estate has
never been under load: every component was verified one request at a time, and the observability
WP-09, WP-12, WP-13 and WP-17 installed has never had anything to observe. This package declares
what demand looks like; WP-21 and WP-25 execute it.

### Owned by WP-20

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-PERF-001** Demand is described as a versioned model, not embedded in a tool | `contracts/workload/` carries a JSON Schema 2020-12 document and one committed model, `TB-WORKLOAD-DAY-V1`, landed **before** the engine that reads it. It names no host, no URL, no topic and no file: the two drivers that will consume it send to entirely different places, and a model that knew where to send a request would be half a driver. `contracts/check-workload-model.py` validates the model against the schema with a hand-written checker - standard library only, like the two checks either side of it - and then proves what a schema cannot: that the cohort shares add to 1 and yield whole customers, that every mix adds to 1, that the declared daily volume is what the population actually generates, and that the declared peak-to-trough is what the curve actually has. An **unrecognised JSON Schema keyword is a failure rather than a silent pass**, because a validator that ignores what it does not know agrees with a schema it never checked | `contracts/check-workload-model.py`, wired into `contracts/validate.sh`; demonstrated both ways against eight planted faults - shares that no longer sum, a flattened curve, an operation the ledger does not serve, a scale-3 currency, an undeclared field on a closed object, a schema growing a name field, an unimplemented keyword and an object left open; `model_test.go::TestDecodeRefusesADocumentItDoesNotFullyUnderstand` proves the loader refuses the same documents independently | **Met** |
| **REQ-PERF-002** A load run is reproducible from its recorded manifest | `TB-WORKLOAD-RUN-V1` carries the seed, the model digest, the git SHA, both dials, the window and what the run asks for. The digest is of the **decoded** model rather than of the file, so reindenting the document does not invalidate every run report before it while any change to what the model says does. Determinism rests on two choices stated in the code: `math/rand/v2`'s PCG, whose output is a specified function of its seed, and inverse-transform sampling over `Float64` rather than `ExpFloat64` or `NormFloat64`, whose ziggurat tables are an implementation detail. The population is seeded **per event** rather than per run, so a driver may fan the schedule across workers and still produce the day the manifest describes | `arrivals_test.go::TestTheSameSeedProducesAByteIdenticalSchedule` compares rendered bytes, not structs; `TestADifferentSeedProducesADifferentSchedule` and `TestADifferentDayProducesADifferentSchedule` pin the other side; `manifest_test.go::TestTheDigestChangesWhenTheModelDoes` changes the payday multiplier without touching the version; `population_test.go::TestADrawIsReproducibleAndIndependentOfOrder` | **Met** |

### Contributed by WP-20, verified by the owning package

| Requirement | Owner | What WP-20 contributes | Status |
|---|---|---|---|
| **REQ-INT-001** Every interface is defined by a contract before implementation | WP-02 | A sixth contract family, and the first that is an interface between a **plan and the tools that execute it** rather than between two running components. The schema and the model landed in their own commit, before any Go file existed - the same ordering `contracts/reporting/` was given by WP-17 | `contracts/workload/`, `contracts/check-workload-model.py`, and the commit order on this branch | Met at this tier |
| **REQ-INT-006** Business concepts are defined once and shared across eras | WP-02 | The model names no reference format of its own. All four are drawn to the patterns the ledger's OpenAPI document declares, and the test **reads those patterns from that document** rather than from a copy - the mistake F-64 records happening where requirement ids were transcribed instead. The operation mix is checked the same way, against the eleven `operationId`s, so a model cannot name an operation the estate does not serve. There are four formats and they are genuinely different; a single reference helper applied to all four is the trap, and three of the four would then be refused at the far end | `population_test.go::TestEveryReferenceMatchesThePatternInTheContract` over 20 000 draws; `TestTheFourReferenceFormatsAreGenuinelyDifferent`; `TestEveryOperationIsOneTheLedgerServes`; `check-workload-model.py::declared_operations` | Met at this tier |
| **REQ-INT-007** Contract conformance is checked, not assumed | WP-02 | Both halves, kept apart on purpose and made **independent**. The Python checker says the document is a coherent model; `internal/model` repeats two of its cross-checks in Go and refuses a model whose population does not generate its declared volume - because WP-21 will load a model from wherever it is pointed, and nothing guarantees that file went through `validate.sh`. Unknown fields are refused rather than ignored: a mistyped field would otherwise load at its zero value and the run report would describe a day nobody asked for | `contracts/check-workload-model.py`; `model_test.go::TestDecodeRefusesAModelWhoseOwnNumbersDisagree`, `::TestDecodeRefusesADocumentItDoesNotFullyUnderstand` | Met at this tier |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | `int64` minor units with an ISO 4217 code, in the third language where the rule matters, and enforced against the **source** rather than asserted about. The scanner is an AST walk rather than a regex, so a `/` in a string cannot be mistaken for a division: the money package may name no float, carry no fractional literal and perform no division or multiplication; no file in the module may name `float64` without a recorded reason; and exactly one **function** may convert between a float and an amount. `Add` refuses overflow rather than wrapping, the same decision WP-06's `Money` took three strata away. Currency-aware in the estate's own sense too - `PIC S9(13)V99 COMP-3` is scale 2, so JPY and BHD are refused in three independent places, each stating the reason rather than carrying a bare list | `money_test.go`; `source_test.go::TestNoFloatReachesAnAmount` over every file in the module, with nine planted faults it must refuse and two legitimate cases it must allow; `TestEveryJustifiedFileStillExists` and `TestEveryMoneyExemptionStillNamesARealFunction` stop either allowlist outliving what it excuses; `check-workload-model.py` against a planted `JPY` | Met at this tier |
| **REQ-DP-001** All test data is synthetic | WP-03 | Stronger than synthetic: **structurally incapable of being otherwise**. The schema has no field a name, an address or an identifier could be written into, and the checker refuses a schema that grows one - a denylist of property names checked against the schema itself, every object closed, every string bounded by an `enum`, a `pattern` or a stated prose allowance. A customer in the engine is an index and a pseudonymous reference. The manifest is checked the same way, against its actual generated output rather than by assertion about intent, which is what `data-classification.md` asks for | `check-workload-model.py::check_schema_cannot_carry_personal_data`, demonstrated against a planted `holderName`; `manifest_test.go::TestTheManifestCarriesNothingResemblingPersonalData` | Met at this tier |

---

## WP-16 - recon: the two cores compared every morning

Ticket TB-1016. Spans strata 0 and 3, Python 3.12 under `uv`. 81 tests, of which six bring up a real
PostgreSQL with the ledger's own Flyway migrations **and** run the real GnuCOBOL overnight cycle,
then reconcile the master that cycle produced against the ledger that fed it.

The package that makes the strangler fig survivable. Every other package moves money between the
eras; this is the one that checks the eras still agree afterwards, and it became possible only when
WP-11b made a single transfer reach both cores.

### Owned by WP-16

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-REC-001** Old and new cores are reconciled every cycle | A match-merge over two ordered inputs: `ACCTREC` decoded from the master the cycle produced, and the ledger's balances **summed from postings** at an `audit_record.seq` per [ADR 0009](../governance/adr/0009-reports-are-cut-at-an-audit-position.md) - never read from the `balance` table, which would compare the mainframe against a cache of the thing being checked. Read-only against both cores, enforced by PostgreSQL rather than by intention: the role is granted `SELECT` and nothing else. The COMP-3 decoder is a **third** independent implementation, after `comp3.py` and `Comp3.java`, because a decoder that imported one of them would inherit whatever it has wrong - and a reconciliation is the last place in the estate where agreement by construction is acceptable | `test_end_to_end.py` seeds the ledger, runs the **real** `run-eod.sh`, and reconciles the produced master to zero breaks; `test_the_reconciliation_wrote_nothing_to_either_core` re-reads both afterwards; `test_ledger.py::test_the_reader_cannot_write` asserts `InsufficientPrivilege` from a `SELECT`-only role; `test_comp3.py` holds the decoder to the canonical model's worked examples as literals **and** to balances read out of `ACCTMAST.DAT` | **Met** |
| **REQ-REC-002** Timing differences are distinguished from genuine drift | **The cut-off is the movement file, not a timestamp** - [ADR 0015](../governance/adr/0015-the-cut-off-is-the-movement-file.md). The set of `MOV-TRANSFER-REF` values in the file the cycle consumed decides what the master ought to hold; the ledger reader returns `booked_minor` and `expected_minor` side by side, and an account matching the second but not the first is `TIMING` rather than drift. Exact, with no window and no tolerance, and reproducible from the two files for ever. The scan is a seek at a 120-byte stride over the key field only - `MOV-REFERENCE` is free text a customer controls and may quote a transfer reference, which is [ADR 0014](../governance/adr/0014-the-movement-file-is-its-own-unique-constraint.md)'s trap from the reading side | `test_end_to_end.py::test_a_post_cut_off_movement_is_timing_not_drift` posts two transfers, lets only one reach the cycle, and asserts `TIMING` on both affected accounts with `VALUE_DRIFT` at zero; `test_compare.py::test_drift_wins_when_the_master_matches_neither_figure` pins the other side, so widening timing to absorb a real break fails; `test_cutoff.py::test_it_reads_the_key_field_only_never_the_file_as_text` plants a transfer reference inside a remittance reference | **Met** |
| **REQ-REC-003** Breaks are investigated, never auto-corrected | Enforced by the **shape of the code** rather than by discipline. `compare` takes two lists and returns breaks: it holds no connection, no file handle and no path, so there is nowhere for a correction to be written even by accident. A future change that wanted to auto-heal would have to add a writer to that module first, which is a diff a reviewer notices. `TIMING` is reported rather than suppressed, because a difference that is invisible cannot be confirmed as understood | `test_compare.py` drives every classification with no database present at all - the module cannot reach one; `test_the_reconciliation_wrote_nothing_to_either_core` proves it at the other end, on the real master and the real ledger | **Met** |

### Contributed by WP-16, verified by the owning package

| Requirement | Owner | What WP-16 contributes | Status |
|---|---|---|---|
| **REQ-INT-001** Every interface is defined by a contract before implementation | WP-02 | `contracts/recon/break-report-v1.md` and its checker landed **before** the writer that emits the format, in their own commit. The break report crosses a stratum boundary - `legacy/backoffice` renders it from stratum 1 - so it is a contract artefact rather than a README section, which is the lesson F-34 and F-51 both record about surfaces that are only described where the producer happens to live | `check-break-report.py`, wired into `contracts/validate.sh`; `test_report.py` parses the contract's own tables at test time and holds the document to them, so the writer is checked against the contract rather than against a transcription of it | Met at this tier |
| **REQ-INT-007** Contract conformance is checked, not assumed | WP-02 | Both halves of the claim, kept apart on purpose. The checker says the document is a coherent format - no field declared twice, no undeclared type, every classification reachable. The component's own test says the writer implements it. Demonstrated to have teeth by mutation: dropping a classification, removing `differenceMinor`'s nullability and introducing an undeclared type each fail the checker naming the defect | `contracts/check-break-report.py`, `test_report.py::test_the_document_matches_the_contract_at_every_level` | Met at this tier |
| **REQ-LED-004** Account type determines sign convention | WP-06 | A **fifth** independent statement of the normal-balance rule, after the Java domain, the reconciliation SQL, `batch/reporting` and `ACCTPOST`. Deliberate rather than careless: a reconciliation that asked the ledger which way its own figures ran would be reconciling nothing, and one that borrowed the reporting job's answer would agree with whatever that job has wrong. An unknown account type is refused rather than defaulted to a side | `test_accounting.py` pins all five types with hand-computed figures that no shared code path can fake, and asserts an unrecognised type raises rather than guessing | Met at this tier |
| **REQ-MF-001** Record layouts are defined once and shared | WP-03 | `ACCTREC` now has a reader outside stratum 0, and it derives every offset from `contracts/check-copybook-offsets.py --json` rather than transcribing them - the view WP-11b added, consumed here by a second tier in a second language. The test fails naming the field if one is moved or resized | `test_master.py::test_every_offset_comes_from_the_contract`, demonstrated by moving `ACCT-BOOKED-BAL` one byte and watching it fail by name | Met at this tier |
| **REQ-MF-002** Money on the mainframe is packed decimal, not binary or text | WP-03 | The claim now has three tiers in it rather than two. A Python decoder built independently of both existing implementations reads the same bytes to the same values, including the cases that separate a correct decoder from a plausible one - zero, the maximum representable balance, and the `+1`/`-1` pair whose fifteenth digit shares the last byte with the sign | `test_comp3.py`, against the generator's own output rather than against its own arithmetic | Met at this tier |
| **REQ-MF-005** Every batch run produces balancing control totals | WP-04 | Extended past stratum 0. `accountsCompared` must equal `accountsMatched` plus `accountsBroken` and the writer **refuses to emit a report where it does not**, because a control total nothing verifies is decoration. `totalAbsoluteDriftMinor` sums absolute values, since equal and opposite errors on two accounts is the most alarming shape a reconciliation can take, not the least | `test_report.py::test_totals_that_do_not_balance_are_refused`, `test_compare.py::test_absolute_drift_does_not_cancel` | Met at this tier |
| **REQ-MF-006** The end-of-day cycle is runnable and reproducible | WP-05 | Exercised from a third tier, and for a purpose the cycle was not built to serve. WP-11b ran it to prove a transfer arrives; this runs it to prove the arrival was *correct*, which is the first time anything has checked the cycle's arithmetic from outside stratum 0 | `test_end_to_end.py` runs the real cycle for every case and refuses a run that abends or produces rejects | Met at this tier |
| **REQ-OPS-001** Every scheduled process has a runbook | WP-05 | [`reconciliation-break.md`](../runbooks/reconciliation-break.md) filled from the stub that has named WP-16 as its owner since the foundation commit. Written against behaviour the tests assert: the classification table matches the contract's, the exit codes match `main.py`, and the "never rerun the job" instruction is a consequence of the reproducibility test rather than advice | The runbook's commands are the CLI's actual arguments; `test_end_to_end.py::test_the_same_cut_reconciles_identically_twice` is what makes the rerun instruction true | Met at this tier |
| **REQ-OPS-002** The service exposes business-level metrics and structured logs | WP-09 | Breaks are counted **by classification**, never as one number. An alert on "breaks > 0" would page somebody every morning for timing differences that are expected, which is the same failure as classifying them as drift arriving by a different route. Every classification gets a series even at zero, because a series that disappears when all is well cannot be charted - and the absence of the series and the absence of the job would look identical | `test_observability.py` asserts a series per classification including the zeroes, and that `tessera_recon_last_success` distinguishes a clean morning from a job that never ran | Met at this tier |
| **REQ-DP-001** All test data is synthetic | WP-03 | Every account, customer and transfer reference is generated or invented, and the master under test comes from the WP-03 generator at a fixed seed rather than from a committed binary fixture. The break report carries account references and balances and no remittance reference at all - the one field a paying customer controls has no place in a document the back office reads | The report's field list in `contracts/recon/break-report-v1.md` carries no free-text field; `test_report.py` asserts the document's fields are exactly the contract's | Met at this tier |

---

## WP-15 - backoffice: the operator's screens

Ticket TB-1015. Stratum 1, Java 8, JSP and JSTL with jQuery 1.7.2, deployed as its **own WAR** on
Tomcat 8.5 beside `customer-master`. 45 unit tests and 6 deployment tests, the latter on a real
Tomcat over real Oracle.

The package that gives the reconciliation somewhere to be read. WP-16 produces the break report and
this renders it; the seam between them is a contract rather than an assumption.

### Owned by WP-15

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-OPS-003** Operators can see and work reconciliation breaks | Two server-rendered screens. The break list reads `BREAKS-CCYYMMDD.json` per [`contracts/recon/break-report-v1.md`](../../contracts/recon/break-report-v1.md) - never the ledger, because giving a 2011 monolith a PostgreSQL connection would route around the layering the estate exists to demonstrate - and shows the control totals, the ledger cut and the transfer count the cut-off admitted. The rejects screen reads `REJECTS.DAT`, whose first 120 bytes are a whole `MOVEREC`, and **decodes the COMP-3 amount** so nobody reads packed bytes by eye. Both distinguish *no report* from *no breaks* in a warning box: those are the two states an operator must never confuse, and the second means nobody is checking. **A `TIMING` break is listed and offers no action** - it is expected, and inviting an operator to work one undoes what [ADR 0015](../governance/adr/0015-the-cut-off-is-the-movement-file.md) was for | `BackofficeDeploymentIT` deploys the built WAR to a real Tomcat 8.5 and reads both screens over HTTP: `aTimingBreakOffersNoAction` asserts the timing row has no button **and** that an actionable row does, so the check cannot pass by removing every button; `BreakReportReaderTest` refuses a wrong format id, unbalanced totals and an unknown classification; `RejectFileTest` takes its offsets from `contracts/check-copybook-offsets.py --json REJREC` | **Met** |
| **REQ-OPS-004** Operator actions are attributable and audited | **Stratum 1 had no audit trail until this package.** `V4__audit.sql` adds `operator_audit`, and `V5__pkg_operator.sql` adds the append-only trigger over it and `PKG_OPERATOR` - so the change and its audit row are one statement pair in one transaction, and there is no path to one without the other. A DAO that wrote the audit row itself would be one an application bug could skip. The acting user comes from `getRemoteUser()`, never from a form field: a hidden input naming the operator is a field anybody can edit, and a trail recording who the browser *said* it was is not attributable at all. Acknowledging is idempotent, because a double-click is one act and a trail that records two misleads whoever reads it later; re-annotating replaces the note and is itself audited, so the earlier text survives in the trail and nowhere else | `PkgOperatorTest` proves the trail cannot be updated or deleted (ORA-20010), that a rollback takes the audit row with the change, that a second acknowledgement is one act and one row, and that a `TIMING` break is refused at the database; `BackofficeDeploymentIT.acknowledgingABreakIsRecordedAndAudited` reads the actor back **out of Oracle** rather than off the screen that claims to have written it | **Met** |

### Contributed by WP-15, verified by the owning package

| Requirement | Owner | What WP-15 contributes | Status |
|---|---|---|---|
| **REQ-EST-002** The estate contains genuinely different UI eras | WP-01 | The other half of the demonstration, against `edge/web-banking`'s React. Server-rendered JSP with JSTL, jQuery 1.7.2 vendored into the WAR, a stylesheet with no framework and no build step, and markup that still works with scripting off. No npm, no bundler, no transpiler - and the two screens sit on one Tomcat with a SOAP endpoint, which is the shape the era actually had | The module builds with Maven alone and `BytecodeVersionTest` holds every class to Java 8 bytecode; `ToolchainTest` asserts the JVM and the class library | Met at this tier |
| **REQ-INT-001** Every interface is defined by a contract before implementation | WP-02 | The break report is consumed here exactly as `contracts/recon/break-report-v1.md` defines it, and neither side reads the other at run time. WP-16 held its writer to the contract; this holds its reader to the same document, which is what makes the two capable of disagreeing rather than agreeing by construction | `BreakReportReaderTest`, and `BackofficeDeploymentIT` end to end against a report written to that format | Met at this tier |
| **REQ-MF-001** Record layouts are defined once and shared | WP-03 | `REJREC` gains its first reader outside stratum 0, and it derives every offset from the contracts checker rather than transcribing them. The 120 bytes of `REJ-MOVEMENT` are read as a whole `MOVEREC`, which is the copybook's own claim about that field made executable | `RejectFileTest.everyOffsetComesFromTheContract` runs `check-copybook-offsets.py --json REJREC` and fails naming the field if one moves | Met at this tier |
| **REQ-MF-002** Money on the mainframe is packed decimal, not binary or text | WP-03 | A **fourth** independent COMP-3 implementation, after `comp3.py`, `Comp3.java` and `recon/comp3.py`. Deliberate for the reason the reconciliation gives: a decoder that borrowed another tier's would inherit whatever that tier has wrong. Held to the canonical model's worked examples as literals, and the decoded value is rendered through `BigDecimal` - money is never a floating-point number, on a screen least of all | `Comp3Test` at stratum 1, including zero, the maximum, the unsigned nibble and both malformed cases | Met at this tier |
| **REQ-DP-001** All test data is synthetic | WP-03 | Every account, transfer and operator name in this module is invented. The break report carries account references and balances and **no remittance reference at all** - the one field a paying customer controls has no place in a document the whole back office reads, and the format does not have a field for it | The contract's field list carries no free-text customer field; `BackofficeDeploymentIT` seeds its own fixtures | Met at this tier |

---

## WP-21 - workload-driver: the online day at volume

Ticket TB-1021. Stratum 4, Go 1.25, standard library only. 74 new tests over six packages, none of
which needs an estate: the driver's own behaviour is exercised against `httptest` handlers, and the
estate itself is exercised by `workload/scripts/estate-up.sh`, which boots PostgreSQL, the ledger
and the gateway and drives a compressed bank day at them.

**The first package in this repository that puts the estate under load.** Everything before it was
verified one request at a time, and the observability WP-09, WP-12, WP-13 and WP-17 installed had
never had anything to observe. The driver behaves like a customer application rather than like a
load tool, and that distinction decides every design question in it: a load tool sends requests and
counts the ones that came back 200, while a client holds an idempotency key across a retry, treats a
lost response as an unknown outcome rather than a failure, backs off when it is told to, and reads
back what it created rather than a reference it invented.

### Owned by WP-21

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-PERF-003** Offered load is independent of the system's response | The scheduler releases every event at the time WP-20's schedule fixed for it and **never waits for a worker** - [ADR 0016](../governance/adr/0016-the-workload-model-is-open.md). A closed model throttles itself precisely when the estate slows down, so offered load falls exactly when the interesting thing is happening and the latency comes out flattering. The cost the ADR names is paid rather than dodged: concurrency is unbounded in principle, and the run *records* how far past the level it was sized for it went (`tessera_workload_in_flight_peak`, `OverExpected`) instead of capping it, because a pool that blocks the scheduler is a closed model wearing an open model's name. Latency is stamped from the **intended** send time in one function, so no path out of `Send` can quietly measure from somewhere else, and the scheduler's own lateness is published separately as `tessera_workload_schedule_lag_seconds` - the signal that says the driver rather than the bank fell behind | `runner_test.go::TestASlowEstateDoesNotReduceTheOfferedRate` gives every response a 50 ms delay and asks for 700 requests in one second: all 722 are sent, in 1.05 s, with 52 in flight at the peak - a closed model with four workers would take nine seconds; `TestNothingThrottlesWhenTheRunPassesWhatItExpected` sets the expected level to 2 and proves the excess is counted rather than enforced; `send_test.go::TestLatencyIsMeasuredFromTheIntendedSendTimeAndNotFromTheActualOne` freezes the clock and asserts the five seconds the request was late are in the figure; `TestADriverThatCannotKeepUpRecordsItsOwnLag` | **Met** |

### Contributed by WP-21, verified by the owning package

| Requirement | Owner | What WP-21 contributes | Status |
|---|---|---|---|
| **REQ-API-001** Money-moving requests are idempotent | WP-08 | The first exercise of the property from a client's side, at volume. One key per scheduled event, derived from the business date and the event's ordinal and **reused by every retry** of it: `send_test.go::TestARetryOfALostRequestCarriesTheKeyTheFirstAttemptUsed` answers the first attempt with a 500 and the second with the ledger's replay, then asserts both attempts carried the same key and that it is inside the contract's 16-to-64 characters. The live run shows the same thing from the other end - the driver counted 24 replays and the ledger's own `ledger_transfers_total{outcome="replayed"}` counted 24 | **Met** in WP-08 |
| **REQ-EDG-003** A slow dependency cannot exhaust the edge | WP-12 | A 429 is `refused` rather than `failed` and is **never retried immediately**: retrying into a rate limiter converts a working control into a stampede and measures the retry loop rather than the bank. The refusals are counted in their own column and reconciled as never having reached the ledger. The gateway's limiter buckets on subject and route class, which is why a token is minted per synthetic subject rather than once for the run - a population behind one token is one caller being throttled correctly | **Met** in WP-12 |
| **REQ-INT-007** Contract conformance is checked, not assumed | WP-02 | Every request the driver builds is checked against `contracts/openapi/ledger-core.yaml` **read at test time**: the method and path template of all ten drawn operations, and which of them the contract requires an `Idempotency-Key` on. A route the driver invents fails the test rather than becoming a `no-route` at the gateway during a run. The same idiom as `routing_test.go` at the edge, pointed the other way | **Met** in WP-02 |
| **REQ-OPS-002** The service exposes business-level metrics and structured logs | WP-09 | `tessera_workload_*` published on its own port, in the estate's naming (`tessera_<component>_<thing>_<unit>`) so that a dashboard reads the driver and the bank without translation. Hand-written, because this module carries no dependencies. Every label is bounded - operation, outcome, reason - and a test asserts no account or customer reference reaches one, which in a 1.2 million customer population is the difference between a metric and a monitoring outage | **Met** in WP-09 |
| **REQ-DP-001** All test data is synthetic | WP-03 | The driver invents nothing. Every account reference comes from the WP-20 population, including the treasury the funding is debited from - generated at the index one past the last customer, so it matches the canonical pattern, cannot collide with a customer the run draws, and is reproducible from the model alone. `request_test.go::TestNoRequestCarriesAnythingResemblingPersonalData` greps the bytes of every request body and path rather than asserting about intent | **Met** in WP-03 |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | Extended to the driver half of the module: the amount that goes on the wire is `int64` minor units and an ISO 4217 code, and the source scanner that enforces it now distinguishes the engine from the driver rather than being widened. A run happens in real time and a Prometheus exposition is float64 by specification, so the two calls the engine forbids - `time.Now` and `strconv.FormatFloat` - are permitted per file, with the reason recorded, and a staleness test removes the exemption when the file stops making the call | **Met** in WP-06 |

---

## WP-22 - ledger data volume: a production-shaped database

Ticket TB-1022. Stratum 3, Java 17. A new module, `services/ledger-loader`, and a new command in the
Go half, `workload-dataset`. **Neither is a component of the bank**: they sit in the category
`workload/` and `walkthrough.sh` already occupy, and nothing in the estate depends on them.

**Every query in this repository had only ever run against about three accounts.** The plans they get
at that size say nothing about the plans they get at a year of postings, and a recorded normal
captured against a fixture is a recorded normal of the fixture. This package is what makes WP-23's
baseline mean anything, and what gives **F-24** the evidence it asked for.

The loader is a Java module rather than a Go command because of what it must not restate. The audit
trail's canonical form is `AuditEntry`'s - what is hashed decides whether two different entries can
produce the same bytes, and a second implementation would agree with the first until the day it did
not. The sign convention is `AccountType.signedEffect`'s. The population still comes from WP-20, over
a pipe, so neither side draws the day twice.

### Owned by WP-23

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-PERF-005** Every service states its SLI, its objective and its error budget | `contracts/slo/` carries a JSON Schema 2020-12 document and one committed catalogue, `TB-SLO-CATALOGUE-V1`: eleven objectives and twenty-eight supporting signals over five components, each with the SLI, the target, the measurement window, the error-budget arithmetic and the reasoning for the number. [ADR 0012](../governance/adr/0012-slo-catalogue-boundary.md) decides that the objective is declared here and the alert configured elsewhere, and `check-slo-catalogue.py` enforces the line rather than trusting it - a denylist of alerting vocabulary fails the build if `burnRate`, `severity`, `receiver`, `dashboard` or `retention` ever appears as a field. The control the ADR actually commits to is the **bidirectional** one: every metric the estate emits owes an entry, and every entry names a metric the estate emits, read out of each component's own source rather than transcribed. Each SLI also declares **how its figure is computed**, so a report derives it instead of a tool restating it | `contracts/check-slo-catalogue.py`, wired into `contracts/validate.sh`; demonstrated both ways against seventeen planted faults - a metric renamed in code, a budget that no longer follows from its target, budget minutes that no longer follow from the fraction and the window, a `timeRatio` with no threshold, an account reference used as a label, `business_date` outside the batch tier, two objectives sharing an id, a metric emitted and uncatalogued, a catalogue entry for a metric nothing emits, an alerting field added to the schema, a JSON Schema keyword the shared validator does not assert, a component left with no objective, and five malformed computability selectors. `CatalogueScrapeTest` asserts the other half against a **real scrape**: every exposed name the catalogue carries for `ledger-api` is in `/actuator/prometheus`, and every threshold objective has a histogram bucket at the boundary it is stated over | **Met** |
| **REQ-PERF-006** Normal is recorded before it is needed | `workload/baselines/` holds the recorded normal and the conditions it was captured under. A WP-21 day driven through the gateway against a WP-22-loaded ledger - 40 001 accounts, 799 565 rows, dataset digest `747f4177`, chain head `d0c59134` - sending 34 323 requests in 45 s, reconciling 9 132 postings against the ledger's own counter exactly. `workload-report` generates the report from the manifest and the scrapes and **reads no clock**, so a rerun over the same inputs is byte-identical; the scrapes are committed because a report nobody can regenerate is one nobody can check. The measured number **F-27** has asked for since WP-09 is beside it: money movement peaks at about 790 postings a second and **does not move when a second ledger instance is added**, because `pg_advisory_xact_lock` lives in the database rather than in the JVM. What the baseline could not exercise is printed as `nothing happened` rather than omitted | `main_test.go::TestTheSameInputsProduceAByteIdenticalReport` compares rendered bytes over committed fixtures, not structs; `TestTheReportRefusesAManifestFromARunItCannotDescribe`; `evaluate_test.go` pins each way of computing an SLI, including that a counter is read as a **delta** rather than as the process lifetime, that a counter going backwards is not a negative count, and that more good events than valid ones is **refused** rather than reported as better than perfect; the committed report regenerates byte-identically from the committed manifest and scrapes; the figures and their conditions are in [`../architecture/estate-under-load.md`](../architecture/estate-under-load.md) | **Met** |

### Contributed by WP-23, verified by the owning package

| Requirement | Owner | What WP-23 contributes | Status |
|---|---|---|---|
| **REQ-OBS-002** Every service exposes a metrics endpoint | WP-09 | The ledger reports on the database that does its work - pool utilisation and acquire wait, per-table size, dead tuples and vacuum activity, and the two lock waits timed **apart** so that the audit chain's service-wide lock is never averaged into per-account contention. A test carrying `@AutoConfigureObservability` reads them out of a real scrape, because without it Boot leaves a `SimpleMeterRegistry` and a metrics test passes while verifying nothing (**F-32**) | **Met** |

### Owned by WP-22

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-PERF-004** Query cost is measured at production cardinality, not at fixture size | A bulk loader that turns a WP-20 population into 300 001 accounts and roughly five million postings over 250 business dates, written with `COPY` and committed **per business date** - `posting_entry_balances` is a `DEFERRABLE INITIALLY DEFERRED` constraint trigger fired for each row, so PostgreSQL queues one pending event per posting until commit and a year in one transaction is a queue that spills. Nothing is disabled to avoid it. The plans are then captured with `EXPLAIN (ANALYZE, BUFFERS)` for the statement page at its first page and at a deep cursor, the balance read, and both queries `batch/reporting` bounds a business date by - against **the busiest account the load actually produced**, whose reference and depth the manifest names, because an account planted to be deep would be a plan of the fixture this package replaces | `LoadedLedgerTest` loads into real PostgreSQL and then runs the ledger's own controls over what was written: `BalanceReconciliation.breaks()` is empty, `AuditChain.verify()` is empty, `pg_trigger.tgenabled` is `O` for every trigger, and a planted single-leg entry is still refused at commit with "does not balance"; `theSameStreamLoadedTwiceProducesTheSameDigest` compares a SHA-256 over every row in write order rather than row counts; the captured plans and the cardinality they were measured at are recorded in [`../architecture/query-plans-at-volume.md`](../architecture/query-plans-at-volume.md) | **Met** |

### Contributed by WP-22, verified by the owning package

| Requirement | Owner | What WP-22 contributes | Status |
|---|---|---|---|
| **REQ-LED-006** A materialised balance is reconciled against its postings | WP-07 | The first exercise of the control at a size where it could plausibly fail. `BalanceReconciliation` sums the postings in SQL, reimplementing the sign convention independently of the Java that wrote them, over 300 001 accounts and millions of postings rather than over three - and it is the loader's acceptance test rather than a check the loader performs on itself | **Met** |
| **REQ-AUD-001** The audit trail is append-only and tamper-evident | WP-09 | The chain is written by a second producer for the first time, and verified by the same `AuditChain` an operator would run. `ChainWriter` reuses `AuditEntry`'s canonical form rather than restating it, so a loaded row is the row the API would have left; a report joining on `subject_ref` cannot tell the two apart, which is the point of loading rather than of inventing | **Met** |
| **REQ-LED-002** An account may not go below its overdraft limit | WP-06 | Enforced on the bulk path, where nothing in the schema enforces it. The loader carries a running balance and asks `OverdraftPolicy.forbidden()` itself, so a drawn transfer that would take an account below zero is **counted and not written** - a loader that posted it anyway would leave a ledger full of rows `Transfer` would have rejected while every constraint remained enabled | **Met** |
| **REQ-DP-001** All test data is synthetic | WP-03 | The loader invents no reference. Accounts, customers, transfers, holds and the treasury all come from the WP-20 population, and the two references the loader does mint - the opening entries - are `TB` plus the day before the range, which is what keeps them from colliding with a drawn one | **Met** |
| **REQ-LED-003** Money is exact and currency-aware | WP-06 | `long` minor units from the stream to the `COPY` buffer. No `double` and no `BigDecimal` on the amount path, and the one place a balance moves consults `AccountType.signedEffect` rather than reproducing it | **Met** |

---

## WP-24a - failure injection: the scenario contract, the fixture and the recorded normal

Ticket TB-1024. A new contract pair under `contracts/workload/`, a sixth checker in
`contracts/validate.sh`, and four new packages in `workload/`. **None of it is a component of the
bank**: it extends the fixture WP-20 and WP-21 built, and nothing in the estate depends on it.

**Every measurement in this repository had been taken on a healthy estate.** WP-21 put it under load,
WP-22 gave it a production-shaped database and WP-23 declared what good looks like - and none of them
degraded it on purpose. This package declares what a condition is, makes the fixture capable of
showing one, and re-takes the recorded normal against an estate that finally has a broker in it.

The seven recorded signatures themselves are **WP-24c**, moved out on 2026-08-22 after four defects
in the new fixture invalidated three capture cycles and a fifth was left undiagnosed. What is
verified here is verified; what is not is named rather than implied.

### Owned by WP-24a

| Requirement | Design | Verified by | Status |
|---|---|---|---|
| **REQ-PERF-007** Degradation is exercised, not assumed | `contracts/workload/scenario.schema.json` and `tessera-scenarios-v1.json` declare `TB-SCENARIOS-V1`: seven conditions, each naming what it degrades, how the **fixture** produces it, when in the business day, the objectives it is expected to **move** and the objectives expected to stay **flat**. The two lists are what turn a signature from prose into an assertion, and the flat list is the load-bearing half - the interesting claim about a rate-limit storm is not that refusals rose but that `SLO-GATEWAY-AVAILABILITY` did not. [ADR 0017](../governance/adr/0017-a-scenario-is-its-own-contract.md) decides that a scenario is its own contract rather than a field on the day model, because every run manifest records the day model's digest and a model that changed because somebody added a fault is a baseline nothing can be diffed against. **Three of the seven move nothing this estate has an objective for**, and each carries a written reason rather than an empty list - an estate that cannot see a condition is a finding. `internal/injector` applies a condition to containers the fixture booted and processes it started, never to the estate's own code or configuration, and reports a condition it cannot produce as **uninjected with the reason** rather than failing - `SCN-CLOCK-SKEW` is one. `workload-report --scenario` judges a run against what its scenario declared, and "moved" is the catalogue's own threshold rather than a tolerance invented in the report | `contracts/check-workload-scenarios.py`, wired into `contracts/validate.sh`; **demonstrated both ways against fifteen planted faults** - an SLO id renamed in the catalogue and left stale in a scenario, the same objective in both lists, two scenarios sharing an id, an objective named twice in one list, a scenario that moves nothing and does not say why, `movesNothingBecause` on a scenario that expects movement, a parameter belonging to another condition, a required parameter unset, a condition dropped from the catalogue, a hold window running past the end of the day, an object left open, an unbounded string, a planted `holderName`, a planted `severity`, and a `oneOf` the shared validator does not assert. The Go half refuses independently: `scenario_test.go::TestDecodeRefusesADocumentItDoesNotFullyUnderstand` over nine mutations of the committed catalogue. `injector_test.go` runs the whole injector against a recorder, so `make test-workload` still needs no Docker; `signature_test.go` pins the verdict both ways, including a declared move that did not happen and a flat objective that moved, and refuses a catalogue digest the run was not executed under | **Partially met** - the catalogue, the checker, the injector and the report land here; the seven recorded signatures are WP-24c |

### Contributed by WP-24a, verified by the owning package

| Requirement | Owner | What WP-24a contributes | Status |
|---|---|---|---|
| **REQ-PERF-006** Normal is recorded before it is needed | WP-23 | A second capture, `workload/baselines/with-broker/`, beside the first rather than over it - `baseline.sh` now requires `--out-name`, because a baseline written over another leaves one report and two sets of conditions. It closes **F-79**: 150 000 customers over 355 dates, **300 001 accounts and 14 491 832 rows**, against WP-23's 40 001 and 799 565. It closes the broker half of **F-77**: `fraud-scoring` has figures for the first time, 23 588 events with both objectives met, and `SLO-LEDGER-OUTBOX-FRESHNESS` goes from *missed* at 140 s to met at **0** - which confirms that the missed objective was the fixture rather than the ledger. The manifest now records the **hardware** and, for a degraded run, the scenario and its catalogue digest; `New` refuses a blank hardware the way it already refused a blank commit | **Met** |
| **REQ-DP-001** All test data is synthetic | WP-03 | The scenario schema is *incapable* of carrying personal data, checked the same way WP-20's is: every object closed, every string bounded, and `check-workload-model.py`'s denylist of property names run against it - demonstrated against a planted `holderName`. Nothing the injector captures is a customer's | **Met** |
| **REQ-OPS-002** Every component exposes a metric surface | WP-09 | The fixture now scrapes three of them rather than two. `workload-run` writes a third `before`/`after` pair for the scorer, and `estate-up.sh` **asserts the outbox drained** rather than assuming it - the control that was missing when WP-23's baseline recorded a missed objective for two months without anybody noticing it was the fixture | **Met** |
