# Tessera Bank - root build delegation
#
# This repository is polyglot by design: seven toolchains across four technology eras. There is
# deliberately no monorepo build system. Each tier builds with its own native tooling, exactly as a
# real polyglot organisation works, and this Makefile only delegates.
#
# Targets are honest stubs until the relevant work package lands. See docs/plan/STATUS.md.

.DEFAULT_GOAL := help
.PHONY: help build test lint plan status

NOTHING_YET = "No source code has been written yet. See docs/plan/STATUS.md for the next work package."

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

build: ## Build every tier with its native toolchain
	@echo $(NOTHING_YET)

test: ## Run every tier's test suite
	@echo $(NOTHING_YET)

lint: ## Run every tier's linters and quality gates
	@echo $(NOTHING_YET)

status: ## Show what is done and what is next
	@cat docs/plan/STATUS.md

plan: ## Show the master plan
	@cat docs/plan/master-plan.md
