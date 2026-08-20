# backoffice

**Stratum 1** | **Vintage ~2011** | **Java 8, Servlet 3.0, JSP + JSTL, jQuery 1.7.2, WAR on Tomcat 8.5** | **Built by WP-15**

The internal operations screen: reconciliation breaks, the rejects queue from the overnight cycle,
and the operator actions on both. Server-rendered, unfashionable, and exactly what internal bank
tools actually look like fifteen years after they were written.

**Its own WAR, beside `customer-master` rather than inside it.** An operator screen and the SOAP
endpoint the integration tier depends on should not share a deployment: a change to a table layout
would otherwise redeploy the service that carries every transfer into stratum 1. Two WARs on one
Tomcat is what a 2011 bank ran. The cost is that `customer-master` publishes a **classes jar** so
its DAO can be reused here - the alternative being a second implementation of how this bank reads
an account, which is the drift `batch/recon` exists to detect.

## The screens

| Path | Shows |
|---|---|
| `/breaks` | The morning's reconciliation, from `BREAKS-CCYYMMDD.json` |
| `/rejects` | Movements the overnight cycle refused, from `REJECTS.DAT` |
| `/action` | POST only: acknowledge a break, annotate a reject |

Both input directories are `context-param`s in `web.xml`. No path and no connection string appears
anywhere in this WAR's code - a 2011 application declared what it needed in the descriptor and the
operations team bound it to the environment, which is why one artefact deploys everywhere unchanged.

## The four things this screen gets right on purpose

### A timing break offers no action

`TIMING` means the master holds exactly what the cut-off says it should, and the difference is
movements posted after the cycle's input was cut. It is **expected**. The screen lists it - a
difference that is invisible cannot be confirmed as understood - and offers no button, and
`PKG_OPERATOR` refuses to acknowledge one even if something else calls it. A rule enforced only in
a JSP is a rule the next caller does not have.

The reasoning is [ADR 0015](../../docs/governance/adr/0015-the-cut-off-is-the-movement-file.md), and
WP-16's Constraints put it plainly: classifying an expected difference as a break trains operators
to ignore the report, which is worse than having no report.

### "No report" is not "no breaks"

A directory with nothing in it for the business date says **the reconciliation has not run**, in a
warning box. Those are the two states an operator must never confuse, and the second is the one that
means nobody is checking. The same distinction is made on the rejects screen.

### The acting user comes from the container

`getRemoteUser()`, never a form field. A hidden input naming the operator is a field anybody can
edit, and an audit trail recording who the browser said it was is not attributable at all. Every
action is a POST followed by a redirect, so a refresh cannot repeat it - an audit trail full of
accidental duplicates is one nobody can read.

### The change and its record are one transaction

Both actions are `PKG_OPERATOR` calls, so the row and its audit entry are written together inside
the database. A DAO that inserted the audit row itself would be one an application bug could skip
past. The trail is **append-only**, enforced by a trigger: an audit trail an application can rewrite
is a log, not a control.

## Reading the break report

`batch/recon` writes it; this renders it. The format is
[`contracts/recon/break-report-v1.md`](../../contracts/recon/break-report-v1.md) and neither side
reads the other at run time - each is held to the contract by its own test, which is what makes them
capable of disagreeing.

Anything this screen cannot check, it refuses: a document of another format, control totals that do
not balance, a classification the contract has gained since this module was written. A screen that
renders whatever it is handed will one day render last week's file and say nothing.

Jackson **1.x** (`org.codehaus.jackson`), because that is what a 2011 Java shop had. Jackson 2 is
`com.fasterxml` and a different era.

## Styling

It looks its age, deliberately. No framework, no reset, no build step. Tables have borders because
the data is tabular and an operator reads it eight hours a day. jQuery filters rows that the server
already rendered; with scripting off the page is still complete and correct. **Do not make it
pretty** - see WP-15's Constraints.

## Tests

```bash
make test-backoffice
```

Needs Docker: a real Oracle and a real Tomcat 8.5 start during this suite. `BackofficeDeploymentIT`
deploys the built WAR and walks what an operator walks - logs in, reads a break list rendered from a
report the test wrote, acknowledges a break, and reads the audit row back **out of Oracle** rather
than off the screen that claims to have written it.

The Cargo and Oracle scaffolding under `src/test/java/.../it/` is copied from `customer-master`'s
test tree and the copy is recorded as **F-61**, not hidden. It is the third copy in the repository.
