# backoffice

**Stratum 1** | **JSP + jQuery** | **Built by WP-15**

The internal operations screen: reconciliation breaks, the rejects queue from the overnight cycle, and the operator actions on both. Server-rendered, unfashionable, and exactly what internal bank tools actually look like fifteen years after they were written.

**No modern frontend tooling.** No React, no TypeScript, no bundler. This screen exists to demonstrate that the estate contains genuinely different eras. Styling should look its age - do not make it pretty.

Every operator action writes to the audit trail. An internal tool that mutates state without an audit record is precisely the finding an auditor writes up.

