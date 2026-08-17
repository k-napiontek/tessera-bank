# Tessera Bank - root build delegation
#
# This repository is polyglot by design: seven toolchains across four technology eras. There is
# deliberately no monorepo build system. Each tier builds with its own native tooling, exactly as a
# real polyglot organisation works, and this Makefile only delegates.
#
# Tiers with no code yet say so per tier. "Nothing here yet" and "nothing anywhere" are different
# statements, and conflating them is how a session concludes the repository is empty.

.DEFAULT_GOAL := help
.PHONY: help build test lint plan status \
        build-mainframe build-services test-contracts test-mainframe test-services \
        lint-contracts jdk17

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

build: build-mainframe build-services ## Build every tier with its native toolchain
	@echo "Nothing to build in legacy/, integration/, edge/ or batch/ - no source there yet."

build-mainframe: ## Compile the COBOL programs (GnuCOBOL, IBM dialect)
	@cobc -x -std=ibm -Wall -I mainframe/copybook \
		-o $(CURDIR)/mainframe/data/out/acctpost mainframe/cobol/ACCTPOST.CBL
	@echo "OK    ACCTPOST compiled"

build-services: jdk17 ## Build the Java 17 tier
	@JAVA_HOME="$(JAVA17)" ./gradlew --quiet :services:ledger-core:build

# --- test -------------------------------------------------------------------------------------

test: test-contracts test-mainframe test-services ## Run every tier's test suite
	@echo
	@echo "OK    every tier with tests passed"

test-contracts: ## Validate the contracts against the canonical data model
	@bash contracts/validate.sh

test-mainframe: ## Copybooks, COMP-3 encoding, synthetic data, and the ACCTPOST match-merge
	@sh mainframe/copybook/compile-check.sh
	@python3 mainframe/copybook/check-identity.py
	@python3 mainframe/data/test_comp3.py 2>&1 | tail -3
	@python3 mainframe/data/generate.py --seed 42 >/dev/null
	@python3 mainframe/data/check-records.py | tail -1
	@python3 mainframe/cobol/test-acctpost.py

test-services: jdk17 ## Ledger domain unit and property tests
	@JAVA_HOME="$(JAVA17)" ./gradlew :services:ledger-core:test

# --- lint -------------------------------------------------------------------------------------

lint: lint-contracts ## Run every tier's linters and quality gates
	@echo "No linter configured for the other tiers yet - see quality/ and follow-up F-03."

lint-contracts: ## OpenAPI, AsyncAPI and XML validators
	@bash contracts/validate.sh

# --- plan -------------------------------------------------------------------------------------

status: ## Show what is done and what is next
	@cat docs/plan/STATUS.md

plan: ## Show the master plan
	@cat docs/plan/master-plan.md
