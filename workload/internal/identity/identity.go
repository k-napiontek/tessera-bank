// Package identity mints the access tokens a run presents to edge/api-gateway.
//
// **This is a test fixture, not a component of the bank.** ADR 0007 records that nothing in this
// estate issues tokens: the gateway validates a bearer token and deliberately holds no signing key,
// because an edge component that could mint an identity could mint any identity in the bank. A real
// deployment puts an identity provider in front of it. This package is the Go equivalent of
// edge/web-banking/scripts/dev-token.mjs, which mints one token at a time for a walkthrough.
//
// A run needs thousands, and the reason is the rate limiter rather than realism. The gateway buckets
// on subject and route class (ratelimit.Middleware), so a run that drives a whole population through
// one token is refused by a control that is working correctly, and reports it as the bank being
// slow. One subject per synthetic customer keeps the limiter measuring what it exists to measure.
//
// Standard library only, like the rest of this module: crypto/rsa signs, encoding/json and
// encoding/base64 render, and nothing here needs a JWT library to produce the three segments the
// gateway parses.
package identity

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

// Scopes is the scope set the ledger's routes require, spelled as
// edge/api-gateway/internal/routing spells them.
//
// Copied rather than imported: the gateway is a separate module, and a load fixture may not put
// itself on the bank's dependency graph to save three string literals. identity_test.go reads that
// route table and fails if the two ever drift, which is the control dev-token.mjs asks for in a
// comment - getting these wrong is a 403 that reads exactly like a bad token.
func Scopes() []string { return []string{"ledger:read", "ledger:write", "accounts:manage"} }

// keySize is the modulus the gateway's walkthrough fixture uses. Large enough to be a real RSA key
// and small enough that generating one does not delay the start of a run.
const keySize = 2048

// Settings are the claims every minted token carries. The issuer and the audience have no defaults
// worth having: a token minted for the wrong audience is refused by the gateway with the same 401
// as a forged one, and guessing them here would turn a configuration mistake into a mystery.
type Settings struct {
	Issuer   string
	Audience string
	// Scopes is the space-separated scope claim, as a slice. The gateway's route table is the
	// authority for what these may be, and a test reads them out of it rather than trusting this
	// comment.
	Scopes []string
	// TTL is how long a minted token stays valid. It bounds how long a run may hold one, and the
	// wallet mints a replacement before it expires rather than after.
	TTL time.Duration
}

func (s Settings) validate() error {
	var missing []string
	if s.Issuer == "" {
		missing = append(missing, "issuer")
	}
	if s.Audience == "" {
		missing = append(missing, "audience")
	}
	if len(s.Scopes) == 0 {
		missing = append(missing, "scopes")
	}
	if s.TTL <= 0 {
		missing = append(missing, "a positive ttl")
	}
	if len(missing) > 0 {
		return fmt.Errorf("identity: the token settings need %s", strings.Join(missing, ", "))
	}
	return nil
}

// Issuer signs tokens for synthetic subjects with one key pair.
//
// One key pair for the whole population, not one per subject: the subject is a claim, and a
// gateway that had to be told a new public key per customer would be an identity provider with a
// slow interface rather than a fixture.
type Issuer struct {
	settings Settings
	key      *rsa.PrivateKey
}

// Generate builds an issuer over a freshly generated key pair.
func Generate(settings Settings) (*Issuer, error) {
	if err := settings.validate(); err != nil {
		return nil, err
	}
	key, err := rsa.GenerateKey(rand.Reader, keySize)
	if err != nil {
		return nil, fmt.Errorf("identity: generating a key pair: %w", err)
	}
	return &Issuer{settings: settings, key: key}, nil
}

// PublicKeyPEM renders the verification key in the form the gateway reads: one SPKI block, which
// is what TB_GATEWAY_JWT_KEYS points at and what auth.loadKeys parses.
func (i *Issuer) PublicKeyPEM() ([]byte, error) {
	encoded, err := x509.MarshalPKIXPublicKey(&i.key.PublicKey)
	if err != nil {
		return nil, fmt.Errorf("identity: encoding the public key: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: encoded}), nil
}

// header is fixed for every token this package mints.
type header struct {
	Algorithm string `json:"alg"`
	Type      string `json:"typ"`
}

// claims is the registered set plus the scope claim RFC 9068 defines for an access token. The
// gateway requires an expiry, an issuer and an audience, and refuses a token with no subject
// because there would be nobody to rate limit, authorise or name in an audit trail.
type claims struct {
	Subject   string `json:"sub"`
	Issuer    string `json:"iss"`
	Audience  string `json:"aud"`
	Scope     string `json:"scope"`
	IssuedAt  int64  `json:"iat"`
	NotBefore int64  `json:"nbf"`
	Expiry    int64  `json:"exp"`
}

// ErrNoSubject reports a token asked for on behalf of nobody.
var ErrNoSubject = errors.New("identity: a token needs a subject")

// Token mints one token for one subject, valid from now.
func (i *Issuer) Token(subject string, now time.Time) (string, error) {
	if subject == "" {
		return "", ErrNoSubject
	}
	head, err := json.Marshal(header{Algorithm: "RS256", Type: "JWT"})
	if err != nil {
		return "", err
	}
	body, err := json.Marshal(claims{
		Subject:   subject,
		Issuer:    i.settings.Issuer,
		Audience:  i.settings.Audience,
		Scope:     strings.Join(i.settings.Scopes, " "),
		IssuedAt:  now.Unix(),
		NotBefore: now.Unix(),
		Expiry:    now.Add(i.settings.TTL).Unix(),
	})
	if err != nil {
		return "", err
	}

	encode := base64.RawURLEncoding.EncodeToString
	signingInput := encode(head) + "." + encode(body)
	digest := sha256.Sum256([]byte(signingInput))
	signature, err := rsa.SignPKCS1v15(rand.Reader, i.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", fmt.Errorf("identity: signing: %w", err)
	}
	return signingInput + "." + encode(signature), nil
}

// renewBefore is how long before expiry a wallet replaces a token. A token that expires while a
// request is in flight is a 401 that looks exactly like a broken fixture, and the gateway's own
// clock skew leeway is 30 seconds.
const renewBefore = time.Minute

// Wallet holds one live token per subject and mints a replacement before it expires.
//
// An RSA signature costs about a millisecond, which at the rates this driver offers would be the
// slowest thing in the request path and would be measured as the bank's latency. A population's
// worth of tokens is minted once and reused, which is also what a customer application does.
type Wallet struct {
	issuer *Issuer

	mu   sync.Mutex
	held map[string]token
}

type token struct {
	value   string
	expires time.Time
}

// NewWallet builds a wallet over an issuer.
func NewWallet(issuer *Issuer) *Wallet {
	return &Wallet{issuer: issuer, held: map[string]token{}}
}

// For returns the subject's token, minting one if it has none or if the one it holds is close
// enough to expiry that a request carrying it might not arrive in time.
func (w *Wallet) For(subject string, now time.Time) (string, error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	if held, found := w.held[subject]; found && now.Add(renewBefore).Before(held.expires) {
		return held.value, nil
	}
	value, err := w.issuer.Token(subject, now)
	if err != nil {
		return "", err
	}
	w.held[subject] = token{value: value, expires: now.Add(w.issuer.settings.TTL)}
	return value, nil
}

// Held is how many subjects the wallet is holding a token for. Reported so that a run can say how
// many identities it presented, which is the figure that explains the rate limiter's behaviour.
func (w *Wallet) Held() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.held)
}
