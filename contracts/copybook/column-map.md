# Copybook column map

Every field of every fixed-width record, with its byte positions. **Positions are 1-based and
inclusive**, the convention COBOL and every hex dump use.

Derived from [`../../docs/architecture/canonical-data-model.md`](../../docs/architecture/canonical-data-model.md).
Where this map and the model disagree, the model wins and this map is regenerated.
`../check-copybook-offsets.py` asserts that the `.CPY` files still produce exactly these numbers.

## How a length is worked out

| Picture | Bytes | Rule |
|---|---|---|
| `PIC X(n)` | n | One character per byte, ASCII, space-padded. |
| `PIC 9(n)` | n | Unpacked display digits, one per byte. Zero-padded on the left. |
| `PIC S9(p)V9(s) COMP-3` | `ceil((p + s + 1) / 2)` | Packed decimal: two digits per byte, plus one nibble for the sign. |

`PIC S9(13)V99 COMP-3` is therefore `ceil((13 + 2 + 1) / 2)` = **8 bytes**. `V` is an implied decimal
point and occupies nothing.

## ACCTREC - account master, 100 bytes

| Field | Start | End | Length | Picture | Canonical field |
|---|---:|---:|---:|---|---|
| `ACCT-REF` | 1 | 16 | 16 | `PIC X(16)` | `Account.accountRef` |
| `ACCT-CUST-REF` | 17 | 28 | 12 | `PIC X(12)` | `Account.customerRef` |
| `ACCT-TYPE` | 29 | 37 | 9 | `PIC X(09)` | `Account.accountType` |
| `ACCT-CURRENCY` | 38 | 40 | 3 | `PIC X(03)` | `Account.currency` |
| `ACCT-STATUS` | 41 | 47 | 7 | `PIC X(07)` | `Account.status` |
| `ACCT-BOOKED-BAL` | 48 | 55 | 8 | `PIC S9(13)V99 COMP-3` | `Account.bookedBalance.amountMinor` |
| `ACCT-AVAIL-BAL` | 56 | 63 | 8 | `PIC S9(13)V99 COMP-3` | `Account.availableBalance.amountMinor` |
| `ACCT-OPENED-DATE` | 64 | 71 | 8 | `PIC 9(08)` | `Account.openedDate` |
| `ACCT-LAST-MOVE-DATE` | 72 | 79 | 8 | `PIC 9(08)` | `Account.lastMovementDate` |
| `FILLER` | 80 | 100 | 21 | `PIC X(21)` | - spare |

Both balances share `ACCT-CURRENCY`: an account holds exactly one currency for life, so storing the
code twice would create two places for it to disagree.

## MOVEREC - movement, 120 bytes

| Field | Start | End | Length | Picture | Canonical field |
|---|---:|---:|---:|---|---|
| `MOV-TRANSFER-REF` | 1 | 20 | 20 | `PIC X(20)` | `Movement.transferRef` |
| `MOV-LEG-NO` | 21 | 22 | 2 | `PIC 9(02)` | `Movement.legNo` |
| `MOV-ACCT-REF` | 23 | 38 | 16 | `PIC X(16)` | `Movement.accountRef` |
| `MOV-DIRECTION` | 39 | 39 | 1 | `PIC X(01)` | `Movement.direction` |
| `MOV-CURRENCY` | 40 | 42 | 3 | `PIC X(03)` | `Movement.amount.currency` |
| `MOV-AMOUNT` | 43 | 50 | 8 | `PIC S9(13)V99 COMP-3` | `Movement.amount.amountMinor` |
| `MOV-VALUE-DATE` | 51 | 58 | 8 | `PIC 9(08)` | `Movement.valueDate` |
| `MOV-POSTED-TS` | 59 | 72 | 14 | `PIC 9(14)` | `Movement.postedAt` |
| `MOV-REFERENCE` | 73 | 107 | 35 | `PIC X(35)` | `Movement.reference` |
| `FILLER` | 108 | 120 | 13 | `PIC X(13)` | - spare |

`MOV-AMOUNT` is always positive. `MOV-DIRECTION` carries the sign, so a hex dump never shows a
negative movement - only a debit one.

## REJREC - rejected movement, 200 bytes

| Field | Start | End | Length | Picture | Canonical field |
|---|---:|---:|---:|---|---|
| `REJ-MOVEMENT` | 1 | 120 | 120 | `PIC X(120)` | the whole of `MOVEREC` |
| `REJ-REASON-CODE` | 121 | 124 | 4 | `PIC X(04)` | `Rejection.reasonCode` |
| `REJ-REASON-TEXT` | 125 | 164 | 40 | `PIC X(40)` | `Rejection.reasonText` |
| `REJ-DETECTED-TS` | 165 | 178 | 14 | `PIC 9(14)` | `Rejection.detectedAt` |
| `FILLER` | 179 | 200 | 22 | `PIC X(22)` | - spare |

`REJ-MOVEMENT` is the 120 bytes of `MOVEREC` verbatim, not a restatement of its fields. A program
reading this file redefines those bytes with `MOVEREC`, which is why a rejected record can be fed
back into a later run without being re-encoded.

## Reading a record by hand

To find `MOV-AMOUNT` in a movement file with `xxd`, skip to the record, then to byte 43:

```
xxd -s $(( (RECNO - 1) * 120 + 42 )) -l 8 MOVEMENT.DAT
```

The 8 bytes that come back are packed decimal. The last nibble is the sign: `C` positive, `D`
negative. See the worked examples in the canonical data model.
