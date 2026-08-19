package server_test

import (
	"context"
	"io"
	"net/http"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/server"
)

func settings() server.Settings {
	return server.Settings{
		ListenAddress:     "127.0.0.1:0",
		ReadHeaderTimeout: time.Second,
		ShutdownGrace:     5 * time.Second,
	}
}

func TestServeAnswersOnTheBoundAddress(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	})

	srv, err := server.Listen(settings(), handler)
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	served := make(chan error, 1)
	go func() { served <- srv.Serve(ctx) }()

	response, err := http.Get("http://" + srv.Address() + "/anything")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusTeapot {
		t.Errorf("status = %d, want 418", response.StatusCode)
	}

	cancel()
	if err := <-served; err != nil {
		t.Errorf("serve returned %v, want nil after a cancelled context", err)
	}
}

func TestShutdownLetsAnInFlightRequestFinish(t *testing.T) {
	release := make(chan struct{})
	handler := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		<-release
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "finished")
	})

	srv, err := server.Listen(settings(), handler)
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	served := make(chan error, 1)
	go func() { served <- srv.Serve(ctx) }()

	type result struct {
		body string
		err  error
	}
	answered := make(chan result, 1)
	go func() {
		response, err := http.Get("http://" + srv.Address() + "/slow")
		if err != nil {
			answered <- result{err: err}
			return
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		answered <- result{body: string(body), err: err}
	}()

	// Give the request time to reach the handler, then ask the process to stop while it is there.
	time.Sleep(50 * time.Millisecond)
	cancel()
	close(release)

	// A transfer cut off mid-flight is the one failure the customer cannot resolve themselves: they
	// do not know whether the money moved. Draining is what makes a rolling deployment safe.
	got := <-answered
	if got.err != nil {
		t.Fatalf("in-flight request failed during shutdown: %v", got.err)
	}
	if got.body != "finished" {
		t.Errorf("body = %q, want %q", got.body, "finished")
	}
	if err := <-served; err != nil {
		t.Errorf("serve returned %v, want nil", err)
	}
}

func TestListenReportsAnAddressItCannotBind(t *testing.T) {
	taken, err := server.Listen(settings(), http.NotFoundHandler())
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = taken.Serve(ctx) }()

	clash := settings()
	clash.ListenAddress = taken.Address()

	if _, err := server.Listen(clash, http.NotFoundHandler()); err == nil {
		t.Error("binding an address already in use must fail at Listen, before anything is served")
	}
}
