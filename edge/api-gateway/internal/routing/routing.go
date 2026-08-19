// Package routing decides which of the ledger's operations a request is asking for, and refuses
// anything that is not one of them.
//
// The table is the gateway's entire knowledge of the ledger: a method, a path shape, the scope it
// needs and the name it is measured under. It knows that POST /transfers exists; it does not know
// what a transfer is, and nothing in this package may ever need to.
//
// Refusing an unrecognised path is the point. The ledger serves its actuator endpoints on the same
// port as its API, so a gateway that forwards whatever it does not recognise publishes the bank's
// health detail and metrics to the internet.
package routing

import (
	"context"
	"net/http"
	"sort"
	"strings"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/problem"
)

// The scopes the edge understands. They are coarse on purpose: a scope says what kind of thing a
// caller may do, and the ledger decides whether this caller may do it to this account. Splitting
// that decision across two tiers is how it ends up enforced in neither.
const (
	ScopeRead    = "ledger:read"
	ScopeWrite   = "ledger:write"
	ScopeAccount = "accounts:manage"
)

// Route is one operation of the ledger's API.
type Route struct {
	// Method is the HTTP method, upper case.
	Method string
	// Path is the OpenAPI path template, braces and all.
	Path string
	// Scope is the permission a token must carry to use the route.
	Scope string
	// Class names the route in metrics and in the rate limiter. It is bounded, unlike a path, which
	// carries an account reference and would give every account its own metric series.
	Class string
}

// Routes is the table. It mirrors contracts/openapi/ledger-core.yaml exactly, and a test in this
// package fails if the two ever disagree.
func Routes() []Route {
	return []Route{
		{http.MethodPost, "/accounts", ScopeAccount, "accounts.open"},
		{http.MethodGet, "/accounts/{accountRef}", ScopeRead, "accounts.get"},
		{http.MethodGet, "/accounts/{accountRef}/balance", ScopeRead, "accounts.balance"},
		{http.MethodGet, "/accounts/{accountRef}/statement", ScopeRead, "accounts.statement"},
		{http.MethodGet, "/accounts/{accountRef}/holds", ScopeRead, "holds.list"},
		{http.MethodPost, "/accounts/{accountRef}/holds", ScopeWrite, "holds.place"},
		{http.MethodPost, "/transfers", ScopeWrite, "transfers.create"},
		{http.MethodGet, "/transfers/{transferRef}", ScopeRead, "transfers.get"},
		{http.MethodPost, "/transfers/{transferRef}/reversals", ScopeWrite, "transfers.reverse"},
		{http.MethodPost, "/holds/{holdRef}/capture", ScopeWrite, "holds.capture"},
		{http.MethodPost, "/holds/{holdRef}/release", ScopeWrite, "holds.release"},
	}
}

type contextKey struct{}

// WithRoute puts a matched route on a context.
func WithRoute(ctx context.Context, route Route) context.Context {
	return context.WithValue(ctx, contextKey{}, route)
}

// FromContext returns the route the request matched.
func FromContext(ctx context.Context) (Route, bool) {
	route, ok := ctx.Value(contextKey{}).(Route)
	return route, ok
}

// Middleware matches the request against the table and puts the route on the context, or answers
// 404 or 405 itself.
//
// The matching is written here rather than delegated to http.ServeMux because the gateway must
// answer a mismatch with a Problem document, and ServeMux answers a method mismatch with plain text
// of its own before any handler runs.
func Middleware(routes []Route) func(http.Handler) http.Handler {
	compiled := compile(routes)

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			segments, ok := split(r.URL.Path)
			if !ok {
				problem.Write(w, r, http.StatusNotFound, problem.NoRoute, "No such route.")
				return
			}

			var allowed []string
			for _, candidate := range compiled {
				if !candidate.matches(segments) {
					continue
				}
				if candidate.route.Method == r.Method {
					next.ServeHTTP(w, r.WithContext(WithRoute(r.Context(), candidate.route)))
					return
				}
				allowed = append(allowed, candidate.route.Method)
			}

			if len(allowed) > 0 {
				sort.Strings(allowed)
				// RFC 9110 requires Allow on a 405, and it saves the client a second request to
				// discover what the resource does support.
				w.Header().Set("Allow", strings.Join(allowed, ", "))
				problem.Write(w, r, http.StatusMethodNotAllowed, problem.NoRoute,
					"That method is not available on this resource.")
				return
			}
			problem.Write(w, r, http.StatusNotFound, problem.NoRoute, "No such route.")
		})
	}
}

// pattern is a route with its path pre-split into segments.
type pattern struct {
	route    Route
	segments []string
}

func compile(routes []Route) []pattern {
	compiled := make([]pattern, 0, len(routes))
	for _, route := range routes {
		segments, ok := split(route.Path)
		if !ok {
			// A table entry that cannot be split is a programming error in this file, not input.
			panic("routing: unusable path in the table: " + route.Path)
		}
		compiled = append(compiled, pattern{route: route, segments: segments})
	}
	return compiled
}

// matches reports whether the request's segments fit the pattern. A braced segment matches exactly
// one non-empty segment; everything else must match literally.
func (p pattern) matches(segments []string) bool {
	if len(segments) != len(p.segments) {
		return false
	}
	for i, expected := range p.segments {
		if isWildcard(expected) {
			continue
		}
		if segments[i] != expected {
			return false
		}
	}
	return true
}

func isWildcard(segment string) bool {
	return strings.HasPrefix(segment, "{") && strings.HasSuffix(segment, "}")
}

// split breaks a path into its segments, rejecting anything that is not exactly one spelling of one
// resource: no empty segment, and no trailing slash. Two spellings of one route would give the rate
// limiter and the metrics two names for the same thing, and a limit that is trivially doubled is
// not a limit.
func split(path string) ([]string, bool) {
	if !strings.HasPrefix(path, "/") || path == "/" {
		return nil, false
	}
	segments := strings.Split(strings.TrimPrefix(path, "/"), "/")
	for _, segment := range segments {
		if segment == "" {
			return nil, false
		}
	}
	return segments, true
}
