// Package proxy forwards a request to services/ledger-core and relays the answer.
//
// It is deliberately not httputil.ReverseProxy. That type streams, which is the right default for a
// general proxy and the wrong one here: the gateway has to decide whether a response is within its
// size limit before a byte of it is on the wire, and it has to be able to send the request a second
// time when the first attempt never got an answer. Both need the bodies in hand.
//
// The bodies are small by construction - a transfer instruction is a few hundred bytes and a
// statement page is bounded by the cursor - and the limits in the configuration are what keep them
// that way.
//
// What the gateway forwards is an allow list, in both directions. A deny list forwards whatever
// header somebody invents next, and the ledger trusts what reaches it.
package proxy

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/problem"
)

// Settings are what the proxy needs from the configuration.
type Settings struct {
	// Ledger is the base URL, including any path prefix such as /v1.
	Ledger *url.URL
	// Timeout bounds one attempt, from connection to the last byte of the response.
	Timeout time.Duration
	// Attempts is the total number of tries, so 1 means no retry.
	Attempts int
	// MaxRequestBytes is the largest request body that will be forwarded.
	MaxRequestBytes int64
	// MaxResponseBytes is the largest response body that will be relayed.
	MaxResponseBytes int64
	// Transport is the round tripper to use. Nil means a transport built here.
	Transport http.RoundTripper
}

// requestHeaders are the only headers that travel to the ledger.
//
// Authorization goes through unchanged: the gateway validates the token and forwards it, so the
// ledger can validate it again rather than trusting a claim the network makes about who is calling.
var requestHeaders = []string{
	"Authorization",
	"Idempotency-Key",
	"Content-Type",
	"Accept",
	"Accept-Language",
	correlation.Header,
}

// responseHeaders are the only headers relayed back. Anything else the ledger sets - its server
// banner, its own cookies, a header added by a framework upgrade - stops at the edge.
var responseHeaders = []string{
	"Content-Type",
	"Location",
	"ETag",
	"Retry-After",
	"Cache-Control",
	correlation.Header,
}

// New builds the handler that forwards to the ledger.
func New(settings Settings) http.Handler {
	transport := settings.Transport
	if transport == nil {
		transport = defaultTransport(settings.Timeout)
	}
	attempts := settings.Attempts
	if attempts < 1 {
		attempts = 1
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, ok := readBody(w, r, settings.MaxRequestBytes)
		if !ok {
			return
		}

		response, err := send(r, transport, settings, attempts, body)
		if err != nil {
			refuse(w, r, err)
			return
		}
		defer response.Body.Close()

		relay(w, r, response, settings.MaxResponseBytes)
	})
}

// readBody reads the request body up to the limit. It is held in memory because a retry has to be
// able to send it again, and because the alternative - streaming - means the size limit can only be
// enforced after part of the body has already reached the ledger.
func readBody(w http.ResponseWriter, r *http.Request, limit int64) ([]byte, bool) {
	if r.Body == nil {
		return nil, true
	}
	// limit+1 so that a body exactly at the limit is accepted and one byte more is not.
	body, err := io.ReadAll(io.LimitReader(r.Body, limit+1))
	if err != nil {
		problem.Write(w, r, http.StatusBadRequest, problem.NoRoute, "The request body could not be read.")
		return nil, false
	}
	if int64(len(body)) > limit {
		problem.Write(w, r, http.StatusRequestEntityTooLarge, problem.PayloadTooLarge,
			"The request body is larger than this API accepts.")
		return nil, false
	}
	return body, true
}

// build makes the outbound request. Each attempt gets a fresh one: a request that has been sent
// cannot be sent again.
func build(ctx context.Context, r *http.Request, ledger *url.URL, body []byte) (*http.Request, error) {
	target := *ledger
	target.Path = strings.TrimSuffix(ledger.Path, "/") + r.URL.Path
	target.RawQuery = r.URL.RawQuery

	outbound, err := http.NewRequestWithContext(ctx, r.Method, target.String(), bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	for _, name := range requestHeaders {
		if value := r.Header.Get(name); value != "" {
			outbound.Header.Set(name, value)
		}
	}
	outbound.ContentLength = int64(len(body))
	return outbound, nil
}

// relay copies the ledger's answer back, refusing one that is larger than the limit.
//
// The whole body is read before anything is written, because a response that turns out to be too
// large after a 200 has gone out cannot be turned into an error any more.
func relay(w http.ResponseWriter, r *http.Request, response *http.Response, limit int64) {
	body, err := io.ReadAll(io.LimitReader(response.Body, limit+1))
	if err != nil {
		logging.FromContext(r.Context()).Error("the ledger's response could not be read", "error", err.Error())
		problem.Write(w, r, http.StatusBadGateway, problem.UpstreamUnusable,
			"The ledger could not be reached. The request may not have been applied.")
		return
	}
	if int64(len(body)) > limit {
		logging.FromContext(r.Context()).Error("the ledger's response exceeds the relay limit", "limit", limit)
		problem.Write(w, r, http.StatusBadGateway, problem.UpstreamOversized,
			"The ledger's response is too large to relay.")
		return
	}

	for _, name := range responseHeaders {
		if value := response.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	_, _ = w.Write(body)
}
