package proxy_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/proxy"
)

// ledger is a stand-in for services/ledger-api that records what reached it.
type ledger struct {
	server  *httptest.Server
	seen    *http.Request
	body    string
	status  int
	reply   string
	headers map[string]string
}

func newLedger(t *testing.T) *ledger {
	t.Helper()

	l := &ledger{status: http.StatusOK, reply: `{"ok":true}`, headers: map[string]string{}}
	l.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		l.body = string(body)
		l.seen = r.Clone(r.Context())

		for name, value := range l.headers {
			w.Header().Set(name, value)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(l.status)
		_, _ = io.WriteString(w, l.reply)
	}))
	t.Cleanup(l.server.Close)
	return l
}

func (l *ledger) url(t *testing.T, path string) *url.URL {
	t.Helper()
	parsed, err := url.Parse(l.server.URL + path)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	return parsed
}

func settings(target *url.URL) proxy.Settings {
	return proxy.Settings{
		Ledger:           target,
		Timeout:          2 * time.Second,
		Attempts:         1,
		MaxRequestBytes:  1024,
		MaxResponseBytes: 4096,
	}
}

// through sends one request at the gateway's proxy, with the correlation middleware in front of it
// because everything downstream of it expects an id.
func through(t *testing.T, s proxy.Settings, request *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	correlation.Middleware(proxy.New(s)).ServeHTTP(recorder, request)
	return recorder
}

func TestTheLedgersAnswerIsRelayed(t *testing.T) {
	l := newLedger(t)
	l.status = http.StatusCreated
	l.reply = `{"transferRef":"TRF-000001"}`
	l.headers["Location"] = "/transfers/TRF-000001"

	response := through(t, settings(l.url(t, "")), httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader(`{"x":1}`)))

	if response.Code != http.StatusCreated {
		t.Errorf("status = %d, want 201", response.Code)
	}
	if response.Body.String() != l.reply {
		t.Errorf("body = %q, want %q", response.Body.String(), l.reply)
	}
	if got := response.Header().Get("Location"); got != "/transfers/TRF-000001" {
		t.Errorf("Location = %q", got)
	}
	if l.body != `{"x":1}` {
		t.Errorf("the ledger received %q", l.body)
	}
	if l.seen.Method != http.MethodPost {
		t.Errorf("the ledger saw %s", l.seen.Method)
	}
}

func TestTheBasePathOfTheLedgerIsPreserved(t *testing.T) {
	l := newLedger(t)

	through(t, settings(l.url(t, "/v1")), httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001/statement?cursor=b3BhcXVl&limit=50", nil))

	// The ledger is served under /v1 and the gateway is not. Dropping the prefix produces a 404
	// from the ledger that looks exactly like a missing account.
	if l.seen.URL.Path != "/v1/accounts/ACC-000001/statement" {
		t.Errorf("path = %q, want /v1/accounts/ACC-000001/statement", l.seen.URL.Path)
	}
	if l.seen.URL.RawQuery != "cursor=b3BhcXVl&limit=50" {
		t.Errorf("query = %q", l.seen.URL.RawQuery)
	}
}

func TestOnlyTheHeadersTheLedgerNeedsAreForwarded(t *testing.T) {
	l := newLedger(t)

	request := httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader("{}"))
	request.Header.Set("Authorization", "Bearer token-value")
	request.Header.Set("Idempotency-Key", "6f1c9b5e-2d3a-4f8b-9c0d-1e2f3a4b5c6d")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cookie", "session=abc123")
	request.Header.Set("X-Forwarded-For", "198.51.100.22")
	request.Header.Set("Connection", "keep-alive")
	request.Header.Set("X-Admin-Override", "true")

	through(t, settings(l.url(t, "")), request)

	// An allow list, not a deny list. A deny list forwards every header somebody thinks of next,
	// and the ledger trusts what reaches it.
	for _, forwarded := range []string{"Authorization", "Idempotency-Key", "Content-Type", "Accept"} {
		if l.seen.Header.Get(forwarded) == "" {
			t.Errorf("%s did not reach the ledger", forwarded)
		}
	}
	for _, blocked := range []string{"Cookie", "X-Forwarded-For", "X-Admin-Override"} {
		if l.seen.Header.Get(blocked) != "" {
			t.Errorf("%s was forwarded to the ledger", blocked)
		}
	}
}

func TestTheResolvedCorrelationIdIsForwarded(t *testing.T) {
	l := newLedger(t)

	request := httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil)
	request.Header.Set(correlation.Header, "not-a-uuid")

	response := through(t, settings(l.url(t, "")), request)

	forwarded := l.seen.Header.Get(correlation.Header)
	if !correlation.IsCanonicalUUID(forwarded) {
		t.Fatalf("the ledger received %q, want a canonical UUID", forwarded)
	}
	// One id, one request, every tier. The ledger would otherwise mint a second one and the two
	// halves of the trail would not join up.
	if got := response.Header().Get(correlation.Header); got != forwarded {
		t.Errorf("the client was told %q and the ledger %q", got, forwarded)
	}
}

func TestAHopByHopHeaderFromTheLedgerIsNotRelayed(t *testing.T) {
	l := newLedger(t)
	l.headers["Connection"] = "close"
	l.headers["Keep-Alive"] = "timeout=5"
	l.headers["ETag"] = `"v1"`

	response := through(t, settings(l.url(t, "")), httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))

	// RFC 9110: a hop-by-hop header describes one connection and must not be passed on. Relaying
	// the ledger's Connection header would let it close the customer's.
	for _, hop := range []string{"Connection", "Keep-Alive"} {
		if got := response.Header().Get(hop); got != "" {
			t.Errorf("%s was relayed to the client: %q", hop, got)
		}
	}
	if got := response.Header().Get("ETag"); got != `"v1"` {
		t.Errorf("ETag = %q, want it relayed", got)
	}
}

func TestAnOversizedRequestNeverReachesTheLedger(t *testing.T) {
	l := newLedger(t)
	s := settings(l.url(t, ""))
	s.MaxRequestBytes = 64

	response := through(t, s, httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader(strings.Repeat("a", 1024))))

	if response.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("status = %d, want 413", response.Code)
	}
	// The point of a limit at the edge is that the cost is paid here and not by the ledger.
	if l.seen != nil {
		t.Error("the oversized request was forwarded")
	}
	assertProblem(t, response, "https://problems.tesserabank.example/payload-too-large")
}

func TestARequestExactlyAtTheLimitIsForwarded(t *testing.T) {
	l := newLedger(t)
	s := settings(l.url(t, ""))
	s.MaxRequestBytes = 64

	body := strings.Repeat("a", 64)
	response := through(t, s, httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader(body)))

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 at exactly the limit", response.Code)
	}
	if l.body != body {
		t.Errorf("the ledger received %d bytes, want %d", len(l.body), len(body))
	}
}

func TestAnOversizedAnswerIsRefusedRatherThanRelayed(t *testing.T) {
	l := newLedger(t)
	l.reply = strings.Repeat("b", 5000)
	s := settings(l.url(t, ""))
	s.MaxResponseBytes = 1024

	response := through(t, s, httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001/statement", nil))

	// Deciding before anything is written is the whole difficulty: a half-relayed body with a 200
	// already on the wire cannot be turned into an error.
	if response.Code != http.StatusBadGateway {
		t.Errorf("status = %d, want 502", response.Code)
	}
	assertProblem(t, response, "https://problems.tesserabank.example/upstream-oversized")
}

func TestAProblemFromTheLedgerIsRelayedUntouched(t *testing.T) {
	l := newLedger(t)
	l.status = http.StatusUnprocessableEntity
	l.reply = `{"type":"https://problems.tesserabank.example/insufficient-funds","title":"Insufficient funds","status":422}`
	l.headers["Content-Type"] = "application/problem+json"

	response := through(t, settings(l.url(t, "")), httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader("{}")))

	// The ledger owns every business failure. A gateway that reinterprets one has started to
	// understand what a transfer is.
	if response.Code != http.StatusUnprocessableEntity {
		t.Errorf("status = %d, want 422", response.Code)
	}
	if response.Body.String() != l.reply {
		t.Errorf("the problem document was altered:\n%s", response.Body.String())
	}
}

func assertProblem(t *testing.T, response *httptest.ResponseRecorder, wantType string) {
	t.Helper()
	if got := response.Header().Get("Content-Type"); got != "application/problem+json" {
		t.Errorf("content type = %q", got)
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("body is not JSON: %v", err)
	}
	if body["type"] != wantType {
		t.Errorf("type = %v, want %v", body["type"], wantType)
	}
}
