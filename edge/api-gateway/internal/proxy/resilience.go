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

// retryPause is how long the gateway waits before a second attempt, giving a ledger that is
// restarting a moment to finish.
const retryPause = 50 * time.Millisecond

// failure says what went wrong on the way to the ledger, and how the caller should be told.
type failure struct {
	kind   problem.Type
	status int
	err    error
}

func (f *failure) Error() string { return f.err.Error() }

// send calls the ledger, retrying only where a replay cannot do harm.
//
// Every attempt carries a deadline of its own. A downstream call without one is how a single slow
// dependency becomes a total outage: connections pile up at the edge, each holding a goroutine and
// a socket, until the gateway is the thing that is down.
//
// Two conditions must both hold before a second attempt is made.
//
// The request must be replayable: a safe method, or one carrying an Idempotency-Key. A connection
// error can be raised after the ledger has read the request, so replaying a transfer that carries
// no key is how a customer is debited twice. The key is precisely what makes the replay safe - the
// ledger resolves it and returns the original answer instead of posting again.
//
// And the failure must be one the ledger cannot have acted on. A timeout is not: it means the
// ledger has the request and is struggling with it, and sending it again multiplies the load on a
// dependency at the moment it can least afford it. That is how a slow dependency becomes a failed
// one.
//
// The returned cancel function must be called only after the response body has been read.
// Cancelling it earlier closes the body mid-read, which truncates a large response that has already
// been answered with 200.
func send(r *http.Request, transport http.RoundTripper, settings Settings, attempts int, body []byte) (*http.Response, context.CancelFunc, error) {
	replayable := isReplayable(r)

	var lastErr error
	for attempt := 1; attempt <= attempts; attempt++ {
		if attempt > 1 {
			// A short pause, so a ledger that is restarting has a moment to finish doing so.
			select {
			case <-time.After(retryPause):
			case <-r.Context().Done():
				return nil, nil, &failure{kind: problem.UpstreamUnusable, status: http.StatusBadGateway, err: r.Context().Err()}
			}
		}

		ctx, cancel := context.WithTimeout(r.Context(), settings.Timeout)
		outbound, err := build(ctx, r, settings.Ledger, body)
		if err != nil {
			cancel()
			return nil, nil, &failure{kind: problem.UpstreamUnusable, status: http.StatusBadGateway, err: err}
		}

		response, err := transport.RoundTrip(outbound)
		if err == nil {
			// An answer, however bad its status, is a decision the ledger has taken. The gateway
			// relays it rather than asking again and hoping for a different one.
			return response, cancel, nil
		}
		cancel()

		lastErr = classify(ctx, err)
		if !replayable || !worthRetrying(lastErr) {
			return nil, nil, lastErr
		}
		logging.FromContext(r.Context()).Warn("retrying the ledger",
			"attempt", attempt, "of", attempts, "error", err.Error())
	}
	return nil, nil, lastErr
}

// isReplayable reports whether sending the request twice is safe.
func isReplayable(r *http.Request) bool {
	if r.Header.Get("Idempotency-Key") != "" {
		return true
	}
	return r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions
}

// worthRetrying reports whether the failure is one where the ledger cannot have acted.
func worthRetrying(err error) bool {
	var known *failure
	if errors.As(err, &known) {
		return known.kind != problem.UpstreamTimeout
	}
	return false
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
