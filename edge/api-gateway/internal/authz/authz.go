// Package authz decides whether an authenticated caller may use the route they asked for.
//
// The decision is deliberately coarse: a scope says what kind of operation a caller may perform,
// and the ledger decides whether this caller may perform it on this account. Splitting one decision
// across two tiers is how it ends up enforced in neither, and a gateway that knows which accounts a
// customer owns is a gateway that has grown business logic.
package authz

import (
	"net/http"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/problem"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

// Middleware refuses a request whose token does not carry the scope its route requires.
//
// It runs after authentication and after routing, and it refuses rather than defaults when either
// is missing. A misordered chain is a silent, total failure - every request admitted - so the
// impossible case is the one worth handling.
func Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		principal, authenticated := auth.PrincipalFrom(r.Context())
		if !authenticated {
			problem.Write(w, r, http.StatusUnauthorized, problem.Unauthenticated,
				"A valid bearer token is required.")
			return
		}
		route, matched := routing.FromContext(r.Context())
		if !matched {
			problem.Write(w, r, http.StatusNotFound, problem.NoRoute, "No such route.")
			return
		}

		if !principal.HasScope(route.Scope) {
			// The subject is a pseudonymous customer reference, which is what this estate logs; the
			// scopes are not personal data either. Neither is served back.
			logging.FromContext(r.Context()).Warn("authorisation refused",
				"subject", principal.Subject,
				"route", route.Class,
				"required_scope", route.Scope,
			)
			// 403 and no challenge: the caller proved who they are, and authenticating again will
			// not change the answer. A 401 here sends a well-behaved client into a refresh loop.
			problem.Write(w, r, http.StatusForbidden, problem.Forbidden,
				"This token does not permit that operation.")
			return
		}

		next.ServeHTTP(w, r)
	})
}
