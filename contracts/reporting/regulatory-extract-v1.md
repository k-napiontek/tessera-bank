# Regulatory extract, format `TB-REGEXT-V1`

The account-level extract a regulatory submission is built from. **Positions are 1-based and
inclusive**, the convention every fixed-width format and every hex dump uses.

[`../check-extract-layout.py`](../check-extract-layout.py) asserts that the tables below are a
coherent format: contiguous columns, lengths agreeing with their picture clauses, and every record
type the same width. `batch/reporting` validates its own output against these tables, which is the
separate claim that the writer implements the format rather than that the format is well formed.

## Why fixed width, in 2025

Because this is what a regulatory submission is. A supervisor receives a file, loads it with a
program written years before the sender existed, and reconciles the trailer. The format is boring on
purpose: no schema negotiation, no optional elements, no encoding to agree, and a record either is
200 bytes or is rejected.

It is deliberately **not** a copybook. Every field here is printable ASCII in display representation,
so the file is line-terminated text that can be opened, diffed and mailed. The stratum 0 files cannot
be, because a `COMP-3` amount can contain `0x0D` and a fixed-width record is space-padded, which is
why they are `ORGANIZATION IS SEQUENTIAL`. Same shape, four decades apart, different constraints.

## The file

- Exactly one `HDR`, then zero or more `ACC` in ascending account reference order, then exactly one
  `TRL`.
- Every record is 200 bytes followed by `LF`. Record *n* therefore begins at byte `(n - 1) * 201`.
- ASCII. `PIC X` fields are left-aligned and space-padded; `PIC 9` fields are right-aligned and
  zero-padded. An empty `PIC X` field is 200 spaces, never an empty line.
- Amounts are **minor units**, up to 15 digits, exactly as the canonical data model defines
  `amountMinor`. There is no decimal point anywhere in this file. `REGEXT-CURRENCY-SCALE` carries the
  ISO 4217 scale so a reader can place the point without shipping a currency table of its own - the
  reason JPY (0) and BHD (3) do not need a second format.

## HDR - header, 200 bytes

| Field | Start | End | Length | Picture | Holds |
|---|---:|---:|---:|---|---|
| `REGEXT-REC-TYPE` | 1 | 3 | 3 | `PIC X(3)` | `HDR` |
| `REGEXT-FORMAT-ID` | 4 | 15 | 12 | `PIC X(12)` | `TB-REGEXT-V1` |
| `REGEXT-INSTITUTION` | 16 | 26 | 11 | `PIC X(11)` | The reporting institution's BIC |
| `REGEXT-BUSINESS-DATE` | 27 | 34 | 8 | `PIC 9(8)` | `CCYYMMDD`, the date reported on |
| `REGEXT-POSITION` | 35 | 52 | 18 | `PIC 9(18)` | The ledger position - an `audit_record.seq` |
| `REGEXT-CHAIN-HASH` | 53 | 116 | 64 | `PIC X(64)` | The audit chain head hash at that position |
| `FILLER` | 117 | 200 | 84 | `PIC X(84)` | - spare |

`REGEXT-POSITION` and `REGEXT-CHAIN-HASH` are what make this file reproducible and traceable at the
same time. The position says which postings were in scope; the hash says which audit chain those
postings belong to, so a file cannot be silently re-cut against a restored database whose history
diverged. A verifier holding both can walk the chain to that sequence and get the same head hash, or
find out that it cannot.

There is no generation timestamp, deliberately. A wall clock in the body would make byte-identical
reruns impossible by construction, and the run instant belongs in the manifest beside the file.

## ACC - account detail, 200 bytes

| Field | Start | End | Length | Picture | Holds |
|---|---:|---:|---:|---|---|
| `REGEXT-REC-TYPE` | 1 | 3 | 3 | `PIC X(3)` | `ACC` |
| `REGEXT-ACCT-REF` | 4 | 37 | 34 | `PIC X(34)` | `Account.accountRef` |
| `REGEXT-CUST-REF` | 38 | 71 | 34 | `PIC X(34)` | `Account.customerRef` |
| `REGEXT-ACCT-TYPE` | 72 | 87 | 16 | `PIC X(16)` | `Account.accountType` |
| `REGEXT-CURRENCY` | 88 | 90 | 3 | `PIC X(3)` | `Account.currency` |
| `REGEXT-STATUS` | 91 | 106 | 16 | `PIC X(16)` | `Account.status` |
| `REGEXT-BOOKED-SIGN` | 107 | 107 | 1 | `PIC X(1)` | `+` or `-` |
| `REGEXT-BOOKED-MINOR` | 108 | 122 | 15 | `PIC 9(15)` | Booked balance, absolute, in minor units |
| `REGEXT-CURRENCY-SCALE` | 123 | 123 | 1 | `PIC 9(1)` | ISO 4217 scale of `REGEXT-CURRENCY` |
| `REGEXT-OPENED-DATE` | 124 | 131 | 8 | `PIC 9(8)` | `CCYYMMDD` |
| `REGEXT-MOVEMENT-COUNT` | 132 | 141 | 10 | `PIC 9(10)` | Postings for this account within the position |
| `FILLER` | 142 | 200 | 59 | `PIC X(59)` | - spare |

`REGEXT-ACCT-REF` and `REGEXT-CUST-REF` are 34 because that is what `account.reference` and
`account.customer_ref` are declared as in the ledger schema - the width of an IBAN. `REGEXT-ACCT-TYPE`
and `REGEXT-STATUS` are 16 for the same reason, even though the longest value either can currently
hold is `LIABILITY` at nine characters and `BLOCKED` at seven. A field sized to today's longest
enumeration value truncates silently the day a value is added, and a truncated status is a wrong
status rather than a missing one.

The sign is a separate character rather than a leading `-`, so the digits are always a fixed 15 and a
reader can slice the field without parsing it. A credit-normal account with money in it reports `+`:
the sign is the signed effect on the account under its own normal balance, not the side of the
postings.

## TRL - trailer, 200 bytes

| Field | Start | End | Length | Picture | Holds |
|---|---:|---:|---:|---|---|
| `REGEXT-REC-TYPE` | 1 | 3 | 3 | `PIC X(3)` | `TRL` |
| `REGEXT-ACCOUNT-COUNT` | 4 | 13 | 10 | `PIC 9(10)` | `ACC` records in this file |
| `REGEXT-HASH-TOTAL` | 14 | 31 | 18 | `PIC 9(18)` | Sum of every `REGEXT-BOOKED-MINOR` |
| `REGEXT-CURRENCY-COUNT` | 32 | 34 | 3 | `PIC 9(3)` | Distinct currencies among the `ACC` records |
| `REGEXT-BUSINESS-DATE` | 35 | 42 | 8 | `PIC 9(8)` | `CCYYMMDD`, repeated from the header |
| `FILLER` | 43 | 200 | 158 | `PIC X(158)` | - spare |

`REGEXT-HASH-TOTAL` is a **control total, not an amount**. It sums absolute minor units across every
currency, which is meaningless as money and exactly right as a check: a lost, duplicated or edited
detail record changes it, and no exchange rate is needed to recompute it. A receiver that adds it up
and compares is doing the only thing a trailer is for. It is 18 digits against 15-digit amounts so
that a file of ordinary size cannot overflow it; a writer whose total does not fit must fail rather
than wrap, because a wrapped control total agrees with nothing and looks fine.

`REGEXT-BUSINESS-DATE` is repeated from the header so that a header and a trailer from different runs
cannot be spliced into one plausible file. It costs eight bytes and removes an entire class of
silent corruption.
