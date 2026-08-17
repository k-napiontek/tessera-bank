# Test strategy

> **STUB.** Outline only. Filled by **WP-06**.

What is tested, where, and to what standard - across seven toolchains and four technology eras, where "run the tests" means something different in each.

## Planned contents

- Test levels: unit, property-based, integration, contract, system integration, acceptance
- Per-stratum approach - COBOL fixture comparison, JVM unit and Testcontainers, Go table tests, pytest, frontend component tests
- Coverage thresholds and where they apply, and where they deliberately do not
- Property-based testing of the ledger invariants, and why examples are insufficient there
- Contract testing to prevent implementation drifting from the artefacts in contracts/
- Test data: synthetic generation only - see [data-classification.md](data-classification.md)
- Resilience and failure-injection testing, for the DORA operational resilience requirement
