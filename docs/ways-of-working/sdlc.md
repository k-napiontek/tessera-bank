# Software development lifecycle

> **STUB.** Outline only. Filled by **WP-18**.

The lifecycle a change travels through, gate by gate, and the evidence each gate produces. This is the document that answers an auditor asking "show me how a change gets to production".

## Planned contents

- Change request raised with a ticket ID; risk classified standard / normal / major / emergency
- Design gate: ADR if architecturally significant, architecture review if major
- Implementation gate: branch, test-driven development, Definition of Done self-check
- Review gate: four-eyes via CODEOWNERS - see [control-exceptions.md](control-exceptions.md)
- Quality gate: coverage thresholds, static analysis, software composition analysis
- Security review trigger: authentication, money movement, or personal data
- Release gate: promotion through the environment ladder with named sign-off
- Post-implementation verification and the evidence retained at each gate
