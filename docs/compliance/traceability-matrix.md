# Requirements traceability matrix

> **Partially filled.** The requirement catalogue below is complete - all 60 ids, each with its
> owning work package. The per-package sections exist only for packages that have been executed:
> WP-02 to WP-08. Every work package adds its own as part of the Definition of Done,
> and WP-18 verifies that none is missing.

Requirement to design to code to test, for the whole estate. This is the artefact an auditor samples: every requirement must resolve to an implementation and to a test that would fail without it. Each work package updates it as part of its Definition of Done.

---

## Requirement catalogue

**The authority for requirement IDs.** Every `REQ-*` id in this repository is defined here, extracted
from the Traceability section of the work package that owns it. 60 requirements across
eighteen packages.

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
| **REQ-UI-003** Available balance is never presented as spendable when held | WP-14 | `Hold` is defined, and `availableBalance` is specified as booked less every hold still `PLACED` | Contract |

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
| **REQ-UI-003** Available balance is never presented as spendable when held | WP-14 | `Balance.available()` is derived from booked less every hold still `PLACED`, never stored, and reports a negative figure honestly rather than flooring at zero | Contract |

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
| **REQ-EDG-002** Every request is traceable end to end | WP-12 | `X-Correlation-Id` is echoed onto every Problem document when the gateway sends one, and never invented here - a correlation id minted per tier correlates nothing | Contract |

---
