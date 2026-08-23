# WP-03 - Mainframe copybooks and synthetic data

| | |
|---|---|
| **Ticket** | TB-1003 |
| **Branch** | `feat/TB-1003-mainframe-data` |
| **Stratum** | 0 - COBOL-85, ~1995 |
| **Depends on** | WP-02 |
| **Status** | `Not started` |

## Objective

Establish the mainframe data layer: the copybooks that define every fixed-width record, and the
synthetic account master and movement files the batch programs will read. Getting the record layouts
and the packed-decimal representation exactly right here is what makes every later COBOL and Java
interop task tractable, because both sides will encode against these definitions.

## In scope

> **The `ACCTREC` sketch below predates the canonical data model and is superseded by it** - the
> contract in [`contracts/copybook/`](../../../contracts/copybook/README.md) is what was built, and
> the Tasks section settles it in full under *Three contradictions settled before task 1*. The
> original text stands rather than being rewritten, so what was planned and what was built stay
> separately visible: a work package is the record of a decision, not a description of the current
> tree. Annotated here by WP-18b's documentation pass, closing **F-14**.

- `ACCTREC.CPY` - account master record: account number `PIC X(10)`, currency `PIC X(3)`, balance
  `PIC S9(13)V99 COMP-3`, status `PIC X(1)`, last movement date `PIC 9(8)`.
- `MOVEREC.CPY` - movement record: account number, amount, direction, value date, reference,
  originating system.
- `REJREC.CPY` - reject record: the movement plus a reason code.
- A synthetic account master file and a day of synthetic movements, generated rather than hand-typed.
- The generator script that produces them, so the data can be regenerated and varied.
- Documentation of the COMP-3 packed-decimal representation, including the sign nibble, since the
  Java side in WP-11 must reproduce it byte for byte.

## Out of scope

- Any COBOL program - `ACCTPOST` is WP-04, `EODREPT` is WP-05.
- JCL - WP-05.
- Any Java-side encoder or decoder - WP-11.

## Constraints

- COBOL-85 fixed format: columns 1-6 sequence, 7 indicator, 8-11 area A, 12-72 area B. Tabs are
  forbidden - they destroy column alignment and the compiler rejects the source.
- Record layouts must match `contracts/copybook/` exactly. If they need to differ, the contract
  changes first.
- **All data is synthetic.** No name, address or identifier may resemble a real person.
- Amounts must include the awkward cases deliberately: zero, the maximum representable value,
  negative balances on overdraft-permitted accounts, and a currency with a non-2 decimal scale.

## Tasks

Seven tasks, roughly one commit each. Each is test-first: the failing check, then the thing that
makes it pass.

### Three contradictions settled before task 1

Reading this package against the contracts merged in TB-1002 turns up three disagreements. All are
resolved from documents that already exist, and are recorded here so no task rediscovers them.

**1. The In-scope sketch above is superseded.** It describes `ACCTREC` with an account number of
`PIC X(10)` and a status of `PIC X(1)`, and lists no customer reference, account type or available
balance. [`contracts/copybook/ACCTREC.CPY`](../../../contracts/copybook/ACCTREC.CPY), merged in
TB-1002, says `ACCT-REF PIC X(16)`, `ACCT-STATUS PIC X(7)`, and carries all three. The Constraints
section above already decides this: *"Record layouts must match `contracts/copybook/` exactly."* The
contract wins. The In-scope text was written before the canonical data model existed and is kept only
as a record of the original intent.

**2. `mainframe/copybook/` and `contracts/copybook/` hold the same files.** The copybooks COBOL
compiles against live in `mainframe/copybook/`; the contract lives in `contracts/copybook/`. REQ-MF-001
requires a layout to be defined once, so the two are asserted **byte-identical** by a check that runs
in both directions - a file in one and not the other is a failure too. An enforced invariant rather
than a hopeful sentence in a README.

**3. A non-scale-2 currency cannot exist in the account master.** The Constraints above ask for
fixtures including *"a currency with a non-2 decimal scale"*, but the canonical data model states
stratum 0 carries scale-2 currencies only: `PIC S9(13)V99 COMP-3` hard-codes two decimals, so a JPY
balance would be misstated by a factor of 100.

Such a currency therefore appears **only as a movement destined for rejection**, never as an account
in the master. The integration tier rejects it before it reaches stratum 0, and the mainframe
validates it again on arrival - defence in depth, because a 1995 core does not trust its feeds. This
gives WP-04's rejection path a real fixture without pretending the master can hold what it cannot
represent.

### 1. Copybooks, with an identity check

Copy `ACCTREC.CPY`, `MOVEREC.CPY` and `REJREC.CPY` from `contracts/copybook/` into
`mainframe/copybook/`, plus `mainframe/copybook/check-identity.py` asserting the two directories hold
byte-identical files and the same set of them.

The check comes first and is seen to fail before the files are copied.
`feat(mainframe): add copybooks with contract identity check [TB-1003]`

### 2. Compile the copybooks

A harness under `mainframe/copybook/` that `COPY`s all three into its working storage and does
nothing else, compiled with `cobc` in COBOL-85 fixed format. It proves the layouts are valid COBOL
rather than plausible-looking text - a `PIC` clause the compiler rejects is a defect the column
arithmetic in TB-1002 cannot see.

Not an application program: `ACCTPOST` is WP-04 and `EODREPT` is WP-05.
`test(mainframe): compile copybooks with gnucobol [TB-1003]`

### 3. The COMP-3 encoder

`mainframe/data/comp3.py` - packed decimal, two digits per byte with the sign in the final nibble,
`0x0C` positive and `0x0D` negative, zero always positive.

Tested against the worked examples in
[`canonical-data-model.md`](../../architecture/canonical-data-model.md): a positive amount, a negative
amount, zero, and the maximum representable value. Those four byte strings are the agreement WP-11's
Java encoder will be held to, so they are asserted here as literal bytes, not recomputed by the same
arithmetic that produced them.
`feat(mainframe): add comp-3 encoder for synthetic data [TB-1003]`

### 4. The account master generator

`mainframe/data/generate.py`, producing 100-byte `ACCTREC` records, deterministic for a given seed.

Includes the awkward balances deliberately: zero, the maximum representable value, and a negative
balance on an account whose overdraft policy permits it. A generator that only emits comfortable
numbers tests nothing.

Every reference comes from the canonical patterns, and nothing resembling a person appears anywhere -
no names, no addresses, no identifiers. Output goes to `mainframe/data/out/`, which is gitignored: the
generator is committed, its output never is.
`feat(mainframe): generate synthetic account master [TB-1003]`

### 5. The movement generator

120-byte `MOVEREC` records: both legs of each transfer, sharing a transfer reference, leg `01` the
debit and leg `02` the credit, sorted by account reference as the match-merge in WP-04 will require.

Plus the reject fixture from resolution 3 above - one movement in a currency of scale 3, which WP-04
must reject rather than apply.
`feat(mainframe): generate synthetic movements [TB-1003]`

### 6. The conformance check

`mainframe/data/check-records.py` - reads generated records back and asserts every field against
[`contracts/copybook/column-map.md`](../../../contracts/copybook/column-map.md): start position,
length, and for COMP-3 fields the decoded value and the sign nibble.

This is the conformance check WP-02 wired into this package. It must be seen to fail on drifted data
before it is accepted.
`test(mainframe): assert generated records match the contract [TB-1003]`

### 7. Documentation

`mainframe/README.md`, `mainframe/copybook/README.md` and `mainframe/data/README.md` updated to
describe what exists; the traceability matrix updated with REQ-MF-001, REQ-MF-002 and REQ-DP-001; and
`docs/consuming-this-repo.md` given the GnuCOBOL prerequisite.
`docs(mainframe): record WP-03 requirements and prerequisites [TB-1003]`

### On the generator being Python

The COBOL-85 rule governs `.CBL` and `.CPY` files. The generator is tooling that produces stratum 0
data; it is not stratum 0 source, and writing it in COBOL would make it harder to read without making
it more authentic. Standard library only, so it runs from a clean checkout with nothing installed.

## Definition of Done

- [ ] Copybooks compile when included by a trivial COBOL program.
- [ ] The generator produces deterministic output for a given seed.
- [ ] A hex dump of a generated master record matches the documented COMP-3 layout byte for byte.
- [ ] No data resembles a real person.

## Verification

Compile a throwaway COBOL program that copies each copybook, confirming the layouts are valid. Dump
generated records with `od -c` / `xxd` and check field offsets and the packed-decimal sign nibble
against the documented layout.

**Contract conformance (WP-02).** Hex-dump a generated master record and a generated movement
record and check every field against [`../../../contracts/copybook/column-map.md`](../../../contracts/copybook/column-map.md),
byte for byte. The COMP-3 amounts must cover a positive value, a negative value and zero, with
sign nibbles `0x0C`, `0x0D` and `0x0C` respectively.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-MF-001 Record layouts are defined once and shared | `mainframe/copybook/` |
| REQ-MF-002 Money on the mainframe is packed decimal, not binary or text | `ACCTREC.CPY` |
| REQ-DP-001 All test data is synthetic | the generator |
