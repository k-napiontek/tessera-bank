package problem_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/problem"
)

func TestTheDocumentMatchesTheContract(t *testing.T) {
	recorder := httptest.NewRecorder()
	var request *http.Request
	correlation.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		request = r
		problem.Write(w, r, http.StatusUnauthorized, problem.Unauthenticated, "The bearer token is not valid.")
	})).ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/transfers", nil))

	if got := recorder.Header().Get("Content-Type"); got != "application/problem+json" {
		t.Errorf("content type = %q, want application/problem+json", got)
	}
	if recorder.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", recorder.Code)
	}

	var body map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("body is not JSON: %v", err)
	}
	if body["type"] != "https://problems.tesserabank.example/unauthenticated" {
		t.Errorf("type = %v", body["type"])
	}
	if body["title"] != "Authentication required" {
		t.Errorf("title = %v", body["title"])
	}
	if body["status"] != float64(http.StatusUnauthorized) {
		t.Errorf("status field = %v", body["status"])
	}
	if body["instance"] != "/transfers" {
		t.Errorf("instance = %v", body["instance"])
	}
	// A support engineer traces the failure with this, and it is the resolved id rather than
	// whatever arrived in the header.
	if body["correlationId"] != correlation.FromContext(request.Context()) {
		t.Errorf("correlationId = %v", body["correlationId"])
	}
}

func TestEveryTypeSharesTheEstateNamespace(t *testing.T) {
	kinds := []problem.Type{
		problem.Unauthenticated, problem.Forbidden, problem.RateLimited, problem.PayloadTooLarge,
		problem.NoRoute, problem.UpstreamTimeout, problem.UpstreamUnusable, problem.UpstreamOversized,
	}
	seen := map[string]bool{}
	for _, kind := range kinds {
		uri := kind.URI()
		if len(uri) <= len("https://problems.tesserabank.example/") {
			t.Errorf("type %q has no slug", uri)
		}
		if seen[uri] {
			t.Errorf("two problem kinds share the URI %q", uri)
		}
		seen[uri] = true
		if kind.Title() == "" {
			t.Errorf("type %q has no title", uri)
		}
	}
}

func TestNothingIsCached(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil)

	problem.Write(recorder, request, http.StatusTooManyRequests, problem.RateLimited, "Slow down.")

	// A cached 429 or 401 is served to the next caller through a shared cache, which is both wrong
	// and, for an authentication failure, a small information leak.
	if got := recorder.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("cache-control = %q, want no-store", got)
	}
}
