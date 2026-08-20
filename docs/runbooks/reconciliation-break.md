# Runbook: reconciliation break

What to do when the morning reconciliation reports a difference between the COBOL account master and
the PostgreSQL ledger.

**Breaks are investigated by humans and never auto-corrected.** A reconciliation that silently fixes
differences destroys the evidence of why they occurred, and the difference is usually the only
surviving trace of the defect that caused it. There is no "repair" command and adding one would need
an ADR reversing REQ-REC-003.

| | |
|---|---|
| **Runs** | `batch/recon`, every morning after the overnight cycle |
| **Reads** | the account master the cycle produced, the movement file it consumed, and the ledger |
| **Writes** | `BREAKS-CCYYMMDD.json` and a metrics textfile. Nothing else, and nothing in either core |
| **Escalation** | Core Banking Operations, then the Ledger team; a `MISSING_IN_LEDGER` is an incident from the start |

## Running it

```bash
export RECON_LEDGER_DSN=postgresql://.../ledger
recon --business-date 20260818 \
      --master     .../eod/20260818/ACCTNEW.DAT \
      --movements  .../eod/20260818/MOVEMENT.IN \
      --output     .../recon/20260818 \
      --metrics    /var/lib/node_exporter/recon.prom
```

**`--movements` must be the file the cycle actually consumed**, not today's live movement file and
not a regenerated one. That file *is* the cut-off - [ADR 0015](../governance/adr/0015-the-cut-off-is-the-movement-file.md) -
and running against the wrong one misclassifies wholesale: every transfer the cycle applied but the
file does not name will report as drift. The report records `cutOff.transferRefCount` so that this
mistake is visible on the face of the output. **If that count does not look like a night's work,
stop and check which file you passed before reading anything else.**

`--position` reproduces an earlier run's cut exactly. Use it when you need to show somebody the
report as it was, not as it is now.

## Working it in the back office

The report is rendered by `legacy/backoffice` at `/backoffice/breaks`, which is where an operator
works it rather than reading JSON. The screen shows the same control totals, the same ledger cut and
the same transfer count, so everything below applies whichever way you are reading it.

Two things the screen does that the file cannot:

- **It shows what has already been acknowledged, and by whom.** A break somebody else is working is
  not a break you should start on.
- **A `TIMING` break has no button.** That is not an oversight - it is the classification saying
  "expected", and `PKG_OPERATOR` refuses to record an acknowledgement for one even if something
  other than the screen asks.

Acknowledging is idempotent, so a double-click is one act. Annotating a reject **replaces** the note
and the previous text survives only in the audit trail, which is append-only. Both actions record
the operator the container authenticated, never a name typed into the page.

## Reading the report

`breaks` is ascending by account reference. `totals` bounds it: `accountsCompared` always equals
`accountsMatched` plus `accountsBroken`, and the job refuses to write a report where it does not.
`totalAbsoluteDriftMinor` sums absolute values, so two accounts wrong by equal and opposite amounts
show as the sum rather than cancelling to zero - that shape is the most alarming a reconciliation
can take, not the least.

**A break is not a failed job.** The reconciliation exits 0 when it finds breaks: it ran, it
compared, it found a disagreement, and that is the control working. Exit 1 means the job did not
run - a file that is not a whole number of records, a ledger it could not read, a `--position` this
database has never held. **Never respond to breaks by rerunning the job.** It is deterministic; the
same two inputs give the same report, and the rerun costs you the morning.

## The four classifications

### `TIMING` - expected, and not yours to work

The master holds exactly what the cut-off says it should. The difference from the ledger is entirely
movements posted after the cycle's input file was cut, and they will be applied by tonight's cycle.

**Action: none.** It is in the report so that it is visibly *not* drift. Do not raise it, do not
correct it, and do not suppress it - a difference that is invisible cannot be confirmed as
understood.

Alert on the other three classifications and never on this one. Paging somebody every morning for
timing is how a report gets ignored.

### `VALUE_DRIFT` - both systems hold the account and disagree

The master matches neither the ledger's balance nor what the cut-off says it should hold. Something
was applied differently on the two sides.

1. Take the account reference and the two figures from the break. The difference is `master - ledger`.
2. Look for that amount among the day's movements for the account. A difference equal to one
   movement's value means that movement reached one side only; a difference equal to twice a
   movement's value means it was applied twice on one side, or with the wrong sign.
3. Check the movement file for the account's `MOV-TRANSFER-REF` entries, and the ledger's postings
   for the same references. One of them has a movement the other does not, or has it differently.
4. If the amounts match but the sign is inverted, suspect the account type rather than the movement:
   an `ASSET` rises on the debit and a `LIABILITY` on the credit, and an account created with the
   wrong type drifts by exactly twice every movement.

**Escalation:** Core Banking Operations first. Involve the Ledger team once you can name the
movement that differs.

### `MISSING_ON_MASTER` - the ledger has the account, the master does not

Usually benign and worth confirming rather than assuming.

1. Was the account opened after the master was cut? Check its opening entry against the business
   date being reconciled. If so it will appear after tonight's cycle, and tomorrow's report should
   be clean for it.
2. If it was opened before the cut, the account never reached stratum 0. That is an integration
   failure: check `tessera.esb.transfer-posted.dlt.v1` for dead letters and follow
   [the EOD runbook's movement-file section](eod-cycle.md).

### `MISSING_IN_LEDGER` - the master has the account, the ledger does not

**Treat as an incident from the start.** An account can predate the migration legitimately, and that
case should be known and stable - the same references every morning. A reference that appears here
for the first time means a row is gone from a system whose postings are append-only, which is either
a restore from a divergent backup or something worse.

Do not investigate quietly. Raise it, then gather evidence.

## Evidence to gather before doing anything

Anything you collect after a correction is worth less, and a correction may be made by a different
team an hour later.

- The break report itself, `BREAKS-CCYYMMDD.json`. It carries `ledgerPosition` and
  `ledgerChainHash`, which is what lets anyone re-derive the same report later - and what proves the
  ledger you read is the ledger that is running now.
- The master and the movement file the run used. **Copy them; do not point at them.** The next
  cycle overwrites the work directory.
- The account's postings from the ledger at that position, and its `MOV-*` records from the movement
  file.
- The overnight cycle's control totals for the same date - `MOVE-APPLIED`, `MOVE-REJECTED`,
  `VALUE-MOVED` - from the EOD output. A reject the cycle reported is often the whole explanation.

## Escalation thresholds

| Condition | Response |
|---|---|
| `TIMING` only, any value | None. The control is working |
| Any `MISSING_IN_LEDGER` | Incident immediately, whatever the value |
| `VALUE_DRIFT` on a single account, under 1 000.00 | Investigate the same morning |
| `VALUE_DRIFT` total absolute drift over 10 000.00, or more than 10 accounts broken | Incident. A pattern is not a mistake |
| Two consecutive mornings with the same unexplained break | Incident. A break that survives a cycle is not timing, whatever it is classified as |
| No report at all for a business date | Incident. Absence of output and a clean night look identical from outside, which is why the job always writes a report |

## Recording the outcome

Close every break with a written cause, even the ones that turn out to be nothing - "expected,
account opened after the cut" is a finding and next month somebody will need it. Feed anything that
reached incident management back into
[`docs/ways-of-working/`](../ways-of-working/), and if a class of break turns out to be routine and
correctly explained, that is an argument for a new classification in
[`contracts/recon/break-report-v1.md`](../../contracts/recon/break-report-v1.md) - not for widening
`TIMING` until it absorbs the problem.
