# Consuming this repository

> **STUB.** Outline only. Filled by **WP-18, updated by each service package**.

For the companion platform repositories. Per tier: how it builds, what it needs at runtime, what it produces, and what shape its deployment takes. This repository deliberately ships no deployment artefacts - see [ADR 0001](governance/adr/0001-source-only-repository.md) - so this document is the handover.

## Planned contents

- Per tier: build command, toolchain version, artefact produced
- Runtime prerequisites: GnuCOBOL, JDK 8 and JDK 17, Maven, Gradle, Go, uv, Node, PostgreSQL, Kafka, a JMS broker, Tomcat 8.5
- Configuration surface per service: environment variables, secrets required, config files
- Health, readiness and metrics endpoints per service
- Startup ordering and dependencies between tiers
- Batch schedules that a scheduler must own: the EOD cycle and the morning reconciliation
- The Oracle-dialect substitution used locally, and what it means for stratum 1
