# mainframe - the core banking system

**Stratum 0** | **Vintage ~1995** | **COBOL-85 (GnuCOBOL), JCL, fixed-width files, COMP-3 packed decimal** | **Built by WP-03, WP-04, WP-05**

The account master - the definitive record of what every account holds - and the overnight batch cycle that applies each day's movements to it. Roughly 90% of US banking core software is still legacy of this kind, and it is not going away: it works, it has been correct for thirty years, and no board has approved the risk of replacing the system that knows how much money everyone has.

## Contents

| Directory | Holds |
|---|---|
| `cobol/` | `ACCTPOST.CBL` (match-merge), `EODREPT.CBL` (report) |
| `copybook/` | Fixed-width record layouts shared by every program |
| `jcl/` | `EODCYCLE.JCL` - the job graph, plus the local shell runner |
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

