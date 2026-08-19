package health_test

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/health"
)

type stubProbe struct {
	calls atomic.Int64
	err   error
}

func (s *stubProbe) Check(context.Context) error {
	s.calls.Add(1)
	return s.err
}

func get(t *testing.T, handler http.Handler, path string) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))
	return recorder
}

func TestLivenessStaysUpWhileTheDownstreamIsDown(t *testing.T) {
	probe := &stubProbe{err: errors.New("connection refused")}
	handler := health.Handler(probe)

	response := get(t, handler, "/healthz")

	// Liveness answers "is this process wedged", not "is the estate well". Wiring the downstream
	// into it makes an orchestrator restart a perfectly healthy gateway because the ledger is
	// deploying, which converts a partial outage into a crash loop.
	if response.Code != http.StatusOK {
		t.Errorf("liveness = %d, want 200 while the downstream is unreachable", response.Code)
	}
	if probe.calls.Load() != 0 {
		t.Errorf("liveness called the downstream probe %d times, want 0", probe.calls.Load())
	}
}

func TestReadinessReportsUpWhenTheLedgerAnswers(t *testing.T) {
	handler := health.Handler(&stubProbe{})

	response := get(t, handler, "/readyz")

	if response.Code != http.StatusOK {
		t.Fatalf("readiness = %d, want 200", response.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("readiness body is not JSON: %v", err)
	}
	if body["status"] != "UP" {
		t.Errorf("status = %v, want UP", body["status"])
	}
}

func TestReadinessReportsDownWhenTheLedgerIsUnreachable(t *testing.T) {
	handler := health.Handler(&stubProbe{err: errors.New("connection refused")})

	response := get(t, handler, "/readyz")

	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("readiness = %d, want 503", response.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("readiness body is not JSON: %v", err)
	}
	if body["status"] != "DOWN" {
		t.Errorf("status = %v, want DOWN", body["status"])
	}
	// The reason a probe failed is an internal address and an error string. Neither belongs in a
	// response served at the edge.
	if _, leaked := body["error"]; leaked {
		t.Error("readiness body leaks the probe error to an unauthenticated caller")
	}
}

func TestCachedProbeDoesNotAmplifyOnePollIntoMany(t *testing.T) {
	probe := &stubProbe{}
	cached := health.Cached(probe, time.Minute)
	handler := health.Handler(cached)

	get(t, handler, "/readyz")
	get(t, handler, "/readyz")
	get(t, handler, "/readyz")

	// Kubernetes polls readiness on every instance on a fixed period. Without a cache, n gateways
	// become n probes per period against the ledger, and the probe traffic grows with the fleet.
	if probe.calls.Load() != 1 {
		t.Errorf("probe called %d times, want 1 within the cache window", probe.calls.Load())
	}
}

func TestCachedProbeRecheckesAfterTheWindow(t *testing.T) {
	probe := &stubProbe{}
	cached := health.Cached(probe, time.Nanosecond)
	handler := health.Handler(cached)

	get(t, handler, "/readyz")
	time.Sleep(time.Millisecond)
	get(t, handler, "/readyz")

	if probe.calls.Load() != 2 {
		t.Errorf("probe called %d times, want 2 once the window elapsed", probe.calls.Load())
	}
}

func TestDialProbeReachesAListeningLedger(t *testing.T) {
	ledger := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer ledger.Close()

	target, err := url.Parse(ledger.URL)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	probe := health.DialProbe(target, time.Second)

	if err := probe.Check(context.Background()); err != nil {
		t.Errorf("probe against a listening ledger failed: %v", err)
	}
}

func TestDialProbeFailsWhenNothingIsListening(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	address := listener.Addr().String()
	if err := listener.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	target, err := url.Parse("http://" + address)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	probe := health.DialProbe(target, time.Second)

	if err := probe.Check(context.Background()); err == nil {
		t.Error("probe against a closed port must fail")
	}
}

func TestDialProbeUsesTheSchemeDefaultPortWhenNoneIsGiven(t *testing.T) {
	for raw, want := range map[string]string{
		"http://ledger-core.internal/v1":   "ledger-core.internal:80",
		"https://ledger-core.internal/v1":  "ledger-core.internal:443",
		"http://ledger-core.internal:8080": "ledger-core.internal:8080",
	} {
		target, err := url.Parse(raw)
		if err != nil {
			t.Fatalf("parse %q: %v", raw, err)
		}
		if got := health.DialAddress(target); got != want {
			t.Errorf("dial address for %q = %q, want %q", raw, got, want)
		}
	}
}
