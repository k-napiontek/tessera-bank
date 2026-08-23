# Test strategy

What is tested, where, and to what standard - across seven toolchains and four technology eras, where
**"run the tests" means something different in each**.

The governing rule is [`../plan/PROTOCOL.md`](../plan/PROTOCOL.md)'s: **failing test first, then the
implementation, then the refactor.** A task with no test is a task that is not done. Everything below
is how that rule is applied where the toolchain changes underneath it.

---

## The levels

| Level | What it asserts | Where it runs here |
|---|---|---|
| **Unit** | One behaviour, no infrastructure | Every tier. `services/ledger-core` has no Spring context at all |
| **Property-based** | An invariant over generated inputs | The ledger domain, with jqwik |
| **Integration** | The component against the real thing it talks to | Testcontainers: PostgreSQL, Oracle, Kafka; a real Tomcat 8.5 |
| **Contract** | Implementation and published contract agree | Against the artefacts in [`../../contracts/`](../../contracts/README.md), in both directions |
| **System integration** | Several strata, one flow | `FourEraTransferIT`, and the overnight cycle end to end |
| **Acceptance** | The requirement, as an operator or customer would see it | The back-office screens, `web-banking`'s journey, the EOD report |

## Per stratum

| Stratum | Runner | Needs | What it actually proves |
|---|---|---|---|
| 0 `mainframe/` | Python 3 harnesses driving compiled COBOL | GnuCOBOL | The match-merge, the rejects, the report totals and the cycle, compared against **fixture files byte for byte** |
| 1 `legacy/` | JUnit with Maven surefire and failsafe | JDK 8, Docker | PL/SQL against **real Oracle**, and the WAR deployed to a **real Tomcat 8.5** by the test itself |
| 2 `integration/` | Spring Boot 2.7 test with Testcontainers | JDK 8, Docker | A Kafka event becoming canonical XML, a SOAP call and a COMP-3 record - against real Kafka, real Oracle and the real WAR |
| 3 `services/` | JUnit 5, jqwik, ArchUnit, Testcontainers | JDK 17, Docker | The domain without a framework, the persistence against **real PostgreSQL**, the API against its OpenAPI document |
| 4 `edge/` | `go test -race`, `pytest`, `vitest` | Go, uv, Node, Docker for one Kafka test | Table tests under the race detector; the scorer against a real broker; the web app against a **mocked gateway and no network** |
| `batch/` | `pytest` | uv, Docker | Reports and reconciliation against **real PostgreSQL with the ledger's own migrations applied** |
| `workload/` | `go test -race` | Go | The demand model and the drivers. A fixture, held to the same standard as the bank |

`make test` runs every one of them. The per-tier targets are in `make help`.

## Why so much of it runs against real infrastructure

**An in-memory database takes no `SELECT ... FOR UPDATE` row locks.** The ledger's concurrency
guarantee - N threads around a ring of accounts, total value conserved, no deadlock - would pass
against a substitute while proving nothing. The same reasoning repeats per stratum:

- **Oracle**, because a compatibility mode runs no PL/SQL, and the stored procedures are the stratum.
- **Tomcat 8.5**, because "it compiles" and "the WAR deploys" are different statements, and a missing
  listener class or an unbound JNDI name only shows up in the second.
- **Real Kafka**, because redelivery and manual acknowledgement are the behaviour under test.
- **The real overnight cycle**, because `batch/recon` reconciles against a master a real COBOL run
  wrote, not against a Python approximation of one.
- **The ledger's own Flyway migrations** in the reporting tests, because a reader proved against a
  hand-written schema is verified against a fiction, and keeps passing on the day a column changes.

The cost is honest: the suite needs Docker, two JDKs and GnuCOBOL, and the first Oracle pull is about
2 GB. That is the price of a test that would fail for the right reason.

## Property-based testing, and why examples are not enough

The ledger's invariants are universal statements: **every journal entry balances**, **money
arithmetic never silently wraps**, **an account's sign convention follows its type**. An
example-based test asserts them at the points somebody thought of, which is exactly where the code is
already correct.

jqwik generates the rest, including the amounts nobody would type: zero, the boundary of the packed
field, the currency with no minor unit at all. `REQ-LED-001` to `REQ-LED-004` are held this way, and
the reason it matters is in [`../architecture/canonical-data-model.md`](../architecture/canonical-data-model.md):
JPY has 0 decimals and BHD has 3, so any implementation carrying an assumed scale of 2 is wrong for
two real currencies and right for the examples.

## Contract testing

**Contracts are the source of truth**, so the test runs in both directions: the implementation must
satisfy the published artefact, and the artefact must describe something the implementation actually
does. `bash contracts/validate.sh` validates every family - OpenAPI, AsyncAPI, WSDL and XSD,
copybooks, and the four cross-era JSON contracts - and the components then hold themselves to them:
the gateway checks its route table against the OpenAPI document in both directions, the SOAP client
and server are both generated from the authored WSDL rather than from each other, and the adapter
validates its XSLT output against the canonical schema **before it moves**.

The rule that makes this work is the ordering: **the contract changes first, the implementation
second.** A contract updated to match what was built is not a contract, it is a transcript.

## Coverage

**There is no coverage threshold anywhere in this repository, and none is claimed.** No JaCoCo, no
`--cov` gate, no SonarQube quality gate. [`../../quality/README.md`](../../quality/README.md)
declares the rulesets a bank would operate and F-03 records that each lands with the package that
first needs it.

The position that replaces it, and it is a position rather than an excuse: **every requirement in
[`../compliance/traceability-matrix.md`](../compliance/traceability-matrix.md) resolves to a test that
would fail without the implementation.** That is a stronger statement than a percentage, because a
percentage is satisfiable by tests that assert nothing. Where a coverage gate would genuinely help -
telling you which of 80 000 lines nobody exercised - this repository is small enough that the
traceability matrix does the same job by hand.

## Test data

**Synthetic only, always** - [`data-classification.md`](data-classification.md) is binding and this
is where it bites hardest. Data comes from the generators in the repository, deterministic from a
seed, and never from anything resembling a real customer.

Two consequences worth naming, because both were discovered rather than designed:

- **Where a schema requires an identity, the fixture fills it with a marker rather than a
  manufactured person.** `customer-master`'s 2011 schema declares names and a national identifier
  `NOT NULL`; the seeder writes a constant. There is nothing to anonymise because there was never an
  identity - a stronger position than a well-anonymised one.
- **A fixture that does not read back what it planted will report a fault it never injected.** The
  incident exercise's first attempt announced 451 lost transfers when the fault had cost two, and
  both defects were in the fixture rather than in the bank. `INC-001` records it.

## Resilience and failure-injection testing

DORA's operational resilience pillar asks that resilience be **tested rather than assumed**, and that
is a distinct activity from the suites above - those prove the estate works, this proves what it does
when it does not.

| Exercise | What it does |
|---|---|
| Scenario injection | Seven declared conditions in [`../../contracts/workload/`](../../contracts/workload/README.md), each naming the objectives it should move **and the ones that must stay flat**. Captured under `workload/baselines/` |
| Migration under traffic | A schema change applied while money moves, measured rather than reasoned about - [`../runbooks/schema-change-under-traffic.md`](../runbooks/schema-change-under-traffic.md) |
| Soak | Growth over a long run, in the tables that grow |
| Recovery | The overnight cycle re-run, and the reconciliation used as the control that confirms recovery |
| The incident exercise | A real fault, worked through the documented procedure, with [INC-001](../incidents/INC-001-transfers-discarded-at-the-era-boundary.md) as the RCA |

**A recovery asserted without re-running the control is a recovery nobody measured.** That is the
standing rule for this class of test, and the exercise that produced it also produced its
counter-example: the fault reversed cleanly and **not one affected account cleared**.

---

## Rules a test here has to obey

Each of these is a mistake that was actually made in this repository, not a maxim.

1. **Never derive the expectation from the implementation.** The COMP-3 expectations are transcribed
   from the worked examples in the canonical model as literals. A test that recomputes them with the
   encoder's own arithmetic proves only that the implementation agrees with itself.
2. **Never let a checker take its expectations entirely from its own input.** It will then agree with
   every version of that input, including a wrong one.
3. **A test that needs the whole estate is worth writing anyway.** `FourEraTransferIT` boots real
   Kafka, real Oracle and a real Tomcat for one transfer, and it is the only test that can fail when
   the eras stop agreeing.
4. **Test and implementation land in the same commit.** Test-driven development produces this
   naturally; a separate "add tests" commit means they were written to fit.
5. **A green suite is not evidence that the control works.** Two halves of the reconciliation once
   agreed perfectly while drawing different days from one seed. The suite was green throughout.
