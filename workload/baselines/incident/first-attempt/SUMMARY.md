# The first attempt, in figures

The run the response in [`../RESPONSE.md`](../RESPONSE.md) was worked against, before either fixture
defect was fixed. **Its per-account rows are not committed and its numbers are not this exercise's
numbers** - 449 of the 451 transfers it reports as never reaching the mainframe were eaten by the
injector's own file rotation, not by the fault. What is kept is the arithmetic the response cites,
and `ENVELOPE.json`, which is the same account and the same seed as the corrected run.

| Business date | Compared | Matched | VALUE_DRIFT | TIMING | Absolute drift | Cut-off |
|---|---|---|---|---|---|---|
| 20260302 | 16001 | 14928 | 210 | 863 | 4,019,266.34 | 7678 |
| 20260303 | 16001 | 14893 | 1108 | 0 | 4,372,587.94 | 7418 |

Every one of the 210 accounts in drift on D was still in drift on D+1, and 898 more had joined them.

| | |
|---|---|
| Ledger entries dated 2026-03-02 or 2026-03-03 | 15 341 |
| Transfer references the two cycles consumed | 15 096 |
| Reached the ledger and no movement file | **451** |
| Of those, in the rotated `MOVEMENT-seeding.DAT` | **449** - the harness |
| Of those, nowhere at all | **2** - the fault |

The two: `TB202608230000016816` and `TB202608230000018445`, both touching `TB000000000003R2`.
