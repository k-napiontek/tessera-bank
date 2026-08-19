package proxy_test

import (
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestASlowLedgerProducesATimeoutRatherThanAHungConnection(t *testing.T) {
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(5 * time.Second):
			w.WriteHeader(http.StatusOK)
		case <-r.Context().Done():
		}
	}))
	defer slow.Close()

	target, err := url.Parse(slow.URL)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	s := settings(target)
	s.Timeout = 100 * time.Millisecond

	started := time.Now()
	response := through(t, s, httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))
	elapsed := time.Since(started)

	// 504, not 502: the ledger may have applied the request and simply not answered in time, and
	// the gateway must not claim otherwise.
	if response.Code != http.StatusGatewayTimeout {
		t.Errorf("status = %d, want 504", response.Code)
	}
	if elapsed > time.Second {
		t.Errorf("the request took %v; the timeout did not bound it", elapsed)
	}
	assertProblem(t, response, "https://problems.tesserabank.example/upstream-timeout")
}

func TestALedgerThatIsNotListeningProducesABadGateway(t *testing.T) {
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

	response := through(t, settings(target), httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))

	if response.Code != http.StatusBadGateway {
		t.Errorf("status = %d, want 502", response.Code)
	}
	assertProblem(t, response, "https://problems.tesserabank.example/upstream-unusable")
}

func TestARefusalSaysNothingAboutTheInternalAddress(t *testing.T) {
	target, err := url.Parse("http://ledger-core.internal.invalid:8080/v1")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	s := settings(target)
	s.Timeout = 500 * time.Millisecond

	response := through(t, s, httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))

	// An error string from the transport names the host, the port and often the resolver. None of
	// that is the customer's business, and all of it is an attacker's.
	for _, leak := range []string{"ledger-core", "8080", "dial", "lookup", "connection refused"} {
		if strings.Contains(response.Body.String(), leak) {
			t.Errorf("the response leaks %q:\n%s", leak, response.Body.String())
		}
	}
}
