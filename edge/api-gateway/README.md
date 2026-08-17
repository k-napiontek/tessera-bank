# api-gateway

**Stratum 4** | **Go** | **Built by WP-12**

The single entry point: authentication, coarse authorisation, rate limiting, correlation id generation and propagation, so no downstream service has to implement them.

**No business logic.** If the gateway needs to understand what a transfer is, the design is wrong. Every downstream call carries a timeout - an edge component without timeouts turns one slow service into a total outage.

