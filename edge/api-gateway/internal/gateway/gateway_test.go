package gateway_test

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/config"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/gateway"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/health"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/metrics"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/ratelimit"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

const (
	issuer   = "https://issuer.tesserabank.example"
	audience = "tessera-bank-ledger"
	subject  = "CUST-00000042"
)

var (
	once sync.Once
	key  *rsa.PrivateKey
)

func signingKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	once.Do(func() {
		var err error
		if key, err = rsa.GenerateKey(rand.Reader, 2048); err != nil {
			panic(err)
		}
	})
	return key
}

func token(t *testing.T, scopes string, lifetime time.Duration) string {
	t.Helper()
	claims := jwt.MapClaims{
		"iss":   issuer,
		"aud":   audience,
		"sub":   subject,
		"scope": scopes,
		"iat":   time.Now().Unix(),
		"exp":   time.Now().Add(lifetime).Unix(),
	}
	signed, err := jwt.NewWithClaims(jwt.SigningMethodRS256, claims).SignedString(signingKey(t))
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return signed
}

// estate is the gateway under test with a stand-in ledger behind it.
type estate struct {
	public  http.Handler
	admin   http.Handler
	ledger  *httptest.Server
	seen    chan *http.Request
	logs    *strings.Builder
	metrics *metrics.Metrics
}

func build(t *testing.T, rate float64, burst int) *estate {
	t.Helper()

	e := &estate{seen: make(chan *http.Request, 16), logs: &strings.Builder{}, metrics: metrics.New()}
	e.ledger = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		e.seen <- r.Clone(r.Context())
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"transferRef":"TRF-000001"}`)
	}))
	t.Cleanup(e.ledger.Close)

	ledgerURL, err := url.Parse(e.ledger.URL + "/v1")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}

	encoded, err := x509.MarshalPKIXPublicKey(&signingKey(t).PublicKey)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	keysPath := filepath.Join(t.TempDir(), "keys.pem")
	if err := os.WriteFile(keysPath, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: encoded}), 0o600); err != nil {
		t.Fatalf("write keys: %v", err)
	}

	verifier, err := auth.NewVerifier(auth.Settings{Issuer: issuer, Audience: audience, KeysPath: keysPath})
	if err != nil {
		t.Fatalf("verifier: %v", err)
	}

	deps := gateway.Dependencies{
		Config: config.Config{
			LedgerURL:          ledgerURL,
			DownstreamTimeout:  2 * time.Second,
			DownstreamAttempts: 1,
			MaxRequestBytes:    1024,
			MaxResponseBytes:   4096,
		},
		Verifier: verifier,
		Limiter:  ratelimit.New(ratelimit.Settings{PerSecond: rate, Burst: burst}),
		Metrics:  e.metrics,
		Log:      logging.New("info", e.logs),
	}

	e.public = gateway.Public(deps)
	e.admin = gateway.Admin(deps, health.Cached(health.DialProbe(ledgerURL, time.Second), time.Millisecond))
	return e
}

func (e *estate) call(method, path, bearer string, body io.Reader) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, body)
	if bearer != "" {
		request.Header.Set("Authorization", "Bearer "+bearer)
	}
	recorder := httptest.NewRecorder()
	e.public.ServeHTTP(recorder, request)
	return recorder
}

func (e *estate) scrape(t *testing.T) string {
	t.Helper()
	recorder := httptest.NewRecorder()
	e.admin.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	return recorder.Body.String()
}

func TestAnAuthenticatedTransferReachesTheLedger(t *testing.T) {
	e := build(t, 100, 100)

	response := e.call(http.MethodPost, "/transfers", token(t, routing.ScopeWrite, time.Hour), strings.NewReader(`{"amount":1}`))

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201: %s", response.Code, response.Body)
	}
	select {
	case forwarded := <-e.seen:
		if forwarded.URL.Path != "/v1/transfers" {
			t.Errorf("the ledger saw %q", forwarded.URL.Path)
		}
		if forwarded.Header.Get("Authorization") == "" {
			t.Error("the token did not reach the ledger")
		}
	default:
		t.Fatal("the ledger was never called")
	}
}

func TestAnUnauthenticatedRequestNeverReachesTheLedger(t *testing.T) {
	e := build(t, 100, 100)

	response := e.call(http.MethodPost, "/transfers", "", strings.NewReader("{}"))

	if response.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", response.Code)
	}
	if len(e.seen) != 0 {
		t.Error("an unauthenticated request was forwarded")
	}
}

func TestAnExpiredTokenNeverReachesTheLedger(t *testing.T) {
	e := build(t, 100, 100)

	response := e.call(http.MethodPost, "/transfers", token(t, routing.ScopeWrite, -time.Hour), strings.NewReader("{}"))

	if response.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", response.Code)
	}
	if len(e.seen) != 0 {
		t.Error("an expired token was forwarded")
	}
}

func TestAReadOnlyTokenCannotMoveMoney(t *testing.T) {
	e := build(t, 100, 100)

	response := e.call(http.MethodPost, "/transfers", token(t, routing.ScopeRead, time.Hour), strings.NewReader("{}"))

	if response.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", response.Code)
	}
	if len(e.seen) != 0 {
		t.Error("a read-only token moved money")
	}
}

func TestTheLedgersActuatorIsNotReachableThroughTheEdge(t *testing.T) {
	e := build(t, 100, 100)

	for _, path := range []string{"/actuator/health", "/actuator/prometheus", "/v1/transfers"} {
		response := e.call(http.MethodGet, path, token(t, routing.ScopeRead, time.Hour), nil)

		if response.Code != http.StatusNotFound {
			t.Errorf("%s = %d, want 404", path, response.Code)
		}
	}
	if len(e.seen) != 0 {
		t.Error("a request outside the contract was forwarded")
	}
}

func TestTheRateLimitTripsAndSaysWhenToReturn(t *testing.T) {
	e := build(t, 1, 2)
	bearer := token(t, routing.ScopeRead, time.Hour)

	for i := 1; i <= 2; i++ {
		if response := e.call(http.MethodGet, "/accounts/ACC-000001", bearer, nil); response.Code == http.StatusTooManyRequests {
			t.Fatalf("request %d was limited inside the burst", i)
		}
	}

	response := e.call(http.MethodGet, "/accounts/ACC-000001", bearer, nil)
	if response.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429", response.Code)
	}
	if response.Header().Get("Retry-After") == "" {
		t.Error("no Retry-After on the refusal")
	}
}

func TestOneCorrelationIdSpansTheEdgeAndTheLedger(t *testing.T) {
	e := build(t, 100, 100)
	const supplied = "3f8a1c2d-4b5e-4f60-8a71-9c0d1e2f3a4b"

	request := httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader("{}"))
	request.Header.Set("Authorization", "Bearer "+token(t, routing.ScopeWrite, time.Hour))
	request.Header.Set(correlation.Header, supplied)
	recorder := httptest.NewRecorder()
	e.public.ServeHTTP(recorder, request)

	forwarded := <-e.seen
	if got := forwarded.Header.Get(correlation.Header); got != supplied {
		t.Errorf("the ledger saw %q, want %q", got, supplied)
	}
	if got := recorder.Header().Get(correlation.Header); got != supplied {
		t.Errorf("the client saw %q, want %q", got, supplied)
	}
	// The same id in the gateway's log line is what makes the two halves of the trail one trail.
	if !strings.Contains(e.logs.String(), supplied) {
		t.Errorf("the access line carries no correlation id:\n%s", e.logs.String())
	}
}

func TestNoTokenReachesTheLog(t *testing.T) {
	e := build(t, 100, 100)
	bearer := token(t, routing.ScopeWrite, time.Hour)

	e.call(http.MethodPost, "/transfers", bearer, strings.NewReader("{}"))
	e.call(http.MethodPost, "/transfers", "", strings.NewReader("{}"))

	if strings.Contains(e.logs.String(), bearer) {
		t.Errorf("a bearer token was logged:\n%s", e.logs.String())
	}
}

func TestTheAdminSurfaceIsSeparateFromTheCustomerOne(t *testing.T) {
	e := build(t, 100, 100)

	for _, path := range []string{"/metrics", "/healthz", "/readyz"} {
		response := e.call(http.MethodGet, path, token(t, routing.ScopeRead, time.Hour), nil)
		// Even with a valid token: the customer-facing listener serves the contract and nothing
		// else, and the operational surface lives on a port that is not published.
		if response.Code != http.StatusNotFound {
			t.Errorf("%s on the public listener = %d, want 404", path, response.Code)
		}
	}

	recorder := httptest.NewRecorder()
	e.admin.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/readyz", nil))
	if recorder.Code != http.StatusOK {
		t.Errorf("readiness on the admin listener = %d, want 200", recorder.Code)
	}
}

func TestWhatTheEdgeRefusedIsVisibleInTheMetrics(t *testing.T) {
	e := build(t, 1, 1)
	bearer := token(t, routing.ScopeRead, time.Hour)

	e.call(http.MethodPost, "/transfers", bearer, strings.NewReader("{}")) // forbidden
	e.call(http.MethodGet, "/nope", bearer, nil)                           // no route
	e.call(http.MethodGet, "/accounts/ACC-000001", "", nil)                // unauthenticated
	e.call(http.MethodGet, "/accounts/ACC-000001", bearer, nil)            // allowed
	e.call(http.MethodGet, "/accounts/ACC-000001", bearer, nil)            // rate limited

	body := e.scrape(t)
	for _, want := range []string{
		`tessera_gateway_refusals_total{reason="forbidden"} 1`,
		`tessera_gateway_refusals_total{reason="no_route"} 1`,
		`tessera_gateway_refusals_total{reason="unauthenticated"} 1`,
		`tessera_gateway_refusals_total{reason="rate_limited"} 1`,
		`route="accounts.get"`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("missing %s", want)
		}
	}
}

func TestAProblemDocumentIsWhatEveryRefusalLooksLike(t *testing.T) {
	e := build(t, 1, 1)
	bearer := token(t, routing.ScopeRead, time.Hour)

	responses := []*httptest.ResponseRecorder{
		e.call(http.MethodGet, "/accounts/ACC-000001", "", nil),
		e.call(http.MethodPost, "/transfers", bearer, strings.NewReader("{}")),
		e.call(http.MethodGet, "/nope", bearer, nil),
		e.call(http.MethodPost, "/accounts", bearer, strings.NewReader(strings.Repeat("a", 4096))),
	}
	for i, response := range responses {
		if got := response.Header().Get("Content-Type"); got != "application/problem+json" {
			t.Errorf("refusal %d: content type = %q", i, got)
		}
		var body map[string]any
		if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
			t.Fatalf("refusal %d is not JSON: %v", i, err)
		}
		// A client should be able to write one error path for the whole estate, and the
		// correlation id is what it quotes when it opens a ticket.
		if !strings.HasPrefix(body["type"].(string), "https://problems.tesserabank.example/") {
			t.Errorf("refusal %d: type = %v", i, body["type"])
		}
		if !correlation.IsCanonicalUUID(body["correlationId"].(string)) {
			t.Errorf("refusal %d: correlationId = %v", i, body["correlationId"])
		}
	}
}
