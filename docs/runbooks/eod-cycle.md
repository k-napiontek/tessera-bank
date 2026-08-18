# Runbook: end-of-day cycle

**Job** `TBEODCYC` | **Stratum 0** | **Owner** Core Banking Operations | **Escalation** Core Banking
on-call, then the Head of Operations

Written for an operator at 03:00. Every instruction below is something to do, not something to know.

---

## What it does

Applies the day's movements to the account master and prints the end-of-day report. Four steps:

| Step | Program | Reads | Writes |
|---|---|---|---|
| `STEP010` | `SORT` | the delivered movement file | movements in account-reference order |
| `STEP020` | `ACCTPOST` | sorted movements, current master | **new master**, rejects file |
| `STEP030` | `SORT` | the new master | master in currency order, for the report |
| `STEP040` | `EODREPT` | report-sequenced master, rejects | the printed report |

**No step updates the master in place.** `STEP020` writes a new generation, so a failed cycle leaves
yesterday's master intact and a restart re-applies nothing.

## When it runs

Nightly at 03:00, after the integration tier has delivered the movement file. Normal duration is
under a minute on the synthetic volumes in this repository; on production volumes it is the sort
steps that dominate.

**It must complete before the back office opens at 07:00.** If it has not completed by 06:00,
escalate - do not keep waiting.

## Running it

```bash
python3 mainframe/data/generate.py --seed 42          # synthetic input, local only
bash mainframe/jcl/run-eod.sh --business-date 20260818
```

Everything lands in `mainframe/data/out/eod/<business-date>/`:

| File | What it is |
|---|---|
| `MOVEMENT.IN` | the movement file exactly as delivered |
| `MOVEMENT.DAT` | the same file, sorted - `STEP010` |
| `ACCTNEW.DAT` | **the new account master** - `STEP020` |
| `REJECTS.DAT` | movements that could not be applied - `STEP020` |
| `ACCTPOST.LOG` | `STEP020`'s control totals |
| `ACCTRPT.DAT` | the master in report sequence - `STEP030` |
| `EODREPT.TXT` | **the end-of-day report** - `STEP040` |
| `MOVEMENT.APPLIED` | the marker proving this movement file was posted |

`EODCYCLE.JCL` is the production deck. It is not executed here - GnuCOBOL cannot run JCL - and
`run-eod.sh` is the executable equivalent. The two describe the same four steps and a test fails if
they ever diverge.

## What success looks like

Last line: `EOD CYCLE COMPLETE`, exit code 0. Then check three things, in this order.

**1. `STEP020` balanced.** In `ACCTPOST.LOG`:

```
ACCTPOST CTL MOVE-READ              302
ACCTPOST CTL MOVE-APPLIED           140
ACCTPOST CTL MOVE-REJECTED          162
ACCTPOST CTL BALANCED
```

`MOVE-READ` must equal `MOVE-APPLIED` plus `MOVE-REJECTED`. The program checks this itself and ends
`RC=12` when it does not hold. A batch that silently loses a record is worse than one that fails.

**2. The report reconciles.** The last line of `EODREPT.TXT` reads `*** IN BALANCE`, and the reject
recap shows the same figure `ACCTPOST` reported:

```
      TOTAL REJECTED                                           162
      ACCTPOST REPORTED                                        162

  *** IN BALANCE
```

`*** NOT RECONCILED` means the job did not pass `STEP020`'s count to `STEP040`. The report is still
correct; the reconciliation simply was not performed. Raise it, do not ignore it.

**3. The account count.** `EODREPT CTL ACCOUNTS` must equal `ACCTPOST CTL MASTER-WRITTEN`. If the
report shows fewer accounts than the master holds, `STEP030` lost records.

## When a step fails

The cycle stops at the first non-zero return code and no later step runs. The message names the step:

```
EOD ABEND STEP020 RC=12  the cycle stopped, no later step ran
```

| Step | What went wrong | Do this |
|---|---|---|
| `STEP010` | `SORTREC ERROR ... not a whole number of 120-byte records` - the delivered file is truncated | Do **not** pad it. Ask the integration tier to re-send. A partial record means their write failed. |
| `STEP020` | `ACCTPOST ABEND OPEN ACCTMAST` - the master could not be opened | Check the master exists and is readable. The previous generation is still intact; nothing has been applied. |
| `STEP020` | `ACCTPOST CTL OUT-OF-BALANCE` | Do not re-run. Escalate: the program read more movements than it accounted for, which is a defect, not a data problem. |
| `STEP030` | sort failed on the new master | The new master is suspect. Do not catalogue it. Escalate. |
| `STEP040` | `*** OUT OF BALANCE`, `RC=12` | The report counted a different number of rejects than `ACCTPOST` did. The master is fine and already written; the report is not trustworthy. Escalate before anyone works the rejects. |
| any | `EOD ABEND SETUP ... not found` | An input file is missing. The cycle did nothing. Fix the path and run again. |

## Restart and recovery

**A failed cycle has applied nothing.** `STEP020` writes a new master generation rather than
updating the current one, so the bank's position is exactly where it was before the job started.
Fix the cause and run the cycle again from the beginning.

### Never apply the same movement file twice

Applying a day's movements twice doubles every posting in the bank. The cycle refuses:

```
EOD ABEND SETUP this movement file was already applied for 20260818
   pass --rerun only if you intend to apply it again
```

Exit code 8. The check is the movement file's SHA-256 recorded in `MOVEMENT.APPLIED`, so:

- **the same file again** is refused;
- **a corrected file re-sent for the same date** is allowed - that is normal operations;
- `--rerun` overrides the refusal.

`--rerun` is legitimate in exactly two situations: re-running a cycle whose outputs were lost or
corrupted after it completed, and re-running against inputs you have confirmed did not post. It is
never the right answer to "the cycle failed" - a failed cycle applied nothing, so nothing blocks it.

**Running the cycle twice over the same inputs produces byte-identical outputs.** The work directory
is re-seeded from the input master each time and the run timestamp comes from the business date, not
the clock. If two runs differ, something is wrong; report it.

## Rejects

`REJECTS.DAT` holds every movement that could not be applied, each carrying the original movement
verbatim in its first 120 bytes so it can be re-presented without being re-encoded. Rejection is a
business outcome, not a failure - the run carries on.

The back office works them. Counts by reason appear in the report's reject recap; the codes are
`ACCTPOST`'s and are documented in [`mainframe/cobol/README.md`](../../mainframe/cobol/README.md).

A **rising** reject count is the signal worth acting on, particularly `R001` (account not found),
which usually means the two tiers disagree about which accounts exist.

## Escalation

| When | Who |
|---|---|
| Any step fails and the cause is not in the table above | Core Banking on-call |
| `ACCTPOST CTL OUT-OF-BALANCE`, or the report out of balance | Core Banking on-call, **immediately** - do not re-run |
| Not complete by 06:00 | Core Banking on-call and the Head of Operations |
| Reject count materially above the usual level | Core Banking on-call, in hours |

Wake someone for a control total that does not balance. Do not wake someone for rejects.
