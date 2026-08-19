# Requirements traceability matrix

> **Partially filled.** The requirement catalogue below is complete - all 60 ids, each with its
> owning work package. The per-package sections exist only for packages that have been executed:
> WP-02 to WP-09, WP-12, WP-13 and WP-17. Every work package adds its own as part of the Definition of Done,
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
