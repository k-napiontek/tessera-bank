package proxy

import (
	"context"
	"errors"
	"net"
	"net/http"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/problem"
)

// failure says what went wrong on the way to the ledger, and how the caller should be told.
type failure struct {
	kind   problem.Type
	status int
	err    error
}

func (f *failure) Error() string { return f.err.Error() }

// send performs one attempt against the ledger.
//
// Every attempt carries a deadline of its own. A downstream call without one is how a single slow
// dependency becomes a total outage: connections pile up at the edge, each holding a goroutine and
// a socket, until the gateway is the thing that is down.
func send(r *http.Request, transport http.RoundTripper, settings Settings, attempts int, body []byte) (*http.Response, error) {
	ctx, cancel := context.WithTimeout(r.Context(), settings.Timeout)
	defer cancel()

	outbound, err := build(ctx, r, settings.Ledger, body)
	if err != nil {
		return nil, &failure{kind: problem.UpstreamUnusable, status: http.StatusBadGateway, err: err}
	}

	response, err := transport.RoundTrip(outbound)
	if err != nil {
		return nil, classify(ctx, err)
	}
	return response, nil
}

// classify turns a transport error into the answer the customer gets.
func classify(ctx context.Context, err error) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) || errors.Is(err, context.DeadlineExceeded) {
		// 504, not 502: the ledger may well have applied the request and simply not answered in
		// time. Saying "could not be reached" would be a claim the gateway cannot support.
		return &failure{kind: problem.UpstreamTimeout, status: http.StatusGatewayTimeout, err: err}
	}
	return &failure{kind: problem.UpstreamUnusable, status: http.StatusBadGateway, err: err}
}

// refuse writes the failure as a Problem document.
func refuse(w http.ResponseWriter, r *http.Request, err error) {
	kind, status := problem.UpstreamUnusable, http.StatusBadGateway
	var known *failure
	if errors.As(err, &known) {
		kind, status = known.kind, known.status
	}

	// The address and the transport error go to the log; the customer gets the one sentence that is
	// true and actionable.
	logging.FromContext(r.Context()).Error("the ledger could not be called",
		"error", err.Error(), "status", status)

	detail := "The ledger could not be reached. The request may not have been applied."
	if kind == problem.UpstreamTimeout {
		detail = "The ledger did not answer in time. The request may still have been applied."
	}
	problem.Write(w, r, status, kind, detail)
}

// defaultTransport is the connection pool the gateway uses when the configuration does not supply
// one. Every stage has a bound: a stage without one is a queue that grows until the process dies.
func defaultTransport(timeout time.Duration) http.RoundTripper {
	return &http.Transport{
		DialContext: (&net.Dialer{
			Timeout:   timeout,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		TLSHandshakeTimeout:   timeout,
		ResponseHeaderTimeout: timeout,
		ExpectContinueTimeout: time.Second,
		// The gateway talks to one service, so the pool is sized for it rather than for a general
		// client. The Go default of two idle connections per host makes every request after the
		// first two pay for a new handshake.
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 100,
		IdleConnTimeout:     90 * time.Second,
		ForceAttemptHTTP2:   true,
	}
}
