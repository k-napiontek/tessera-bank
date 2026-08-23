# Tessera Bank - root build delegation
#
# This repository is polyglot by design: seven toolchains across four technology eras. There is
# deliberately no monorepo build system. Each tier builds with its own native tooling, exactly as a
# real polyglot organisation works, and this Makefile only delegates.
#
# Tiers with no code yet say so per tier. "Nothing here yet" and "nothing anywhere" are different
# statements, and conflating them is how a session concludes the repository is empty.

.DEFAULT_GOAL := help
.PHONY: help build test lint plan status eod \
        build-mainframe build-legacy build-integration build-services build-edge build-batch \
        test-contracts test-mainframe test-legacy test-integration test-services test-edge \
        test-gateway test-fraud test-web \
        test-batch test-reporting test-recon test-customer-master test-backoffice \
        lint-contracts lint-edge lint-batch lint-docs build-web lint-web \
        build-workload test-workload lint-workload test-quality \
        jdk8 jdk17 docker go uv node

# ---------------------------------------------------------------------------------------------
# Strata 1 and 2 need a JDK 8, and the reason is the whole point of those tiers: Java 8, Spring Boot
# 2.7 and Tomcat 8.5 are one immovable block. Two traps live here. A JDK 8 installed by anything but
# Homebrew sits under ~/Library/Java, which no fixed candidate list would find, so macOS is asked
# through java_home. And bare `mvn` on a machine with a newer Homebrew JDK resolves THAT one - the
# enforcer rule in the parent POM then fails the build, correctly but a long way from the cause, so
# every recipe below passes JAVA_HOME explicitly.
# ---------------------------------------------------------------------------------------------
JAVA8_CANDIDATES := \
	$(JAVA_HOME) \
	$(shell /usr/libexec/java_home -v 1.8 2>/dev/null) \
	/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
	/usr/lib/jvm/java-8-openjdk

JAVA8 := $(firstword $(foreach d,$(JAVA8_CANDIDATES),\
	$(if $(shell test -x "$(d)/bin/java" && "$(d)/bin/java" -version 2>&1 | grep -q '"1\.8\.' && echo y),$(d))))

# ---------------------------------------------------------------------------------------------
# Stratum 3 needs a JDK 17 and will not accept a substitute - see the pinned-stack rule in
# CLAUDE.md. Homebrew installs openjdk@17 keg-only, so it is not on PATH and /usr/bin/java is the
# macOS stub. Without this, gradlew fails with "Unable to locate a Java Runtime", which reads like a
# broken build rather than a missing prerequisite.
# ---------------------------------------------------------------------------------------------
JAVA17_CANDIDATES := \
	$(JAVA_HOME) \
	/opt/homebrew/opt/openjdk@17 \
	/usr/local/opt/openjdk@17 \
	/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home \
	/usr/lib/jvm/java-17-openjdk

JAVA17 := $(firstword $(foreach d,$(JAVA17_CANDIDATES),\
	$(if $(shell test -x "$(d)/bin/java" && "$(d)/bin/java" -version 2>&1 | grep -q '"17\.' && echo y),$(d))))

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------------------------------------
# The ledger's persistence tests run against real PostgreSQL through Testcontainers, and that is not
# negotiable: an in-memory database takes no SELECT ... FOR UPDATE row locks, so the concurrency test
# would pass against one while proving nothing. Without a daemon, Testcontainers fails deep in a stack
# trace that reads like a broken build rather than a stopped Docker.
# ---------------------------------------------------------------------------------------------
docker: ## Report whether the Docker daemon the persistence tests need is running
	@docker info >/dev/null 2>&1 \
		&& echo "Docker: $$(docker version --format '{{.Server.Version}}')" \
		|| (echo "Docker daemon is not running. The ledger persistence tests need it -"; \
		    echo "they run against real PostgreSQL via Testcontainers. Start Docker Desktop:"; \
		    echo "  open -a Docker"; \
		    exit 1)

# ---------------------------------------------------------------------------------------------
# Stratum 4 builds with the Go toolchain the module declares. Go downloads a newer toolchain by
# itself when GOTOOLCHAIN is left alone, so the only real prerequisite is that go exists at all -
# and its absence otherwise shows up as "command not found" from inside a recipe.
# ---------------------------------------------------------------------------------------------
go: ## Report which Go the edge tier will use
	@command -v go >/dev/null 2>&1 \
		&& echo "Go: $$(go version)" \
		|| (echo "No Go toolchain found. Stratum 4 needs one - see CLAUDE.md."; \
		    echo "  brew install go"; \
		    exit 1)

# ---------------------------------------------------------------------------------------------
# The Python half of stratum 4 is pinned to 3.12 and managed by uv, which fetches the interpreter
# itself. That is what "Python 3.12" means here in practice: not a python3.12 on PATH, but a version
# uv resolves from pyproject.toml, so the tier builds the same way on a machine that has never
# installed Python at all.
# ---------------------------------------------------------------------------------------------
uv: ## Report the uv that manages the Python tier
	@command -v uv >/dev/null 2>&1 \
		&& echo "uv: $$(uv --version)" \
		|| (echo "No uv found. The Python edge components need it - see CLAUDE.md."; \
		    echo "  brew install uv"; \
		    exit 1)

# ---------------------------------------------------------------------------------------------
# The TypeScript half of stratum 4 builds with whatever Node is on PATH, above the floor its
# package.json declares. Unlike uv, npm fetches no interpreter for itself, so an absent or ancient
# Node shows up as a syntax error inside a dependency rather than as a missing prerequisite.
# ---------------------------------------------------------------------------------------------
node: ## Report the Node the TypeScript tier will use
	@command -v node >/dev/null 2>&1 \
		&& echo "Node: $$(node --version), npm $$(npm --version)" \
		|| (echo "No Node found. web-banking needs one - see CLAUDE.md."; \
		    echo "  brew install node"; \
		    exit 1)

jdk8: ## Report which JDK 8 the legacy tier will use
ifeq ($(JAVA8),)
	@echo "No JDK 8 found. Strata 1 and 2 are pinned to Java 8 - see CLAUDE.md."
	@echo "  brew install --cask zulu@8"
	@echo "Or set JAVA_HOME to a JDK 8 already installed."
	@exit 1
else
	@echo "JDK 8: $(JAVA8)"
endif

jdk17: ## Report which JDK 17 the Java tier will use
ifeq ($(JAVA17),)
	@echo "No JDK 17 found. Stratum 3 is pinned to Java 17 - see CLAUDE.md."
	@echo "  brew install openjdk@17"
	@echo "Or set JAVA_HOME to a JDK 17 already installed."
	@exit 1
else
	@echo "JDK 17: $(JAVA17)"
endif

# --- build ------------------------------------------------------------------------------------

build: build-mainframe build-legacy build-integration build-services build-edge build-batch build-workload ## Build every tier with its native toolchain

build-mainframe: ## Compile the COBOL programs (GnuCOBOL, IBM dialect)
	@cobc -x -std=ibm -Wall -I mainframe/copybook \
		-o $(CURDIR)/mainframe/data/out/acctpost mainframe/cobol/ACCTPOST.CBL
	@cobc -x -std=ibm -Wall -I mainframe/copybook \
		-o $(CURDIR)/mainframe/data/out/eodrept mainframe/cobol/EODREPT.CBL
	@echo "OK    ACCTPOST and EODREPT compiled"

# Packaging a WAR must not need a database. Left to run the test phase, `package` starts Oracle and
# this target quietly acquires a Docker prerequisite it does not declare - so it skips tests, and
# `test-legacy` is the one target that runs them.
build-legacy: jdk8 ## Build the Java 8 tier - two WARs, customer-master and backoffice
# customer-master is INSTALLED rather than packaged: backoffice depends on the classes jar it
# attaches, and a jar in target/ is not on another module's compile classpath. There is no
# aggregator POM at this stratum - each module names the corporate parent - so the ordering is here.
#
# The corporate parent is installed first. A module finds it by relativePath when building itself,
# but resolving customer-master's descriptor OUT of the repository needs the parent to be in there
# too - which is the difference between "my parent" and "a dependency's parent", and it fails
# naming tessera-parent rather than the module that asked for it.
	@JAVA_HOME="$(JAVA8)" mvn --quiet -N -f platform/parent-pom/pom.xml install
	@JAVA_HOME="$(JAVA8)" mvn --quiet -DskipTests -f legacy/customer-master/pom.xml install
	@JAVA_HOME="$(JAVA8)" mvn --quiet -DskipTests -f legacy/backoffice/pom.xml package
	@echo "OK    customer-master and backoffice package as WARs"

build-services: jdk17 ## Build the Java 17 tier
	@JAVA_HOME="$(JAVA17)" ./gradlew --quiet \
		:services:ledger-core:build :services:ledger-persistence:build :services:ledger-api:build \
		:services:ledger-loader:build

build-workload: go ## Build the workload model engine and its planning tool
	@go -C workload build ./...
	@echo "OK    workload builds"

build-edge: build-web go uv ## Build the edge tier - Go, Python and TypeScript
	@go -C edge/api-gateway build ./...
	@echo "OK    api-gateway builds"
	@cd edge/fraud-scoring && uv sync --locked --quiet
	@echo "OK    fraud-scoring resolves against its lock file"

# ---------------------------------------------------------------------------------------------
# `npm run build` type-checks the whole project with tsc before Vite bundles anything. Vite strips
# types without reading them, so a build that skipped tsc would happily ship code that does not
# type-check - which is the entire value TypeScript is here for.
# ---------------------------------------------------------------------------------------------
build-web: node ## Type-check and bundle web-banking
	@cd edge/web-banking && npm ci --silent && npm run build --silent
	@echo "OK    web-banking type-checks and bundles"

build-batch: uv ## Build the batch tier - Python
	@cd batch/reporting && uv sync --locked --quiet
	@cd batch/recon && uv sync --locked --quiet
	@echo "OK    reporting and recon resolve against their lock files"

# --- test -------------------------------------------------------------------------------------

test: test-contracts test-quality test-mainframe test-legacy test-integration test-services test-edge test-batch test-workload ## Run every tier's test suite
	@echo
	@echo "OK    every tier with tests passed"

test-contracts: ## Validate the contracts against the canonical data model
	@bash contracts/validate.sh

# ---------------------------------------------------------------------------------------------
# The documentation checker's own tests, on fixture trees rather than on this repository: a checker
# proved only against a tree that happens to be clean says nothing about what it does when a link
# breaks. `lint-docs` below is the same tool pointed at the real thing.
# ---------------------------------------------------------------------------------------------
test-quality: ## The documentation checker's own tests
	@python3 quality/test-docs-check.py 2>&1 | tail -3

test-mainframe: ## Copybooks, COMP-3, synthetic data, the match-merge, the report and the cycle
	@sh mainframe/copybook/compile-check.sh
	@python3 mainframe/copybook/check-identity.py
	@python3 mainframe/data/test_comp3.py 2>&1 | tail -3
	@python3 mainframe/data/test_generate.py 2>&1 | tail -3
	@python3 mainframe/data/generate.py --seed 42 >/dev/null
	@python3 mainframe/data/check-records.py | tail -1
	@python3 mainframe/cobol/test-acctpost.py
	@python3 mainframe/cobol/test-eodrept.py
	@python3 mainframe/jcl/test-sortrec.py 2>&1 >/dev/null | tail -3
	@python3 mainframe/jcl/test-eod-cycle.py

# ---------------------------------------------------------------------------------------------
# The schema and the stored procedures run against real Oracle Database 23ai Free in a container.
# TD-005 accepts that Oracle is not distributable and calls for a substitute; this is it. A
# compatibility mode was the alternative and runs no PL/SQL at all, so the two things this stratum
# exists to reproduce - the dialect lock-in and the stored-procedure layer - would both have been
# reproduced by something else pretending. First run pulls ~2GB.
#
# `verify`, not `test`. The WAR is deployed to a real Tomcat 8.5 and called over HTTP, and that
# needs the WAR - which does not exist until `package`. Running only the test phase here would
# leave the one control that proves this component deploys behind a command nobody remembers, which
# is the shape of problem F-30 already records about the audit verifier. It costs a second Oracle
# container, because surefire and failsafe are separate JVMs, and a ~10MB Tomcat download the first
# time.
# ---------------------------------------------------------------------------------------------
test-legacy: test-customer-master test-backoffice ## Both stratum 1 modules

test-customer-master: jdk8 docker ## customer-master on real Oracle, and the WAR on a real Tomcat 8.5
	@JAVA_HOME="$(JAVA8)" mvn --quiet -N -f platform/parent-pom/pom.xml install
	@JAVA_HOME="$(JAVA8)" mvn -f legacy/customer-master/pom.xml install

# backoffice depends on customer-master's attached classes jar, so the module above must have been
# installed first - which is why test-customer-master runs `install` rather than `verify`.
test-backoffice: jdk8 docker ## backoffice, deployed as its own WAR on a real Tomcat 8.5
	@JAVA_HOME="$(JAVA8)" mvn -f legacy/backoffice/pom.xml verify

# ---------------------------------------------------------------------------------------------
# Stratum 2 shares stratum 1's JDK and none of its tooling: Java 8 with Spring Boot 2.7.18, which
# is the last Boot line that supports Java 8 and therefore the reason the whole block is frozen
# together. The tests consume from a real Kafka through Testcontainers, the same image the ledger's
# outbox contract test uses, so this needs Docker too.
# ---------------------------------------------------------------------------------------------
build-integration: jdk8 ## Build the Spring Boot 2.7 tier - esb-adapter
	@JAVA_HOME="$(JAVA8)" mvn --quiet -DskipTests -f integration/esb-adapter/pom.xml package
	@echo "OK    esb-adapter packages"

# build-legacy first, and not by accident: the end-to-end test deploys customer-master's real WAR
# rather than a stub, so the WAR has to exist. Naming the dependency here beats a test failure that
# says "no such file" about a path nobody recognises.
test-integration: jdk8 docker build-legacy ## esb-adapter, against real Kafka and a really-deployed customer-master
	@JAVA_HOME="$(JAVA8)" mvn -f integration/esb-adapter/pom.xml verify

test-services: jdk17 docker ## Ledger domain, persistence, API and bulk loader, the last three on real PostgreSQL
	@JAVA_HOME="$(JAVA17)" ./gradlew \
		:services:ledger-core:test :services:ledger-persistence:test :services:ledger-api:test \
		:services:ledger-loader:test

# workload/ is a fixture rather than a component of the bank, and it is the only tier that needs
# nothing installed beyond a Go toolchain: no Docker, no database, no broker. That is deliberate -
# an engine that had to be run against something would not be an engine that performs no I/O.
test-workload: go ## The workload model engine, under the race detector
	@go -C workload test -race ./...

test-edge: test-gateway test-fraud test-web ## Every edge component

test-gateway: go ## The api-gateway, under the race detector
	@go -C edge/api-gateway test -race ./...

test-fraud: uv docker ## fraud-scoring, including one test against a real Kafka
	@cd edge/fraud-scoring && uv run pytest

# ---------------------------------------------------------------------------------------------
# The web suite reaches no network. Every gateway response it sees is served by MSW at the fetch
# boundary, so the tests assert what the app sends and how it renders what comes back - and they run
# identically on a machine with no estate at all. The live journey is scripts/walkthrough.sh.
# ---------------------------------------------------------------------------------------------
test-web: node ## web-banking, against a mocked gateway - no network
	@cd edge/web-banking && npm ci --silent && npm test --silent

test-batch: test-reporting test-recon ## Every batch component

# ---------------------------------------------------------------------------------------------
# The reporting tests run against real PostgreSQL with the ledger's own Flyway migrations applied,
# for the same reason the ledger's own do: a reader proved against a hand-written schema is verified
# against a fiction, and it keeps passing on the day WP-07 adds a column.
# ---------------------------------------------------------------------------------------------
test-reporting: uv docker ## reporting, against real PostgreSQL with the ledger schema
	@cd batch/reporting && uv run pytest

# ---------------------------------------------------------------------------------------------
# recon reads both cores, so its suite needs both: real PostgreSQL for the ledger side and
# GnuCOBOL for the end-to-end run, which executes the WP-05 overnight cycle rather than a stand-in.
# ---------------------------------------------------------------------------------------------
test-recon: uv docker ## recon, against real PostgreSQL and the real overnight cycle
	@cd batch/recon && uv run pytest

# --- lint -------------------------------------------------------------------------------------

lint: lint-contracts lint-docs lint-edge lint-batch lint-workload ## Run every tier's linters and quality gates
	@echo "No linter configured for the mainframe or Java tiers yet - see quality/ and follow-up F-03."

lint-contracts: ## OpenAPI, AsyncAPI and XML validators
	@bash contracts/validate.sh

# ---------------------------------------------------------------------------------------------
# Documentation is checked by the build because four work packages closed over their own stubs and
# nothing noticed - F-17. Standard library Python, so there is nothing to install.
# ---------------------------------------------------------------------------------------------
lint-docs: ## Internal links, surviving stub markers and invented requirement ids
	@python3 quality/docs-check.py

lint-edge: go uv node ## gofmt and go vet over Go, ruff over Python, eslint over TypeScript
	@test -z "$$(gofmt -l edge/api-gateway)" \
		|| (echo "gofmt would change:"; gofmt -l edge/api-gateway; exit 1)
	@go -C edge/api-gateway vet ./...
	@echo "OK    gofmt and go vet are clean"
	@cd edge/fraud-scoring && uv run ruff check .
	@cd edge/fraud-scoring && uv run ruff format --check . >/dev/null \
		|| (echo "ruff format would change files in edge/fraud-scoring"; exit 1)
	@echo "OK    ruff is clean"
	@$(MAKE) --no-print-directory lint-web

lint-web: node ## eslint over web-banking, with type-aware rules
	@cd edge/web-banking && npm ci --silent && npm run lint --silent
	@echo "OK    eslint is clean over web-banking"

lint-workload: go ## gofmt and go vet over the workload engine
	@test -z "$$(gofmt -l workload)" \
		|| (echo "gofmt would change:"; gofmt -l workload; exit 1)
	@go -C workload vet ./...
	@echo "OK    gofmt and go vet are clean over workload"

lint-batch: uv ## ruff over the batch tier
	@cd batch/reporting && uv run ruff check .
	@cd batch/reporting && uv run ruff format --check . >/dev/null \
		|| (echo "ruff format would change files in batch/reporting"; exit 1)
	@cd batch/recon && uv run ruff check .
	@cd batch/recon && uv run ruff format --check . >/dev/null \
		|| (echo "ruff format would change files in batch/recon"; exit 1)
	@echo "OK    ruff is clean over batch/reporting and batch/recon"

# --- run --------------------------------------------------------------------------------------

eod: ## Run the overnight cycle locally against the synthetic data
	@python3 mainframe/data/generate.py --seed 42 >/dev/null
	@bash mainframe/jcl/run-eod.sh --business-date 20260818 --rerun

# --- plan -------------------------------------------------------------------------------------

status: ## Show what is done and what is next
	@cat docs/plan/STATUS.md

plan: ## Show the master plan
	@cat docs/plan/master-plan.md
