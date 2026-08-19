# web-banking

**Stratum 4** | **TypeScript + React + Vite** | **Built by WP-14, dressed by WP-19**

The customer application: accounts, balances, statements and internal transfers. Everything it does
goes through [`edge/api-gateway`](../api-gateway); it holds no address for the ledger and could not
reach it if it did.

```bash
make test-web     # Vitest against a mocked gateway - no network, no estate
make build-web    # tsc --noEmit under strict mode, then vite build
make lint-web     # eslint with type-aware rules, zero warnings tolerated
```

## Two things this application cannot do, and says so on the screen

Both are properties of the estate rather than of this component, and both are recorded as follow-ups
in [`STATUS.md`](../../docs/plan/STATUS.md). Neither is worked around silently, because a UI that
hides which part of it is scaffolding teaches its reader something false about the system behind it.

**Nothing in this estate issues a token.** [ADR 0007](../../docs/governance/adr/0007-gateway-validates-and-forwards.md)
records that the gateway validates a bearer token and mints none - an edge component holding a
signing key could mint any identity in the bank - and no other component issues one either. So the
sign-in screen asks for a token rather than a password, and says why. `scripts/dev-token.mjs` mints
one for the walkthrough below; it is a test fixture in the same sense a Testcontainers fixture is,
not a component of the bank.

**Nothing lists a customer's accounts.** Every account operation in
[`contracts/openapi/ledger-core.yaml`](../../contracts/openapi/ledger-core.yaml) takes a reference
the caller already holds, and the gateway's route table mirrors it. So the session is *told* its
account references at sign-in and the dashboard reads exactly those. The application enumerates
nothing it was not given.

## What it gets right on purpose

**Money is a `bigint` of minor units, never a `number`.** In JavaScript every `number` is the float
the estate's oldest rule forbids, and `amountMinor` is an `int64` - `Number.MAX_SAFE_INTEGER` is
roughly a ninth of what that holds, so an ordinary response can carry an amount a double rounds in
silence. Amounts are therefore read from the **source text** of the JSON number through the
reviver's `context.source`, and the decimal point is placed by slicing digits. `money.source.test.ts`
parses `money.ts` and fails on a division, a multiplication, a fractional literal, `parseFloat` or
`toFixed`, and it has been shown to fail when a division is planted. Same control as
[`batch/reporting/money.py`](../../batch/reporting), in a third language.

**One idempotency key per attempt, not per request.** It is minted when the customer confirms what
they are sending - the moment the request stops changing - and every retry reuses it: after a
rejection, after a timeout, after a dropped connection. Changing any field mints a new one, because
a different body under the same key is a `409` rather than a retry. Both halves are tested, because
only the pair proves the rule.

**A lost response is `PENDING`, and resolving it re-sends.** The ledger allocates `transferRef` and
`TransferRequest` carries no client reference, so a lost answer leaves this application holding
nothing but its key. There is nothing to poll. The only honest way to find out what happened is to
send the identical request under the identical key and let the ledger's idempotency store replay the
original outcome - a replay answers `200` whatever the original returned. "Check again" does that,
and the screen says it cannot send the money twice.

**Booked and available are two labelled figures, always.** Where they differ the card says how much
is held. A negative available balance prints honestly rather than flooring at zero, matching
`Balance.available()`. One number where a hold exists tells a customer they can spend money they
cannot, which is REQ-UI-003 stated as a defect.

**Every statement page is checked to foot.** Opening plus the movements equals closing, per page,
and one page's closing is the next page's opening - the property the contract calls self-proving.
A page that does not foot is reported to the customer rather than rendered as though it did. The
sign comes from the account type, not from the direction: a customer's current account is a
liability of the bank, so a `DEBIT` reduces it, and reading direction as a sign turns every customer
statement backwards.

**Its own routes stay clear of the gateway's.** The application is served on the gateway's origin,
so a client-side route that shares a prefix with an API path is a page that returns JSON on reload
or on a shared link - and clicking through the running app hides it entirely, because client-side
routing never asks the server. `routes.ts` names both sets and a test asserts they do not overlap.
The statement was at `/accounts/{ref}/statement` until the live walkthrough was opened directly at
that URL and answered with a problem document.

**The token lives in memory and nowhere else.** Not `localStorage`, not `sessionStorage`, not the
console, not the DOM after sign-in. Storage is readable by every script the page ever loads and
outlives the tab; React state dies with the page, which for a bearer token is the correct lifetime.
Four tests hold that line.

## Design

Built by WP-19, mobile first, in the visual language of Polish retail banking - because a customer
who already knows what a bank looks like should not have to spend attention learning this one. The
palette is read off PKO Bank Polski's own properties rather than recalled: `#003574` is the navy
iPKO puts on its primary action, `#CA171D` its red, `#DB912C` the amber `pkobp.pl` puts on a call to
action. None of PKO's mark, name or wordmark is reproduced; the palette is the reference and the
identity is Tessera's.

**No component library, no CSS framework, no icon package, no font service.** WP-14's rule was that
a dependency is justified rather than avoided and that four screens do not justify one; WP-19 added
nothing, and `package.json` is byte-identical to the one WP-14 left. The typeface is two `woff2`
files served from this application's own origin - a page holding a bearer token should not be
telling `fonts.googleapis.com` which banking screen a customer is on.

### One navigation, moved rather than duplicated

Under 640px it is a bar fixed to the bottom of the viewport, where a thumb reaches. Between 640 and
1024 it is a row under the app bar. At 1024 and above it is a rail down the left. All three are the
same `<nav>`, repositioned by a media query, because two would give the page two tab orders and
announce every destination twice - and a media query is not something a screen reader consults.

### The colours are computed, not judged

`src/styles/contrast.test.ts` reads `src/styles/tokens.css`, computes WCAG relative luminance for
every declared colour, and fails if a declared pair drops below 4.5:1 for text or 3:1 for a control
edge. Every token must appear in a pair or in a list of exemptions **with a stated reason**, so a
colour nobody thought about fails the build rather than shipping.

Same control as `money.source.test.ts`, aimed at a different failure. A stylesheet does not crash.
It ships a colour that looked fine to whoever picked it and that a reader with low vision cannot
make out, and nothing anywhere reports it. It is why `--amber-500` fills a shape and never carries a
word: PKO's call-to-action amber reaches 2.6:1 on white, and `--amber-800` exists because of it.

### The balance meter

REQ-UI-003 says an available balance is never presented as spendable when held, and the card has
always discharged that in a sentence. The meter shows the same thing as a proportion - the share of
the booked balance a hold has already committed - and it is an addition to the sentence rather than
a replacement for it. It renders nothing when the two balances agree, nothing when the account is
overdrawn and there is no positive whole to take a share of, and it carries an accessible name
stating the held amount, because a picture only a sighted reader can interpret would discharge the
requirement for some readers and not others.

It is the only ornament in the application. Everything else is flat: one hairline, one elevation on
the app bar, one 150ms transition, and `prefers-reduced-motion` turns that off.

### What the movements table does not do on a phone

It does not become a stack of `div`s. Four columns and a twenty-character reference do not fit a
390px screen - the table needs 470px and the phone offers 358 - and the usual responsive-table trick
costs the table role in the accessibility tree. So the table keeps its columns, its headers and its
semantics, and is given a labelled, focusable region to scroll inside. The page itself never scrolls
sideways at any width.

### One palette

The `prefers-color-scheme: dark` block WP-14 carried was removed rather than extended. PKO's own
interface is light only, and a second palette nobody looks at is a second palette nobody maintains.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `VITE_GATEWAY_URL` | `<origin>` | Base URL of `edge/api-gateway`. **No `/v1`** - that is the ledger's prefix and the gateway adds it when forwarding. |
| `TB_GATEWAY_ORIGIN` | unset | Development only. When set, Vite proxies `/accounts`, `/transfers` and `/holds` to that origin, keeping the browser on one origin as in production. |

Same origin by default, because in a real deployment this application is served behind the gateway -
which means no build-time configuration, and no way to point it at the ledger by accident. The
absence of a path prefix is deliberate and was got wrong first: the gateway serves the contract's
paths at its own root, and `/v1` belongs to the ledger behind it.

## Running it against a live estate

```bash
# 1. The ledger, on a PostgreSQL of your choosing.
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :services:ledger-api:bootRun

# 2. A key pair and a token. The public half is what the gateway will trust.
node edge/web-banking/scripts/dev-token.mjs --out /tmp/tessera-jwt-keys.pem

# 3. The gateway, in front of the ledger.
TB_GATEWAY_LEDGER_URL=http://localhost:8080/v1 \
TB_GATEWAY_JWT_ISSUER=https://issuer.tesserabank.example \
TB_GATEWAY_JWT_AUDIENCE=tessera-bank-ledger \
TB_GATEWAY_JWT_KEYS=/tmp/tessera-jwt-keys.pem \
TB_GATEWAY_LISTEN=:8081 \
go -C edge/api-gateway run ./cmd/gateway

# 4. This application, with Vite proxying the API paths to the gateway.
TB_GATEWAY_ORIGIN=http://localhost:8081 npm --prefix edge/web-banking run dev
```

`TB_GATEWAY_ORIGIN` proxies rather than pointing the client at another origin, because the gateway
sends no `Access-Control-Allow-Origin` and answers a preflight `OPTIONS` with `401` - it
authenticates everything, and in production this application is served behind it on one origin. The
proxy makes development the same shape rather than a shape that needs CORS the deployment lacks.

Then sign in with the token from step 2 and the references of accounts that exist in the ledger.
`scripts/walkthrough.sh` drives steps 1 to 3 and the API side of the journey, so the manual part is
only what has to be seen on a screen.

## Layout

| Path | What lives there |
|---|---|
| `src/money.ts` | Minor units and an ISO 4217 code. The rule, and the test that enforces it against the source. |
| `src/ledger.ts` | The one double-entry rule this tier needs: what a direction does to a balance, and whether a page foots. |
| `src/api/` | The typed client, and the RFC 9457 reader that understands both producers' problem types. |
| `src/session/` | The token and the account references, held in memory for the life of the tab. |
| `src/screens/` | Dashboard, statement, and the transfer journey with its five outcomes. |
| `src/shell/` | The app bar, the one navigation, and the mark. |
| `src/styles/` | Tokens, base, shell and components - and the test that holds the colours to WCAG. |
| `src/fonts/` | The typeface, its licence, and why it is committed rather than fetched. |
| `scripts/` | Development fixtures: a token minter and the live walkthrough. |
