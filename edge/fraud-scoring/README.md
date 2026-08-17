# fraud-scoring

**Stratum 4** | **Python 3.12** | **Built by WP-13**

Scores transfers for fraud risk off the Kafka event stream and publishes a decision. Asynchronous by design: a slow or unavailable model must never be able to stop customers moving their own money.

**Decisions must be explainable** - each names the rules that fired and the rule-set version that produced it. A score with no reason attached is unusable in a regulated context, where a customer can demand to know why a payment was flagged.

