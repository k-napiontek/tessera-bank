// Package metrics measures what an operator needs in order to answer "is the edge healthy" without
// reading a log.
//
// Every label is bounded. The route label is the route's class from the routing table - never the
// path, which carries an account reference and would give Prometheus one time series per account.
// Unbounded label cardinality is the standard way to take a monitoring system down, and it is
// discovered when the monitoring is needed most.
package metrics

import (
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

// Metrics holds the instruments and the registry they are registered in.
//
// The registry is built here rather than taken from prometheus.DefaultRegisterer: a package-level
// registry is global state, and two tests that both build a gateway would collide on it.
type Metrics struct {
	registry *prometheus.Registry

	requests *prometheus.CounterVec
	duration *prometheus.HistogramVec
	refusals *prometheus.CounterVec
	upstream *prometheus.CounterVec
}

// New builds the instruments.
func New() *Metrics {
	registry := prometheus.NewRegistry()
	registry.MustRegister(
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
	)

	m := &Metrics{
		registry: registry,
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "tessera_gateway_requests_total",
			Help: "Requests served, by route class, method and status.",
		}, []string{"route", "method", "status"}),
		duration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "tessera_gateway_request_duration_seconds",
			Help: "How long the edge took to answer, including the ledger.",
			// Buckets chosen for a request that crosses one network hop and a database, with the
			// tail placed where a customer starts to notice.
			Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
		}, []string{"route"}),
		refusals: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "tessera_gateway_refusals_total",
			Help: "Requests the edge refused itself, by reason. A ledger failure is not one of these.",
		}, []string{"reason"}),
		upstream: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "tessera_gateway_upstream_failures_total",
			Help: "Calls to the ledger that produced no usable answer, by kind.",
		}, []string{"kind"}),
	}
	registry.MustRegister(m.requests, m.duration, m.refusals, m.upstream)
	return m
}

// Handler serves the exposition format. It belongs on the administrative listener: metrics name
// internal routes and traffic volumes, and neither is a customer's business.
func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

// Middleware counts and times every request.
func (m *Metrics) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		recorder := &statusWriter{ResponseWriter: w, status: http.StatusOK}

		next.ServeHTTP(recorder, r)

		// The route is read after the chain has run, because the routing middleware is what puts it
		// on the context. A request refused before routing is measured as "unrouted", which is
		// exactly what it was.
		route := "unrouted"
		if matched, ok := routing.FromContext(r.Context()); ok {
			route = matched.Class
		}

		m.requests.WithLabelValues(route, r.Method, strconv.Itoa(recorder.status)).Inc()
		m.duration.WithLabelValues(route).Observe(time.Since(started).Seconds())

		if reason := refusalReason(recorder.status); reason != "" {
			m.refusals.WithLabelValues(reason).Inc()
		}
		if kind := upstreamKind(recorder.status); kind != "" {
			m.upstream.WithLabelValues(kind).Inc()
		}
	})
}

// refusalReason names the statuses the edge produces on its own. A 4xx from the ledger reaches the
// client through the proxy and is counted in requests_total like any other answer; only the ones
// the gateway decides are counted here, so the two questions - "is the edge refusing traffic" and
// "is the ledger refusing traffic" - stay separable.
func refusalReason(status int) string {
	switch status {
	case http.StatusUnauthorized:
		return "unauthenticated"
	case http.StatusForbidden:
		return "forbidden"
	case http.StatusTooManyRequests:
		return "rate_limited"
	case http.StatusRequestEntityTooLarge:
		return "payload_too_large"
	case http.StatusNotFound, http.StatusMethodNotAllowed:
		return "no_route"
	default:
		return ""
	}
}

func upstreamKind(status int) string {
	switch status {
	case http.StatusGatewayTimeout:
		return "timeout"
	case http.StatusBadGateway:
		return "unusable"
	default:
		return ""
	}
}

// statusWriter remembers the status the chain produced.
type statusWriter struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (w *statusWriter) WriteHeader(status int) {
	if w.wrote {
		return
	}
	w.status = status
	w.wrote = true
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if !w.wrote {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(b)
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
