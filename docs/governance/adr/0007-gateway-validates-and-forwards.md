# ADR 0007 - The gateway validates the customer's token and forwards it unchanged

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

Two documents disagreed about where the bearer token that reaches `ledger-core` comes from.

`contracts/openapi/ledger-core.yaml` described it as "issued by `edge/api-gateway` after it has
authenticated the customer". WP-12's Out of scope section says the opposite: "the gateway validates
tokens, it does not issue them".

The disagreement is not cosmetic. If the gateway issues a token, it holds a signing key, and an
edge component that holds a signing key can mint any identity in the bank. If it forwards the
caller's, the ledger must be able to validate that token itself, and the identity provider is
somewhere else entirely.

The question had to be settled before a line of the authentication code was written, because the two
answers produce different components.

## Decision

**The gateway validates the caller's token against configured public keys and forwards it
unchanged. It mints nothing.**

The contract was corrected first, in the commit before the implementation, per the
contracts-change-first rule.

What validation means here, precisely:

- Signature, against one of the public keys in a PEM file the deployment supplies.
- Algorithm, pinned to an asymmetric set. This is the whole defence against algorithm confusion: a
  verifier that honours the token's own `alg` header will accept HS256 signed with the RSA *public*
  key, which is public, so anybody can mint a token. `alg: none` is absent from the list for the
  same reason.
- `exp` - required, so that a token without one cannot be valid forever - plus `nbf`, `iss` and
  `aud`, with 30 seconds of leeway for clock skew and not a second of grace beyond it.
- A non-empty `sub`. Without a subject there is nobody to rate limit, nobody to authorise and nobody
  to name in an audit trail.

More than one key is accepted, so that a key rotation is not an outage: the outgoing and the incoming
key both verify while tokens signed by either are still in flight. A **private** key in that file
fails the boot rather than being ignored - it would mean somebody had put a signing key at the edge.

## Consequences

**What becomes easier.** The gateway holds no secret at all: public keys, a path, and nothing that
would matter if the process were compromised. The ledger keeps its own security requirement rather
than inheriting the gateway's word for it, which is why the OpenAPI document still declares
`bearerAuth` on every operation.

**What becomes harder.** The estate now depends on an identity provider that WP-12 does not build and
this repository does not contain. The tests mint their own tokens with a generated key pair, and the
live verification does the same. That is honest - the gateway's job is to validate, and validating a
token minted by a test harness exercises exactly the same code path as validating one from a real
issuer - but it does mean nothing here proves interoperability with any particular provider.

**What is deliberately not done.** No token exchange, no downstream identity header, no re-signing.
`ledger-api` does not yet validate the token it receives; WP-08 built no authentication, and adding
it there is a change to another package's component. It is recorded as a follow-up rather than done
quietly here, because "the gateway checked it" is precisely the assumption a defence in depth is
supposed not to make.

## Alternatives considered

**Token exchange: mint a short-lived internal token.** What the contract's original wording implied,
and what a large bank with many downstreams often does. Rejected because it contradicts the work
package, and because it puts a signing key at the most exposed point in the estate.

**Forward the token and leave the contract wording alone.** Cheaper by one commit, and it leaves a
document that is actively wrong in the one place a reader goes to find out how authentication works.
