# mainframe - the core banking system

**Stratum 0** | **Vintage ~1995** | **COBOL-85 (GnuCOBOL), JCL, fixed-width files, COMP-3 packed decimal** | **Built by WP-03, WP-04, WP-05**

The account master - the definitive record of what every account holds - and the overnight batch cycle that applies each day's movements to it. Roughly 90% of US banking core software is still legacy of this kind, and it is not going away: it works, it has been correct for thirty years, and no board has approved the risk of replacing the system that knows how much money everyone has.

## Contents

| Directory | Holds |
|---|---|
| `cobol/` | `ACCTPOST.CBL` (match-merge), `EODREPT.CBL` (report) |
| `copybook/` | Fixed-width record layouts shared by every program |
| `jcl/` | `EODCYCLE.JCL` - the job graph, the local shell runner, and the DFSORT stand-in |
| `data/` | Synthetic account master and movement files |

## Constraints

- **COBOL-85 fixed format.** Columns 1-6 sequence, 7 indicator, 8-11 area A, 12-72 area B. Tabs are
  forbidden - they destroy column alignment and the compiler rejects the source.
- **Money is COMP-3 packed decimal**, sign nibble included. The Java side in `integration/` must
  reproduce this byte for byte.
- Batch processing is a **single sequential pass** over sorted files. The algorithm must stay correct
  for a master larger than memory, which is the whole reason match-merge exists.
- All data is synthetic. See [data classification](../docs/ways-of-working/data-classification.md).

## Do not modernise

This tier is deliberately from 1995. See [`CLAUDE.md`](../CLAUDE.md) and
[ADR 0002](../docs/governance/adr/0002-deliberate-legacy-strata.md).

## What exists now

The tier is complete. WP-03 landed the **data layer** - the copybooks and the synthetic files.
WP-04 landed **`ACCTPOST`**, the balanced-line match-merge that applies a day of movements to the
account master. WP-05 landed **`EODREPT`**, the control-break report, and the **overnight cycle**:
`EODCYCLE.JCL` and the shell runner that executes the same four steps locally.

The whole cycle runs end to end on a laptop:

```bash
make eod
```

### Prerequisites

GnuCOBOL 3.2 or later. On macOS:

```bash
brew install gnucobol
```

### Building and generating

```bash
sh mainframe/copybook/compile-check.sh          # cobc accepts every copybook
python3 mainframe/copybook/check-identity.py    # copybooks match contracts/copybook byte for byte
python3 mainframe/data/test_comp3.py            # COMP-3 against the canonical model's worked examples
python3 mainframe/data/generate.py --seed 42    # synthetic master and movements
python3 mainframe/data/check-records.py         # every field against the contract
```

Python 3 with the standard library only - nothing to install.

### The dialect is `-std=ibm`, not `-std=cobol85`

`COMP-3` is an IBM extension. Strict ANSI COBOL-85 spells packed decimal `PACKED-DECIMAL` and rejects
`COMP-3` outright, which the compile harness discovered the first time it ran. Both spellings produce
identical bytes, and every banking COBOL program ever written says `COMP-3` - so the copybooks keep
`COMP-3` and the compiler is told which dialect that is. Changing the copybooks to suit a stricter
flag would have made the code less like the thing it reproduces.

### Running the programs and the cycle

```bash
make build-mainframe                            # compile ACCTPOST and EODREPT
python3 mainframe/cobol/test-acctpost.py        # 13 scenarios - the match-merge
python3 mainframe/cobol/test-eodrept.py         # 18 scenarios - the report
python3 mainframe/jcl/test-sortrec.py           #  9 tests     - the sort
python3 mainframe/jcl/test-eod-cycle.py         # 14 scenarios - the cycle end to end
make test-mainframe                             # all of the above
```

See [`cobol/README.md`](cobol/README.md) for the reason codes, the report layout and the three bugs
the tests caught, and [`jcl/README.md`](jcl/README.md) for the job graph and why it has two sort
steps. The operational view is the [end-of-day runbook](../docs/runbooks/eod-cycle.md).

### One overnight run, on the synthetic data

200 accounts, 302 movements, seed 42:

```
ACCTPOST CTL MASTER-READ            200      EODREPT CTL ACCOUNTS            200
ACCTPOST CTL MASTER-WRITTEN         200      EODREPT CTL CURRENCIES            3
ACCTPOST CTL MOVE-READ              302      EODREPT CTL REJECTED            162
ACCTPOST CTL MOVE-APPLIED           140      EODREPT CTL PAGES                 7
ACCTPOST CTL MOVE-REJECTED          162      EODREPT CTL BALANCED
ACCTPOST CTL VALUE-MOVED       1722315.18
ACCTPOST CTL BALANCED
```

The report reconciles against the run that produced it: 162 rejects counted, 162 reported,
`*** IN BALANCE`. Most of those rejects are `R003`, currency mismatch, because the generator pairs
PLN movements with a master that holds EUR and USD accounts too - see follow-up F-18 in
[`STATUS.md`](../docs/plan/STATUS.md).

