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
        build-mainframe build-services build-edge test-contracts test-mainframe test-services \
        test-edge lint-contracts lint-edge jdk17 docker go

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

build: build-mainframe build-services build-edge ## Build every tier with its native toolchain
	@echo "Nothing to build in legacy/, integration/ or batch/ - no source there yet."

build-mainframe: ## Compile the COBOL programs (GnuCOBOL, IBM dialect)
	@cobc -x -std=ibm -Wall -I mainframe/copybook \
		-o $(CURDIR)/mainframe/data/out/acctpost mainframe/cobol/ACCTPOST.CBL
	@cobc -x -std=ibm -Wall -I mainframe/copybook \
		-o $(CURDIR)/mainframe/data/out/eodrept mainframe/cobol/EODREPT.CBL
	@echo "OK    ACCTPOST and EODREPT compiled"

build-services: jdk17 ## Build the Java 17 tier
	@JAVA_HOME="$(JAVA17)" ./gradlew --quiet \
		:services:ledger-core:build :services:ledger-persistence:build :services:ledger-api:build

build-edge: go ## Build the Go edge components
	@go -C edge/api-gateway build ./...
	@echo "OK    api-gateway builds"

# --- test -------------------------------------------------------------------------------------

test: test-contracts test-mainframe test-services test-edge ## Run every tier's test suite
	@echo
	@echo "OK    every tier with tests passed"

test-contracts: ## Validate the contracts against the canonical data model
	@bash contracts/validate.sh

test-mainframe: ## Copybooks, COMP-3, synthetic data, the match-merge, the report and the cycle
	@sh mainframe/copybook/compile-check.sh
	@python3 mainframe/copybook/check-identity.py
	@python3 mainframe/data/test_comp3.py 2>&1 | tail -3
	@python3 mainframe/data/generate.py --seed 42 >/dev/null
	@python3 mainframe/data/check-records.py | tail -1
	@python3 mainframe/cobol/test-acctpost.py
	@python3 mainframe/cobol/test-eodrept.py
	@python3 mainframe/jcl/test-sortrec.py 2>&1 >/dev/null | tail -3
	@python3 mainframe/jcl/test-eod-cycle.py

test-services: jdk17 docker ## Ledger domain, persistence and API, the last two on real PostgreSQL
	@JAVA_HOME="$(JAVA17)" ./gradlew \
		:services:ledger-core:test :services:ledger-persistence:test :services:ledger-api:test

test-edge: go ## The api-gateway, under the race detector
	@go -C edge/api-gateway test -race ./...

# --- lint -------------------------------------------------------------------------------------

lint: lint-contracts lint-edge ## Run every tier's linters and quality gates
	@echo "No linter configured for the mainframe or Java tiers yet - see quality/ and follow-up F-03."

lint-contracts: ## OpenAPI, AsyncAPI and XML validators
	@bash contracts/validate.sh

lint-edge: go ## gofmt and go vet over the Go tier
	@test -z "$$(gofmt -l edge/api-gateway)" \
		|| (echo "gofmt would change:"; gofmt -l edge/api-gateway; exit 1)
	@go -C edge/api-gateway vet ./...
	@echo "OK    gofmt and go vet are clean"

# --- run --------------------------------------------------------------------------------------

eod: ## Run the overnight cycle locally against the synthetic data
	@python3 mainframe/data/generate.py --seed 42 >/dev/null
	@bash mainframe/jcl/run-eod.sh --business-date 20260818 --rerun

# --- plan -------------------------------------------------------------------------------------

status: ## Show what is done and what is next
	@cat docs/plan/STATUS.md

plan: ## Show the master plan
	@cat docs/plan/master-plan.md
