// Package proxy puts a controllable hop between the gateway and the ledger.
//
// It exists for one condition and it is in path for all of them. SCN-SLOW-DEPENDENCY asks what a
// slow downstream dependency looks like from the outside, and the interesting half of that answer
// is which graph does **not** move: the delay has to land where the ledger's own timer cannot see
// it, so that `ledger_posting_latency_seconds` stays flat while every customer waits. A delay added
// inside the ledger would move both and would demonstrate nothing.
//
// It is in path for every run, including the baseline, and that is deliberate. A measurement taken
// through one hop and diffed against a baseline taken through none differs by more than the
// condition, and comparing them anyway is how a team concludes a regression exists. One localhost
// forwarding hop costs tens of microseconds against objectives stated at 500 ms and 1 s; the
// baseline's conditions record that it was there.
//
// The delay is live rather than fixed at construction. A condition is applied part way into a run
// and reverted part way out of it, against a driver that never stops sending - a proxy that had to
// be restarted to change its delay would make the injection a restart, and a restart is a different
// condition.
package proxy

import (
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sync/atomic"
	"time"
)

// Proxy forwards to one upstream, optionally after waiting.
type Proxy struct {
	listener net.Listener
	server   *http.Server
	upstream *url.URL

	// Nanoseconds, so that a run reading it while the injector writes it is defined behaviour
	// rather than a race the detector finds on somebody else's afternoon.
	delay atomic.Int64
}

// Start listens on address and forwards everything to upstream. An address ending in :0 takes any
// free port, which is what the tests use and what a second estate on one machine needs.
func Start(address, upstream string) (*Proxy, error) {
	if upstream == "" {
		return nil, errors.New("proxy: no upstream to forward to")
	}
	target, err := url.Parse(upstream)
	if err != nil {
		return nil, fmt.Errorf("proxy: upstream %q: %w", upstream, err)
	}
	if target.Scheme == "" || target.Host == "" {
		return nil, fmt.Errorf("proxy: upstream %q is not an absolute http address", upstream)
	}

	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, fmt.Errorf("proxy: listening on %s: %w", address, err)
	}

	forwarder := &Proxy{listener: listener, upstream: target}
	reverse := &httputil.ReverseProxy{
		Rewrite: func(request *httputil.ProxyRequest) {
			request.SetURL(target)
			// The inbound Host is kept rather than replaced. The ledger does not route on it, and a
			// rewritten one would make a captured request look like it came from somewhere else.
			request.Out.Host = request.In.Host
		},
		// A dropped connection would reach the driver as an unknown outcome and read as a broken
		// fixture. A 502 is a failure the caller can classify, which is what the estate would
		// produce anyway if the ledger were behind anything real.
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, _ error) {
			w.WriteHeader(http.StatusBadGateway)
		},
	}
	forwarder.server = &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if wait := forwarder.Delay(); wait > 0 {
				select {
				case <-time.After(wait):
				case <-r.Context().Done():
					// The customer gave up while we were holding their request. Answering now would
					// write to a connection nobody is reading.
					return
				}
			}
			reverse.ServeHTTP(w, r)
		}),
		// Long enough that a held request is not cut off by the fixture itself. A condition that
		// timed out here would be measuring the proxy rather than the estate.
		ReadHeaderTimeout: 30 * time.Second,
	}

	go func() { _ = forwarder.server.Serve(listener) }()
	return forwarder, nil
}

// Addr is the host and port the proxy is listening on, which is what the gateway is pointed at.
func (p *Proxy) Addr() string { return p.listener.Addr().String() }

// Upstream is what it forwards to, for the manifest to record.
func (p *Proxy) Upstream() string { return p.upstream.String() }

// SetDelay changes how long every request is held before it reaches the upstream. Zero is a
// transparent forwarder.
func (p *Proxy) SetDelay(delay time.Duration) { p.delay.Store(int64(delay)) }

// Delay is what is currently being added.
func (p *Proxy) Delay() time.Duration { return time.Duration(p.delay.Load()) }

// Close stops listening. Requests already in flight are abandoned, which is what the end of a run
// means.
func (p *Proxy) Close() error { return p.server.Close() }
