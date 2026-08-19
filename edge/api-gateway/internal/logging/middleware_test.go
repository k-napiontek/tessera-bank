package logging_test

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
)

const token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJDVVNULTAwMDAwMDAxIn0.c2lnbmF0dXJl"

// exercise drives one request through the correlation and access-log middleware and returns the
// lines that were written.
func exercise(t *testing.T, request *http.Request, handler http.Handler) (*bytes.Buffer, []map[string]any) {
	t.Helper()

	out := &bytes.Buffer{}
	log := logging.New("info", out)
	chain := correlation.Middleware(logging.Middleware(log)(handler))

	chain.ServeHTTP(httptest.NewRecorder(), request)

	var lines []map[string]any
	for _, raw := range strings.Split(strings.TrimSpace(out.String()), "\n") {
		if raw == "" {
			continue
		}
		var line map[string]any
		if err := json.Unmarshal([]byte(raw), &line); err != nil {
			t.Fatalf("log line is not JSON: %q", raw)
		}
		lines = append(lines, line)
	}
	return out, lines
}

func ok() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"transferRef":"TRF-1"}`)
	})
}

func TestOneRequestLogsOneLine(t *testing.T) {
	request := httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader("{}"))

	_, lines := exercise(t, request, ok())

	if len(lines) != 1 {
		t.Fatalf("got %d log lines, want 1", len(lines))
	}
	line := lines[0]
	if line["method"] != http.MethodPost {
		t.Errorf("method = %v", line["method"])
	}
	if line["path"] != "/transfers" {
		t.Errorf("path = %v", line["path"])
	}
	if line["status"] != float64(http.StatusCreated) {
		t.Errorf("status = %v, want 201", line["status"])
	}
	if _, present := line["duration_ms"]; !present {
		t.Error("no duration on the access line")
	}
	if !correlation.IsCanonicalUUID(asString(line[correlation.LogKey])) {
		t.Errorf("correlation id = %v, want a canonical UUID", line[correlation.LogKey])
	}
}

func TestTheAccessLineCarriesTheSuppliedCorrelationId(t *testing.T) {
	const supplied = "3f8a1c2d-4b5e-4f60-8a71-9c0d1e2f3a4b"
	request := httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil)
	request.Header.Set(correlation.Header, supplied)

	_, lines := exercise(t, request, ok())

	if got := asString(lines[0][correlation.LogKey]); got != supplied {
		t.Errorf("correlation id = %q, want %q", got, supplied)
	}
}

func TestNoCredentialReachesALogLine(t *testing.T) {
	request := httptest.NewRequest(http.MethodPost, "/transfers?debug=1", strings.NewReader(
		`{"amount":{"minorUnits":1000,"currency":"PLN"},"debtorRef":"ACC-000001"}`))
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Idempotency-Key", "6f1c9b5e-2d3a-4f8b-9c0d-1e2f3a4b5c6d")
	request.Header.Set("Cookie", "session=abc123")

	// The handler logs through the request logger, which is how an unwary caller would leak one.
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		logging.FromContext(r.Context()).Info("forwarding to the ledger", "route", "POST /transfers")
		w.WriteHeader(http.StatusCreated)
	})

	out, _ := exercise(t, request, handler)

	// A bearer token in a log line is a credential in a log store, replayable for as long as it
	// lives, and log stores are read by more people than the ledger ever is.
	for _, forbidden := range []string{token, "Bearer", "Authorization", "session=abc123", "debtorRef"} {
		if strings.Contains(out.String(), forbidden) {
			t.Errorf("log output contains %q:\n%s", forbidden, out.String())
		}
	}
}

func TestTheClientAddressIsNotLogged(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil)
	request.RemoteAddr = "203.0.113.7:51234"
	request.Header.Set("X-Forwarded-For", "198.51.100.22")

	out, _ := exercise(t, request, ok())

	// An IP address identifies a person under GDPR, and this repository holds no personal data at
	// all. An account reference and a correlation id are what the estate logs.
	for _, address := range []string{"203.0.113.7", "198.51.100.22"} {
		if strings.Contains(out.String(), address) {
			t.Errorf("log output contains the client address %q:\n%s", address, out.String())
		}
	}
}

func TestTheQueryStringIsNotLogged(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001/statement?cursor=b3BhcXVl&access_token=leaked", nil)

	out, _ := exercise(t, request, ok())

	// A query string is caller-controlled, and callers put credentials there - "access_token=" in a
	// URL is common enough that the OAuth specification warns about it.
	if strings.Contains(out.String(), "access_token") || strings.Contains(out.String(), "cursor=") {
		t.Errorf("log output contains the query string:\n%s", out.String())
	}
	if !strings.Contains(out.String(), "/accounts/ACC-000001/statement") {
		t.Errorf("log output lost the path itself:\n%s", out.String())
	}
}

func TestFromContextFallsBackToADiscardingLogger(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/", nil)

	// Never nil: a handler that logs outside the chain should be silent, not a panic in production.
	logging.FromContext(request.Context()).Info("no middleware ran")
}

func asString(value any) string {
	text, _ := value.(string)
	return text
}
