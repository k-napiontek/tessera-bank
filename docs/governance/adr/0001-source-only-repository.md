# ADR 0001 - Application source only, no deployment artefacts

**Status:** Accepted
**Date:** 2026-08-17
**Deciders:** Karol Napiontek

## Context

This repository exists to support DevOps practice. The obvious instinct is to put everything in it -
source, Dockerfiles, Compose files, Kubernetes manifests, Terraform, CI pipelines - so that it is
complete and runnable in one place.

That instinct produces a repository welded to one deployment approach. Once a `docker-compose.yml`
and a set of Helm charts exist, every experiment starts from them, and the deployment strategy
becomes a property of the application rather than a decision that can be made freshly. The intended
use is the opposite: **one realistic codebase, many independent deployment experiments**, each
approaching the same estate differently.

There is also a structural argument. In a real bank, application teams own their source and a central
platform team owns the pipeline templates and deployment tooling. Those live in different
repositories with different owners and different review paths. Collapsing them into one repository
misrepresents how the work is actually divided.

## Decision

This repository contains **application source, interface contracts, governance configuration and
documentation only**.

It does not contain: `Dockerfile`, `docker-compose.yml`, Kubernetes manifests, Helm charts, Terraform
or other infrastructure-as-code, or CI workflow definitions.

Packaging and deployment live in separate companion platform repositories that consume this one.

Governance configuration **does** live here - `CODEOWNERS`, pull request and issue templates, linter
and quality-gate configuration - because that mirrors the real division: the platform team owns the
pipeline, the application team declares its own standards for that pipeline to run.

Each tier keeps its native build tooling (Gradle, Maven, Go modules, uv, npm, Make), because that is
how the source is built regardless of how it is later packaged.

## Consequences

**Easier.** The same codebase can be deployed many ways without conflict. Each companion repository
is a genuine exercise rather than a variation on a committed default. The boundary between
application and platform concerns stays visible, which is itself instructive.

**Harder.** The repository cannot be run with a single command. Local execution requires installing
GnuCOBOL, two JDKs, Maven, Gradle, Go, uv, Node, PostgreSQL, Kafka, a JMS broker and Tomcat 8.5 by
hand. Documented in [`../../consuming-this-repo.md`](../../consuming-this-repo.md).

That friction is not an unfortunate side effect - it is the lesson. Feeling how painful a seven
-toolchain polyglot estate is to run by hand is exactly what makes the value of containerisation
concrete rather than theoretical.

**Also harder.** No CI means no green badge and no automated gate in this repository. Quality-gate
configuration is declared here and executed by the platform repositories.

**Committed to.** Any task that appears to require a deployment artefact must stop and ask rather
than adding one.

## Alternatives considered

**Everything in one repository.** Simplest to run, and the common choice for a demonstration project.
Rejected because it fixes the deployment approach at the moment the first manifest is committed, and
that approach is the thing being practised.

**Source plus a development-only Compose file.** Tempting: keep deployment out, but allow one Compose
file purely for local convenience. Rejected because a "development-only" Compose file is used for
deployment within a month, and because the friction it removes is pedagogically valuable here.

**Source plus CI workflows.** CI is arguably a source-adjacent concern and would give the repository
a visible green check. Rejected for now to keep the boundary clean and unambiguous; it can be
revisited without disturbing anything else, and this ADR would be superseded rather than edited.
