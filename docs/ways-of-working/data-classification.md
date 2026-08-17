# Data classification and handling

Binding. The rules below apply to code, tests, fixtures, logs, sample files, documentation and
commit messages alike.

## The one-line version

**There is no real personal data in this repository, and none may be added.** Everything is
synthetic and generated.

## Classification

| Class | Definition | Where it may appear here |
|---|---|---|
| **Public** | Intended for anyone | Documentation, contracts, source code |
| **Internal** | Operational detail, not sensitive | Runbooks, architecture notes |
| **Confidential** | Commercially sensitive | Nothing in this repository |
| **Restricted** | Personal data, authentication material, account holder detail | **Nothing, ever** |

Anything that would be Restricted in a real bank is represented here by synthetic equivalents.

## What counts as personal data

Under GDPR, personal data is anything relating to an identifiable living person - which is broader
than most engineers assume. In this domain it includes: names, addresses, dates of birth, national
identifiers (PESEL, NIN, SSN), phone numbers, email addresses, IP addresses, device identifiers,
card numbers, IBANs tied to an individual, and any combination of attributes sufficient to single
someone out.

**An account number alone is a pseudonymous identifier, not anonymous data.** Pseudonymised data is
still personal data under GDPR. It is used here because it is the minimum the ledger genuinely needs,
not because it is exempt.

## Rules

1. **Synthetic only.** Test data comes from the generators in the repository. Never copy data from a
   real system, and never hand-write data that resembles a real person - "Jan Kowalski, ul. Marszałkowska 1"
   is not synthetic just because you invented it. Generated names must be obviously generated.
2. **Never log personal data.** Log account references and correlation ids. Not names, not addresses,
   not national identifiers, not card numbers, not tokens, not authentication material. A log line is
   the single most common place personal data escapes a system, because logs are copied, shipped and
   retained far more freely than databases.
3. **Never put personal data in an exception message or an error response.** Error paths are the
   second most common leak, and they are usually the least tested.
4. **Never commit credentials.** No passwords, API keys, tokens or private keys, including in test
   fixtures and including "obviously fake" ones - they train the wrong habit and defeat secret
   scanning.
5. **Production data never flows downward.** In the environment ladder, DEV and SIT use synthetic
   data and UAT and PREPROD use masked data. Production data never enters a lower environment. See
   [`environments.md`](environments.md).
6. **Minimise by design.** A service holds the least personal data it needs. `ledger-core` holds
   account references and no customer identity at all - the join to a person happens in
   `customer-master`, deliberately, so most of the estate is out of scope for personal data entirely.

## Retention and erasure

The GDPR right to erasure meets a genuine architectural obstacle here, and this repository confronts
it rather than pretending it away.

The ledger is **append-only** and the audit trail is **hash-chained**. Deleting a row would break the
chain and destroy the tamper-evidence that makes the audit trail worth having. Banking law also
requires transaction records to be retained for years, which is a competing legal obligation, not an
inconvenience.

The resolution used here, which is what the industry does:

- **The ledger retains no personal data** - only account references. There is nothing in it to erase.
- **Personal data lives in `customer-master`**, where erasure is genuinely possible.
- **Erasure is by pseudonymisation and crypto-shredding**, not deletion: the personal data is
  destroyed or its key discarded, while the transaction record and its account reference survive
  intact for the statutory retention period.

Documented in full in [`../compliance/gdpr-data-map.md`](../compliance/gdpr-data-map.md).

## Verification

Every work package's Definition of Done includes a personal-data check. Where a package produces
logs or reports, its verification must include grepping the actual output - not the intention - for
anything resembling personal data.
