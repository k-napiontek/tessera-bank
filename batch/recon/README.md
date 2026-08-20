# recon

**Spans strata 0 and 3** | **Python 3.12 under uv** | **Built by WP-16**

Compares the COBOL account master against the PostgreSQL ledger every morning and reports drift.
This is the safety net that makes strangler-fig modernisation survivable: while two systems both
believe they hold the truth about a customer's money, the only defensible position is to check them
against each other daily and investigate every difference.

Every other component in this estate moves money between the eras. This is the one that checks the
eras still agree afterwards, and it became possible only when WP-11b made a single transfer reach
both cores.

## Running it

```bash
export RECON_LEDGER_DSN=postgresql://.../ledger
recon --business-date 20260818 \
      --master     .../eod/20260818/ACCTNEW.DAT \
      --movements  .../eod/20260818/MOVEMENT.IN \
      --output     .../recon/20260818 \
      --metrics    /var/lib/node_exporter/recon.prom
```

Output is `BREAKS-CCYYMMDD.json`, in the format
[`contracts/recon/break-report-v1.md`](../../contracts/recon/break-report-v1.md) defines.
`legacy/backoffice` renders it for the operators who work the breaks; building that screen is WP-15.

**A break is not a failed job.** The reconciliation exits 0 when it finds breaks - it ran, it
compared, it found a disagreement, and that is the control working. Exit 1 means the job did not
run. See the [runbook](../../docs/runbooks/reconciliation-break.md), which also explains why
rerunning it is never the answer.

## The three things this component gets right on purpose

### The cut-off is the movement file, not a timestamp

Five documents in this repository referred to "the mainframe cut-off" and none defined it. A clock
cannot: the ledger and the overnight cycle share none, and a boundary drawn at a time stops being
reproducible the moment either side's moves.

The cycle's input is a file, and that file names every transfer it carried in `MOV-TRANSFER-REF`. So
**the set of references in the movement file the cycle consumed is the cut-off**. A ledger entry
whose reference is in it must have reached the master; one whose reference is not is expected to be
absent, and is `TIMING` rather than drift. Exact, with no window and no tolerance, and reproducible
from the two files for ever. See [ADR 0015](../../docs/governance/adr/0015-the-cut-off-is-the-movement-file.md).

**This matters more than it looks.** The work package says so outright: classifying a timing
difference as a break trains operators to ignore the report, which is worse than having no report. A
control that is ignored still passes its audit and stops working.

### Read-only against both systems, proved rather than promised

The reconciliation never writes to the ledger or the master. The database role is granted `SELECT`
and nothing else, so the refusal comes from PostgreSQL rather than from this component's good
intentions, and a test asserts it.

`compare` holds no connection, no file handle and no path - it takes two lists of records and
returns breaks. **Breaks are investigated, never auto-corrected**, and that is enforced by the shape
of the code: a change that wanted to auto-heal a break would have to add a writer to that module
first, which is a diff a reviewer notices. A reconciliation that silently fixes differences destroys
the evidence of why they occurred.

### Nothing here is borrowed from the thing it checks

There are three implementations of COMP-3 in this repository - `mainframe/data/comp3.py`,
`Comp3.java` and this one - and five statements of the normal-balance rule. That is deliberate and it
is the point rather than the price. A decoder that imported one of the others would inherit whatever
that one has wrong, and a reconciliation that asked the ledger which way its own figures ran would
be reconciling nothing. Independent implementations required to agree are a control; one
implementation consulted three times is a single point of failure with three names.

The same reasoning is why balances are **summed from postings** rather than read from the ledger's
`balance` table: the materialised figure is a cache of the thing being checked.

## Layout

| Module | Holds |
|---|---|
| `comp3.py` | Packed decimal decoded. Everything malformed is refused rather than interpreted |
| `master.py` | `ACCTREC` read, offsets derived from the contracts checker by its test, never transcribed |
| `cutoff.py` | The movement file read as a set of transfer references - the key field only, never the file as text |
| `ledger.py` | The ledger at an `audit_record.seq`, returning `booked_minor` and `expected_minor` side by side |
| `accounting.py` | Which way an account moves. An unknown type is refused, never defaulted |
| `compare.py` | The match-merge and the four classifications. No I/O of any kind |
| `report.py` | The break report, per the contract. Refuses totals that do not balance |
| `observability.py` | Breaks by classification as a metrics textfile |
| `run.py`, `main.py` | One run, and the command line around it |

## Tests

```bash
make test-recon
```

Needs Docker and GnuCOBOL. Six of the tests bring up a real PostgreSQL with the ledger's own Flyway
migrations **and** run the real overnight cycle through `run-eod.sh`, then reconcile the master that
cycle produced against the ledger that fed it. They fail rather than skip when the toolchain is
missing: a control that quietly does not run is the false assurance the Definition of Done's honesty
clause exists to prevent.
