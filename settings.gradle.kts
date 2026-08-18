// Gradle build for stratum 3 only.
//
// This repository is polyglot by design - seven toolchains across four eras - and deliberately has
// no monorepo build system. Gradle covers services/ and nothing else; the other strata build with
// their own native tooling, exactly as a real polyglot organisation works. See the root Makefile.
rootProject.name = "tessera-bank"

include("services:ledger-core")
include("services:ledger-persistence")
