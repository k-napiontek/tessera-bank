# Reconciliation break report, format `TB-RECON-BREAKS-V1`

The morning comparison between the COBOL account master and the PostgreSQL ledger, as data.
`batch/recon` writes it; `legacy/backoffice` renders it for the operators who work the breaks.

[`../check-break-report.py`](../check-break-report.py) asserts that the tables below are a coherent
format: no field declared twice, every field a type this format allows, and every classification the
break table names also named by the classification table. `batch/recon` validates its own output
against these tables, which is the separate claim that the writer implements the format rather than
that the format is well formed.

## Why a file, and why JSON

**A file, because the reconciliation is read-only against both systems.** That is a constraint of
WP-16 rather than a preference: a job that writes breaks into either core is a job that can damage
the thing it was built to check. Neither Oracle nor PostgreSQL is therefore available to it as a
destination, and a file is what is left.

**JSON, because the consumer is inside this estate.** The regulatory extract next door is fixed
width and stays that way, because its reader belongs to a supervisor and was written before the
sender existed. This report's reader is a screen in the same bank, the document is evidence a human
reads during an investigation, and the header, the breaks and the totals must arrive together - a
break list separated from the totals that bound it is worse than no report, because it looks
complete.

## The file

- **One document per run**, UTF-8, a single JSON object. Not JSON Lines: a reader must not be able
  to consume half of it and believe it has the whole.
- **A run always writes one**, including a run that finds nothing. `breaks` is then `[]` and the
  totals are zeroes. Absence of output is indistinguishable from a job that never ran, and the
  distinction is the entire value of a control.
- **Amounts are minor units**, as integers, exactly as the canonical data model defines
  `amountMinor`. No decimal point appears anywhere in this file and no amount is ever a JSON float.
- **`breaks` is ascending by `accountRef`**, which is the order the account master is already in.
- **No wall clock in the body.** The run instant belongs beside the file, not inside it, for the
  reason [ADR 0009](../../docs/governance/adr/0009-reports-are-cut-at-an-audit-position.md) gives:
  a timestamp in the body makes byte-identical reruns impossible by construction while looking like
  helpful metadata.

## Top level

| Field | Type | Holds |
|---|---|---|
| `formatId` | `string` | `TB-RECON-BREAKS-V1` |
| `businessDate` | `date8` | `CCYYMMDD`, the date reconciled |
| `ledgerPosition` | `integer` | The ledger cut - an `audit_record.seq`, per ADR 0009 |
| `ledgerChainHash` | `hex64` | The audit chain head hash at that position |
| `cutOff` | `object` | Which movements the overnight cycle had already seen - see below |
| `masterFile` | `object` | Which account master was read - see below |
| `breaks` | `array` | Zero or more breaks, ascending by `accountRef` |
| `totals` | `object` | The control totals - see below |

`ledgerPosition` and `ledgerChainHash` together are what make a break report reproducible and
traceable at once: the position says which postings were in scope, the hash says which audit chain
they belong to. A report re-cut against a restored database whose history diverged is detectable
rather than merely unlikely.

## `cutOff`

| Field | Type | Holds |
|---|---|---|
| `movementFile` | `string` | Name of the movement file the cycle consumed |
| `transferRefCount` | `integer` | How many distinct `MOV-TRANSFER-REF` values it carried |

**The cut-off is this file, not a timestamp.** A ledger entry whose transfer reference appears in the
movement file must have reached the master; one whose reference does not is expected to be absent,
and is classified `TIMING` rather than as drift. The reasoning is ADR 0015. Recording the count here
means a reader can tell a report cut against an empty movement file - where everything would look
like timing - from one cut against a real night's work.

## `masterFile`

| Field | Type | Holds |
|---|---|---|
| `name` | `string` | Name of the account master read |
| `recordCount` | `integer` | `ACCTREC` records in it |

## A break

| Field | Type | Holds |
|---|---|---|
| `accountRef` | `string` | The account, as `ACCT-REF` holds it, trimmed |
| `classification` | `string` | One of the classifications below |
| `currency` | `string` | ISO 4217, from whichever side holds the account |
| `masterBookedMinor` | `integer?` | `ACCT-BOOKED-BAL` in minor units, `null` if the master has no such account |
| `ledgerBookedMinor` | `integer?` | The ledger's booked balance in minor units, `null` if the ledger has no such account |
| `differenceMinor` | `integer?` | `masterBookedMinor - ledgerBookedMinor`, `null` when either side is absent |

`differenceMinor` is deliberately `null` rather than the present side's value when an account is
missing from one system. A difference implies two figures were compared; printing one of them as a
difference invites an operator to read a missing account as drift of that size.

## Classifications

| Classification | Means | The operator's first question |
|---|---|---|
| `VALUE_DRIFT` | Both systems hold the account and the booked balances differ | Which movements does one side have that the other does not |
| `MISSING_ON_MASTER` | The ledger holds the account, the master does not | Was it opened after the master was cut, or did it never reach stratum 0 |
| `MISSING_IN_LEDGER` | The master holds the account, the ledger does not | An account that predates migration, or one deleted from the ledger - the second is an incident |
| `TIMING` | The balances differ, and the difference is fully explained by movements posted after the cut-off | None. This is expected, and it is reported so that it is visibly *not* drift |

`TIMING` is a break the report shows and the operator does not work. It is listed rather than
suppressed because a difference that is invisible cannot be confirmed as understood, and a
reconciliation that hides its expected differences is one nobody can audit.

## `totals`

| Field | Type | Holds |
|---|---|---|
| `accountsCompared` | `integer` | Accounts seen on either side |
| `accountsMatched` | `integer` | Accounts whose booked balances agreed exactly |
| `accountsBroken` | `integer` | Accounts with a break of any classification |
| `totalAbsoluteDriftMinor` | `integer` | Sum of `abs(differenceMinor)` over breaks that have one |

**`accountsCompared` equals `accountsMatched` plus `accountsBroken`.** A report where it does not is
a report that lost an account, and the writer refuses to emit one - a control total that is not
checked against anything is decoration.

`totalAbsoluteDriftMinor` sums absolute values on purpose. Signed drift cancels, and two accounts
wrong by equal and opposite amounts is the single most alarming shape a reconciliation can take, not
the least.

## Types

| Type | Means |
|---|---|
| `string` | JSON string |
| `integer` | JSON number with no fractional part; never a float |
| `integer?` | `integer` or `null` |
| `object` | JSON object, fields as tabled above |
| `array` | JSON array |
| `date8` | JSON string of exactly eight digits, `CCYYMMDD` |
| `hex64` | JSON string of exactly 64 lower-case hex characters |
