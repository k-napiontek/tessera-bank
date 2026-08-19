// Package health serves the two probes an orchestrator asks for, and keeps them answering
// different questions.
//
// Liveness answers "is this process wedged". It never consults the ledger: a gateway restarted
// because its downstream is deploying is a crash loop caused by a healthy dependency outage.
//
// Readiness answers "should traffic be sent here". The gateway has exactly one downstream, so a
// gateway that cannot reach the ledger can serve nothing useful and says so - which is also why
// the check is a TCP dial rather than a call to a health endpoint. A listening socket is not a
// healthy ledger, and the gateway deliberately does not claim to know the difference: interpreting
// the ledger's own health document here would put a second opinion about the ledger's state at the
// edge, and two opinions disagree.
package health

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// Probe reports whether the ledger can be reached. A nil error means it can.
type Probe interface {
	Check(ctx context.Context) error
}

// ProbeFunc adapts a function to Probe.
type ProbeFunc func(ctx context.Context) error

func (f ProbeFunc) Check(ctx context.Context) error { return f(ctx) }

// Handler serves /healthz and /readyz.
func Handler(readiness Probe) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		write(w, http.StatusOK, "UP")
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if err := readiness.Check(r.Context()); err != nil {
			// The error names an internal host and port. It is logged by the caller of this
			// package, never served: these two endpoints are the only ones the gateway answers
			// without a token.
			write(w, http.StatusServiceUnavailable, "DOWN")
			return
		}
		write(w, http.StatusOK, "UP")
	})
	return mux
}

func write(w http.ResponseWriter, status int, state string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	// The shape matches the ledger's actuator document, so one operator reads both tiers the same
	// way.
	_ = json.NewEncoder(w).Encode(map[string]string{"status": state})
}

// DialProbe checks that something is listening at the ledger's host and port.
func DialProbe(ledger *url.URL, timeout time.Duration) Probe {
	address := DialAddress(ledger)
	return ProbeFunc(func(ctx context.Context) error {
		ctx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()

		conn, err := (&net.Dialer{}).DialContext(ctx, "tcp", address)
		if err != nil {
			return err
		}
		return conn.Close()
	})
}

// DialAddress resolves the host and port to dial, filling in the port the scheme implies when the
// URL omits it.
func DialAddress(ledger *url.URL) string {
	if ledger.Port() != "" {
		return ledger.Host
	}
	if ledger.Scheme == "https" {
		return net.JoinHostPort(ledger.Hostname(), "443")
	}
	return net.JoinHostPort(ledger.Hostname(), "80")
}

// Cached collapses the probes arriving within window into one call downstream. A fleet of n
// gateways polled every few seconds would otherwise turn readiness into a load source of its own.
func Cached(probe Probe, window time.Duration) Probe {
	var (
		mu       sync.Mutex
		checked  time.Time
		lastErr  error
		hasValue bool
	)
	return ProbeFunc(func(ctx context.Context) error {
		mu.Lock()
		defer mu.Unlock()

		if hasValue && time.Since(checked) < window {
			return lastErr
		}
		lastErr = probe.Check(ctx)
		checked = time.Now()
		hasValue = true
		return lastErr
	})
}
