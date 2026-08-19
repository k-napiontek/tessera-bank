package authz_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/authz"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

// call places a principal and a route on the context by hand, which is what the authentication and
// routing middleware do before this one runs.
func call(t *testing.T, scopes []string, route *routing.Route) (*httptest.ResponseRecorder, bool) {
	t.Helper()

	reached := false
	inner := authz.Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		reached = true
	}))

	handler := correlation.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		if scopes != nil {
			ctx = auth.WithPrincipal(ctx, auth.Principal{Subject: "CUST-00000042", Scopes: scopes})
		}
		if route != nil {
			ctx = routing.WithRoute(ctx, *route)
		}
		inner.ServeHTTP(w, r.WithContext(ctx))
	}))

	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/transfers", nil))
	return recorder, reached
}

func transferRoute() *routing.Route {
	for _, route := range routing.Routes() {
		if route.Class == "transfers.create" {
			return &route
		}
	}
	panic("the transfer route has gone from the table")
}

func TestTheRequiredScopeAdmits(t *testing.T) {
	_, reached := call(t, []string{routing.ScopeRead, routing.ScopeWrite}, transferRoute())

	if !reached {
		t.Error("a token carrying the required scope was refused")
	}
}

func TestAnotherScopeDoesNotAdmit(t *testing.T) {
	response, reached := call(t, []string{routing.ScopeRead}, transferRoute())

	if reached {
		t.Fatal("a read-only token reached a transfer")
	}
	if response.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", response.Code)
	}

	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("body is not JSON: %v", err)
	}
	if body["type"] != "https://problems.tesserabank.example/forbidden" {
		t.Errorf("type = %v", body["type"])
	}
	// 403, not 401: the caller proved who they are, and repeating the authentication will not
	// change the answer. A gateway that returns 401 here sends a client into a refresh loop.
	if got := response.Header().Get("WWW-Authenticate"); got != "" {
		t.Errorf("a 403 carries an authentication challenge: %q", got)
	}
}

func TestNoScopesAtAllDoNotAdmit(t *testing.T) {
	if _, reached := call(t, []string{}, transferRoute()); reached {
		t.Error("a token carrying no scopes reached a transfer")
	}
}

func TestAnUnauthenticatedRequestIsRefusedRatherThanWavedThrough(t *testing.T) {
	// This cannot happen while the middleware is chained in the right order, which is exactly why
	// it is asserted: the failure mode of a misordered chain is silent and total.
	response, reached := call(t, nil, transferRoute())

	if reached {
		t.Fatal("a request with no principal reached the ledger")
	}
	if response.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", response.Code)
	}
}

func TestARequestWithNoRouteIsRefusedRatherThanWavedThrough(t *testing.T) {
	response, reached := call(t, []string{routing.ScopeWrite}, nil)

	if reached {
		t.Fatal("a request that matched no route reached the ledger")
	}
	// Defaulting to "allow" when the route is unknown is how an unrouted path becomes an open one.
	if response.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", response.Code)
	}
}

func TestEveryRouteInTheTableIsEnforceable(t *testing.T) {
	for _, route := range routing.Routes() {
		route := route
		if _, reached := call(t, []string{route.Scope}, &route); !reached {
			t.Errorf("%s %s refuses the scope its own table entry names", route.Method, route.Path)
		}
		if _, reached := call(t, []string{"something:else"}, &route); reached {
			t.Errorf("%s %s admits an unrelated scope", route.Method, route.Path)
		}
	}
}

func TestPrincipalSurvivesTheMiddleware(t *testing.T) {
	var seen auth.Principal
	inner := authz.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		seen, _ = auth.PrincipalFrom(r.Context())
	}))

	route := transferRoute()
	ctx := auth.WithPrincipal(context.Background(), auth.Principal{Subject: "CUST-1", Scopes: []string{route.Scope}})
	ctx = routing.WithRoute(ctx, *route)

	request := httptest.NewRequest(http.MethodPost, "/transfers", nil).WithContext(ctx)
	inner.ServeHTTP(httptest.NewRecorder(), request)

	// The rate limiter keys on the subject and runs after this, so losing the principal here would
	// silently turn a per-customer limit into a global one.
	if seen.Subject != "CUST-1" {
		t.Errorf("subject after authorisation = %q, want CUST-1", seen.Subject)
	}
}
