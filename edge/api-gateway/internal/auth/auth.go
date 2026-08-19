// Package auth authenticates the customer, once, at the edge.
//
// The gateway validates a bearer token and forwards it unchanged. It issues nothing: minting a
// token here would make the edge an identity provider, and an identity provider needs a signing key
// - a secret this component deliberately never holds. Everything in this file is public-key
// material and public-key operations.
//
// Two rules stop the classic JWT forgeries, and both work by refusing to let the token decide how
// it is checked:
//
//   - The accepted algorithms are pinned to asymmetric ones. A verifier that honours the header's
//     alg accepts HS256 signed with the RSA public key - which is public, so anybody can mint a
//     token. This is not theoretical; it is the most common JWT vulnerability there is.
//   - alg=none is not in the list, so a token that declares it needs no signature is refused rather
//     than believed.
package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/problem"
)

// leeway absorbs the difference between the issuer's clock and this one. It is a tolerance for
// skew, not a grace period: a token minutes past its expiry is expired.
const leeway = 30 * time.Second

// signatureAlgorithms is the whole list the gateway will verify. Every entry is asymmetric, so a
// token can never be verified with a key that is also enough to sign one.
var signatureAlgorithms = []string{"RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512"}

// Settings are what the verifier needs from the configuration.
type Settings struct {
	Issuer   string
	Audience string
	KeysPath string
}

// Principal is who the request is from, as far as the edge can tell.
type Principal struct {
	// Subject is the customer reference from the token. It is pseudonymous - the join to a person
	// happens in customer-master, deliberately.
	Subject string
	// Scopes are the coarse permissions the token carries.
	Scopes []string
}

// HasScope reports whether the token carries the named scope.
func (p Principal) HasScope(name string) bool {
	for _, scope := range p.Scopes {
		if scope == name {
			return true
		}
	}
	return false
}

type contextKey struct{}

// WithPrincipal puts an authenticated principal on a context. The middleware uses it, and so does
// any test that needs a request to look as though authentication has already run.
func WithPrincipal(ctx context.Context, principal Principal) context.Context {
	return context.WithValue(ctx, contextKey{}, principal)
}

// PrincipalFrom returns the authenticated principal, if the middleware ran and allowed the request.
func PrincipalFrom(ctx context.Context) (Principal, bool) {
	principal, ok := ctx.Value(contextKey{}).(Principal)
	return principal, ok
}

// Verifier checks tokens against the configured issuer, audience and keys.
type Verifier struct {
	parser *jwt.Parser
	keys   jwt.VerificationKeySet
}

// claims is the registered set plus the scope claim RFC 9068 defines for an access token.
type claims struct {
	Scope string `json:"scope,omitempty"`
	jwt.RegisteredClaims
}

// NewVerifier loads the public keys and builds the parser.
func NewVerifier(settings Settings) (*Verifier, error) {
	keys, err := loadKeys(settings.KeysPath)
	if err != nil {
		return nil, err
	}
	return &Verifier{
		parser: jwt.NewParser(
			jwt.WithValidMethods(signatureAlgorithms),
			jwt.WithIssuer(settings.Issuer),
			jwt.WithAudience(settings.Audience),
			// Without this a token with no exp validates forever, which is a credential that never
			// expires.
			jwt.WithExpirationRequired(),
			jwt.WithLeeway(leeway),
		),
		keys: keys,
	}, nil
}

// Verify returns the principal a token names, or an error describing what failed. The description
// is for the log, never for the caller.
func (v *Verifier) Verify(raw string) (Principal, error) {
	var parsed claims
	if _, err := v.parser.ParseWithClaims(raw, &parsed, func(*jwt.Token) (any, error) {
		// Every configured key is offered, so a key rotation is not an outage: the outgoing and the
		// incoming key both verify while tokens signed by either are still in flight.
		return v.keys, nil
	}); err != nil {
		return Principal{}, err
	}

	if parsed.Subject == "" {
		// Nobody to rate limit, nobody to authorise, nobody to name in an audit trail.
		return Principal{}, errors.New("the token carries no subject")
	}
	return Principal{Subject: parsed.Subject, Scopes: strings.Fields(parsed.Scope)}, nil
}

// Middleware rejects any request that does not carry a valid bearer token.
func Middleware(verifier *Verifier) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := bearerToken(r.Header.Get("Authorization"))
			if !ok {
				reject(w, r, errors.New("no bearer token was presented"))
				return
			}
			principal, err := verifier.Verify(token)
			if err != nil {
				reject(w, r, err)
				return
			}
			next.ServeHTTP(w, r.WithContext(WithPrincipal(r.Context(), principal)))
		})
	}
}

// bearerToken extracts the credential from an Authorization header. RFC 7235 makes the scheme
// case-insensitive; the token itself is not.
func bearerToken(header string) (string, bool) {
	scheme, credential, found := strings.Cut(header, " ")
	if !found || !strings.EqualFold(scheme, "Bearer") {
		return "", false
	}
	credential = strings.TrimSpace(credential)
	if credential == "" || strings.ContainsAny(credential, " \t") {
		return "", false
	}
	return credential, true
}

func reject(w http.ResponseWriter, r *http.Request, reason error) {
	// The reason is logged and not served. Telling a caller which check failed tells them which one
	// to work on next; the operator, who has the log, is the one who needs to know.
	logging.FromContext(r.Context()).Warn("authentication refused", "reason", reason.Error())

	// RFC 6750 requires the challenge on a 401. The realm is the estate, not this process.
	w.Header().Set("WWW-Authenticate", `Bearer realm="tessera-bank"`)
	problem.Write(w, r, http.StatusUnauthorized, problem.Unauthenticated,
		"A valid bearer token is required.")
}

// loadKeys reads every public key in a PEM file.
func loadKeys(path string) (jwt.VerificationKeySet, error) {
	contents, err := os.ReadFile(path)
	if err != nil {
		return jwt.VerificationKeySet{}, fmt.Errorf("cannot read the JWT key file: %w", err)
	}

	var set jwt.VerificationKeySet
	rest := contents
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		key, err := publicKeyFrom(block)
		if err != nil {
			return jwt.VerificationKeySet{}, fmt.Errorf("%s: %w", path, err)
		}
		set.Keys = append(set.Keys, key)
	}

	if len(set.Keys) == 0 {
		return jwt.VerificationKeySet{}, fmt.Errorf("%s contains no public key", path)
	}
	return set, nil
}

func publicKeyFrom(block *pem.Block) (any, error) {
	switch block.Type {
	case "PUBLIC KEY":
		key, err := x509.ParsePKIXPublicKey(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("unreadable public key: %w", err)
		}
		switch key.(type) {
		case *rsa.PublicKey, *ecdsa.PublicKey:
			return key, nil
		default:
			return nil, fmt.Errorf("unsupported public key type %T", key)
		}
	case "CERTIFICATE":
		certificate, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("unreadable certificate: %w", err)
		}
		return certificate.PublicKey, nil
	default:
		// Anything else - a private key above all. A signing key at the edge would mean the gateway
		// can mint tokens, so a key file containing one is a deployment mistake worth refusing to
		// start over rather than quietly ignoring.
		return nil, fmt.Errorf("a %q block does not belong in the JWT key file", block.Type)
	}
}
