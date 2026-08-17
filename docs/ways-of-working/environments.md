# Environments

> **STUB.** Outline only. Filled by **WP-18**.

The environment ladder, what is tested at each rung, who signs off, and what data each may hold. The data rules here are a GDPR and DORA obligation, not a convention.

## Planned contents

- DEV - developer environment, synthetic data, no sign-off
- SIT - system integration testing across tiers, synthetic data
- UAT - business acceptance, masked data, business sign-off required
- PREPROD - production-like, masked data, performance and resilience testing
- PROD - live, real data, change advisory board approval required
- **Production data never flows downward.** Masking and synthetic generation per rung
- Access model per environment and who holds it
- Note: this repository has one environment and one actor - see [control-exceptions.md](control-exceptions.md)
