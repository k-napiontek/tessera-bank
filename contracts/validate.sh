#!/usr/bin/env bash
#
# Validate every contract in this directory. Runnable from a clean checkout.
#
# The Definition of Done depends on this script exiting 0. It proves each contract is well-formed
# and internally consistent - it does not prove the four contracts agree with each other. Only
# reading them beside docs/architecture/canonical-data-model.md proves that, which is why the work
# package asks for both.
#
# Needs: xmllint, python3, node and npm. The OpenAPI and AsyncAPI steps download their validators
# on first run, so the first invocation needs network access.

set -euo pipefail

cd "$(dirname "$0")/.."

failed=0

step() {
  printf '\n\033[1m== %s\033[0m\n' "$1"
}

record() {
  if "$@"; then
    return 0
  fi
  failed=$((failed + 1))
  return 0
}

step "XML - XSD and WSDL well-formedness"
record xmllint --noout contracts/xsd/*.xsd contracts/wsdl/*.wsdl
echo "  xmllint: $(ls contracts/xsd/*.xsd contracts/wsdl/*.wsdl | wc -l | tr -d ' ') documents checked"

step "OpenAPI 3.1"
record npx --yes @redocly/cli lint contracts/openapi/*.yaml

step "AsyncAPI 3.0"
if npx --yes @asyncapi/cli validate contracts/asyncapi/*.yaml 2>/dev/null; then
  :
else
  echo "  @asyncapi/cli could not be installed - falling back to @asyncapi/parser."
  echo "  See the header of contracts/check-asyncapi.mjs for why."
  workdir=$(mktemp -d)
  trap 'rm -rf "$workdir"' EXIT
  if npm install --prefix "$workdir" --silent --no-audit --no-fund @asyncapi/parser >/dev/null 2>&1; then
    cp contracts/check-asyncapi.mjs "$workdir/"
    record node "$workdir/check-asyncapi.mjs" "$PWD"/contracts/asyncapi/*.yaml
  else
    echo "  could not install @asyncapi/parser either - AsyncAPI is UNVERIFIED"
    failed=$((failed + 1))
  fi
fi

step "Copybook field offsets against the canonical model"
record python3 contracts/check-copybook-offsets.py

printf '\n'
if [ "$failed" -ne 0 ]; then
  printf '\033[1mFAILED\033[0m - %d check(s) did not pass\n' "$failed"
  exit 1
fi
printf '\033[1mOK\033[0m - every contract validates\n'
