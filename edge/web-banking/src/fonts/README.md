# fonts

**Figtree**, variable weight axis 400-800, by Erik D. Kennedy. SIL Open Font License 1.1 - the full
text is in [`OFL.txt`](OFL.txt), and OFL-1.1 is on the approved list in
[`dependency-policy.md`](../../../../docs/ways-of-working/dependency-policy.md).

Two subsets, taken from Google Fonts' own `woff2` builds (`v9`) and served from this application's
origin:

| File | Range | Size |
|---|---|---|
| `figtree-latin.woff2` | `U+0000-00FF` and the punctuation the interface uses | 20 KB |
| `figtree-latin-ext.woff2` | Latin Extended-A onwards, which is where Polish diacritics live | 10 KB |

## Why the file is here and not on a CDN

This page holds a bearer token. A `<link>` to `fonts.googleapis.com` would tell a third party the
URL of every banking page a customer opens, on every visit, and would put a stylesheet this
repository does not control in front of the interface. Thirty kilobytes committed once is the
cheaper side of that trade.

## Why this face and not another

`.amount` sets `font-variant-numeric: tabular-nums`, which is load-bearing rather than
decorative - digits that line up down a column make a misread figure harder to produce. A face
without a `tnum` feature would leave that declaration inert and nothing would look broken, which is
the worst way for a control to fail. Figtree's `GSUB` table carries `tnum`; it was checked before
the file was committed, not assumed.
