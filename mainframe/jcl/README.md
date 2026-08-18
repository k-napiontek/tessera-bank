# Job control

**Stratum 0** | **Built by WP-05**

The overnight cycle: the JCL deck that defines it, and the shell runner that executes it locally.

| File | What it is |
|---|---|
| `EODCYCLE.JCL` | The production job deck. Never executed here. |
| `run-eod.sh` | The executable equivalent, running the same four steps under GnuCOBOL. |
| `sortrec.py` | A fixed-length record sort, standing in for DFSORT. |
| `test-eod-cycle.py` | The cycle, its guards, and JCL-to-runner parity. |
| `test-sortrec.py` | The sort. |

## The four steps

```
STEP010  SORT      movements as delivered      -> account-reference sequence
STEP020  ACCTPOST  sorted movements + master   -> new master, rejects
STEP030  SORT      the new master              -> currency then reference sequence
STEP040  EODREPT   report-sequenced master     -> the printed report
```

```bash
python3 mainframe/data/generate.py --seed 42
bash mainframe/jcl/run-eod.sh --business-date 20260818
make eod                                    # both of the above
```

Outputs land in `mainframe/data/out/eod/<business-date>/`. See the
[runbook](../../docs/runbooks/eod-cycle.md) for what each file is and what to do when a step fails.

## Why there are two sort steps

The work package describes the graph as SORT, ACCTPOST, EODREPT. There are two sorts because
`ACCTPOST` writes the new master in **account-reference** order - the order the match-merge consumes
it in - while `EODREPT` control-breaks on **currency**. Running a control-break report over a file
sorted by a different field produces a subtotal at nearly every record: figures that look like
subtotals and are nonsense.

A report sequence is a sort step. That is how DFSORT is used in practice, and it keeps `EODREPT` a
single sequential pass like every other program in this tier. The alternative - having the report
sort for itself - is a report that holds the master, which is the one thing this tier must never do.

## The JCL is documentation, and it is checked

`EODCYCLE.JCL` cannot run here. It is the artefact a mainframe engineer should recognise: a `JOB`
card with accounting information, `EXEC PGM=` per step, `DD` statements carrying `DSN`, `DISP` and
`DCB=(RECFM=FB,LRECL=...)`, inline `SORT FIELDS=` control statements, `COND=(0,LT)` and an
`IF (STEP040.RC = 0) THEN` block.

Two files that must agree, and are only asked to agree by a sentence, will diverge. So
`test-eod-cycle.py` checks it:

- the step names, their order and the programs they run must match `run-eod.sh --steps`;
- every DD name the cycle uses must be declared;
- the `LRECL` values must match the copybooks;
- the `SORT FIELDS=` columns must be the same fields the runner passes to `sortrec.py`.

That last one earns its keep: DFSORT counts columns from 1 and `sortrec.py` counts bytes from 0, so
`MOV-ACCT-REF` is `(23,16,CH,A)` in the JCL and `22:38` in the runner. An off-by-one sorts on the
wrong field, and every figure downstream is quietly wrong.

## `sortrec.py` is not DFSORT

It sorts fixed-length records on byte ranges without interpreting them, because Unix `sort` cannot:
a COMP-3 amount can pack to a trailing `0x0D`, and fixed-width records are padded with the `0x20`
bytes that line-oriented tools strip.

**It reads the whole file into memory. DFSORT does not.** The real utility spills to work datasets
and sorts files far larger than the machine running it. Nothing about this stand-in should be read
as evidence that the local cycle handles a master larger than memory - only the COBOL does that, and
only because it never sorts.

## Idempotence, and the guard against posting twice

Two runs over the same inputs produce byte-identical outputs: the work directory is re-seeded from
the input master every run, and the run timestamp is derived from the business date rather than the
clock.

Applying the same movement file twice is refused outright. On success the runner writes
`MOVEMENT.APPLIED` holding the file's SHA-256; a second run with the same file for the same business
date exits 8. A **different** file for the same date is allowed - a corrected re-send is normal
operations - and `--rerun` overrides the refusal deliberately.

Applying a day's movements twice doubles every posting in the bank. "The operator would notice" is
not a control.
