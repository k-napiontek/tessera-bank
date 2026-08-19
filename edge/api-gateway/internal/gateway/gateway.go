// Package gateway assembles the middleware into the two handlers the process serves.
//
// The order of the chain is the design. Reading it from the outside in:
//
//	correlation   every line and every response carries an id, including the ones refused below
//	logging       one access line per request, with that id on it
//	metrics       counts what the edge refused as well as what it forwarded
//	authentication  nothing past this point runs for an unauthenticated caller
//	routing       the request becomes a known operation, or stops here
//	authorisation the token's scopes are checked against that operation
//	rate limiting keyed by subject and route, both of which exist only after the two above
//	proxy         the ledger is called
//
// Each step depends on what the one outside it established, and moving any of them changes what the
// gateway enforces. Rate limiting above authentication would key on nothing and let one caller
// exhaust the limit for everybody; routing above authentication would let an unauthenticated caller
// probe which paths exist.
//
// The customer-facing listener and the administrative one are separate handlers on separate ports.
// The ledger serves its actuator endpoints beside its API, and this gateway exists partly so that
// arrangement stops at the edge; repeating it here would be a poor joke.
package gateway

import (
	"log/slog"
	"net/http"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/authz"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/config"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/health"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/metrics"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/proxy"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/ratelimit"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

// Dependencies are the parts the process builds once and the handlers share.
type Dependencies struct {
	Config    config.Config
	Verifier  *auth.Verifier
	Limiter   *ratelimit.Limiter
	Metrics   *metrics.Metrics
	Log       *slog.Logger
	Transport http.RoundTripper
}

// Public is the handler customers reach.
func Public(deps Dependencies) http.Handler {
	forward := proxy.New(proxy.Settings{
		Ledger:           deps.Config.LedgerURL,
		Timeout:          deps.Config.DownstreamTimeout,
		Attempts:         deps.Config.DownstreamAttempts,
		MaxRequestBytes:  deps.Config.MaxRequestBytes,
		MaxResponseBytes: deps.Config.MaxResponseBytes,
		Transport:        deps.Transport,
	})

	handler := ratelimit.Middleware(deps.Limiter)(forward)
	handler = authz.Middleware(handler)
	handler = routing.Middleware(routing.Routes())(handler)
	handler = auth.Middleware(deps.Verifier)(handler)
	handler = deps.Metrics.Middleware(handler)
	handler = logging.Middleware(deps.Log)(handler)
	return correlation.Middleware(handler)
}

// Admin is the handler an orchestrator and a Prometheus scraper reach. It is never exposed to a
// customer: readiness names the state of the estate, and the metrics name its routes and volumes.
func Admin(deps Dependencies, readiness health.Probe) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/healthz", health.Handler(readiness))
	mux.Handle("/readyz", health.Handler(readiness))
	mux.Handle("/metrics", deps.Metrics.Handler())
	return mux
}
