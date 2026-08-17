# web-banking

**Stratum 4** | **TypeScript + React** | **Built by WP-14**

The customer application: accounts, balances, statements and internal transfers.

Two details that banking UIs habitually get wrong and that this one must get right: the client generates one **idempotency key per transfer attempt and reuses it on retry** (a fresh key on retry moves money twice), and **booked and available balances are visibly distinct** (showing one number where a hold exists misleads the customer about what they can actually spend).

