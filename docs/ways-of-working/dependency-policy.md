# Dependency policy

> **STUB.** Outline only. Filled by **WP-02**.

How third-party dependencies enter this estate, and what is done about the ones that go bad. This is DORA ICT third-party risk expressed at the level an engineer actually works at.

## Planned contents

- Sourcing: all dependencies via an internal proxy - see CE-003 in [control-exceptions.md](control-exceptions.md)
- Approved licences: Apache-2.0, MIT, BSD, EPL, OFL-1.1. Copyleft requires review; AGPL is refused
- **OFL-1.1 applies to font assets only**, added by WP-19 for the typeface `edge/web-banking`
  serves from its own origin. The licence is permissive for use and embedding; its one real
  condition is that the font may not be sold on its own and that a derivative may not keep the
  reserved name. Neither constrains anything this estate does
- Adding a dependency: ticket, review, recorded justification in the pull request
- Version pinning and committed lock files, per toolchain
- Software composition analysis on every build, and what to do with a finding
- Vendor component register for the third-party risk requirement
- Deliberately outdated components: see [../technical-debt.md](../technical-debt.md) - findings against those are expected output, not defects
