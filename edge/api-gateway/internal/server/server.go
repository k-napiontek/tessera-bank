// Package server binds the listening socket and runs the HTTP server until the process is asked to
// stop, then drains.
//
// Binding is separated from serving on purpose. Listen fails loudly while the process is still
// starting, which is when an orchestrator can act on it; a server that discovers its port is taken
// after it has reported itself healthy is a much worse failure.
package server

import (
	"context"
	"errors"
	"net"
	"net/http"
	"time"
)

// Settings are the parts of the configuration this package needs. It takes them rather than the
// whole config so that it stays testable without an environment.
type Settings struct {
	ListenAddress     string
	ReadHeaderTimeout time.Duration
	ShutdownGrace     time.Duration
}

// Server is a bound listener and the HTTP server that will serve it.
type Server struct {
	http     *http.Server
	listener net.Listener
	grace    time.Duration
}

// Listen binds the address. Nothing is served until Serve is called.
func Listen(settings Settings, handler http.Handler) (*Server, error) {
	listener, err := net.Listen("tcp", settings.ListenAddress)
	if err != nil {
		return nil, err
	}
	return &Server{
		http: &http.Server{
			Handler: handler,
			// A client that opens a connection and then dawdles over its headers holds a slot for
			// as long as it likes without this. At the edge, that is the cheapest denial of service
			// there is.
			ReadHeaderTimeout: settings.ReadHeaderTimeout,
		},
		listener: listener,
		grace:    settings.ShutdownGrace,
	}, nil
}

// Address is the address actually bound, which is what the caller wants when the configured port
// was 0.
func (s *Server) Address() string {
	return s.listener.Addr().String()
}

// Serve serves until ctx is cancelled, then drains in-flight requests within the shutdown grace and
// returns. A request already in a handler when SIGTERM arrives is allowed to finish: a transfer cut
// off mid-flight leaves the customer unable to tell whether their money moved, which is the one
// outcome no retry fixes.
func (s *Server) Serve(ctx context.Context) error {
	failed := make(chan error, 1)
	go func() {
		if err := s.http.Serve(s.listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			failed <- err
			return
		}
		failed <- nil
	}()

	select {
	case err := <-failed:
		return err
	case <-ctx.Done():
	}

	// context.Background, not ctx: ctx is the cancelled one, and passing it here would abort the
	// drain at the instant it began.
	drain, cancel := context.WithTimeout(context.Background(), s.grace)
	defer cancel()

	if err := s.http.Shutdown(drain); err != nil {
		return err
	}
	return <-failed
}
