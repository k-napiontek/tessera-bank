# Copybooks

**Stratum 0** | **Built by WP-03**

Fixed-width record layouts, included by every COBOL program so a field offset is defined exactly once: `ACCTREC.CPY` (account master), `MOVEREC.CPY` (movement), `REJREC.CPY` (reject).

These must stay identical to [`contracts/copybook/`](../../contracts/copybook/). If a layout needs to change, the contract changes first.

