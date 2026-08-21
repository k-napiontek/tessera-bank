package identity_test

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"go/ast"
	"go/parser"
	"go/token"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/identity"
)

// routeTable is the gateway's own scope authority. A fixture may not import it - the gateway is a
// separate module and a load driver has no business appearing on the bank's dependency graph - so
// the test reads the source instead.
const routeTable = "../../../edge/api-gateway/internal/routing/routing.go"

func settings() identity.Settings {
	return identity.Settings{
		Issuer:   "https://issuer.tesserabank.example",
		Audience: "tessera-bank-ledger",
		Scopes:   identity.Scopes(),
		TTL:      time.Hour,
	}
}

func issuer(t *testing.T) *identity.Issuer {
	t.Helper()
	minted, err := identity.Generate(settings())
	if err != nil {
		t.Fatalf("generating an issuer: %v", err)
	}
	return minted
}

// segments splits a token and decodes its header and claims.
func segments(t *testing.T, raw string) (map[string]any, map[string]any, string) {
	t.Helper()
	parts := strings.Split(raw, ".")
	if len(parts) != 3 {
		t.Fatalf("a JWT has three segments, this has %d", len(parts))
	}
	decode := func(segment string) map[string]any {
		bytes, err := base64.RawURLEncoding.DecodeString(segment)
		if err != nil {
			t.Fatalf("segment is not base64url: %v", err)
		}
		var out map[string]any
		if err := json.Unmarshal(bytes, &out); err != nil {
			t.Fatalf("segment is not JSON: %v", err)
		}
		return out
	}
	return decode(parts[0]), decode(parts[1]), parts[0] + "." + parts[1]
}

func TestATokenIsSignedWithTheAlgorithmTheGatewayAccepts(t *testing.T) {
	// The gateway verifies asymmetric algorithms only, so a token it can verify is one it could
	// never have minted. RS256 is what dev-token.mjs produces and what this has to match.
	minted := issuer(t)
	raw, err := minted.Token("CU0000000001", time.Now())
	if err != nil {
		t.Fatalf("minting: %v", err)
	}

	head, _, signingInput := segments(t, raw)
	if head["alg"] != "RS256" {
		t.Errorf("alg is %v, want RS256", head["alg"])
	}
	if head["typ"] != "JWT" {
		t.Errorf("typ is %v, want JWT", head["typ"])
	}

	// Verified with the public key as the gateway reads it - through the PEM, not through the
	// private key still in memory. A PEM that does not verify its own tokens is the failure that
	// costs an hour of a run producing 401s.
	block, _ := pem.Decode(publicKey(t, minted))
	parsed, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		t.Fatalf("the public key does not parse: %v", err)
	}
	public, isRSA := parsed.(*rsa.PublicKey)
	if !isRSA {
		t.Fatalf("the key is %T, and the gateway verifies RSA and ECDSA", parsed)
	}

	signature, err := base64.RawURLEncoding.DecodeString(strings.Split(raw, ".")[2])
	if err != nil {
		t.Fatalf("the signature is not base64url: %v", err)
	}
	digest := sha256.Sum256([]byte(signingInput))
	if err := rsa.VerifyPKCS1v15(public, crypto.SHA256, digest[:], signature); err != nil {
		t.Errorf("the token does not verify against its own public key: %v", err)
	}
}

func TestThePublicKeyIsThePemTheGatewayLoads(t *testing.T) {
	// auth.loadKeys accepts a PUBLIC KEY block holding a PKIX key, and reports "contains no public
	// key" for anything else. That failure arrives at gateway start-up, long after the run began.
	block, rest := pem.Decode(publicKey(t, issuer(t)))
	if block == nil {
		t.Fatal("the key is not PEM at all")
	}
	if block.Type != "PUBLIC KEY" {
		t.Errorf("the block is %q, and the gateway reads PUBLIC KEY or CERTIFICATE", block.Type)
	}
	if len(rest) != 0 {
		t.Errorf("%d trailing bytes after the block", len(rest))
	}
	if _, err := x509.ParsePKIXPublicKey(block.Bytes); err != nil {
		t.Errorf("PKIX parse: %v", err)
	}
}

func TestTheClaimsAreTheOnesTheGatewayRequires(t *testing.T) {
	now := time.Date(2026, 8, 31, 9, 0, 0, 0, time.UTC)
	raw, err := issuer(t).Token("CU0000123456", now)
	if err != nil {
		t.Fatalf("minting: %v", err)
	}
	_, body, _ := segments(t, raw)

	// jwt.WithExpirationRequired means a token with no exp is refused outright, and a missing iss
	// or aud is refused by the parser rather than by the handler.
	for _, claim := range []string{"sub", "iss", "aud", "scope", "iat", "nbf", "exp"} {
		if _, present := body[claim]; !present {
			t.Errorf("the token carries no %s", claim)
		}
	}
	if body["sub"] != "CU0000123456" {
		t.Errorf("sub is %v", body["sub"])
	}
	if body["iss"] != settings().Issuer {
		t.Errorf("iss is %v, want %s", body["iss"], settings().Issuer)
	}
	if body["aud"] != settings().Audience {
		t.Errorf("aud is %v, want %s", body["aud"], settings().Audience)
	}
	if expiry, _ := body["exp"].(float64); int64(expiry) != now.Add(time.Hour).Unix() {
		t.Errorf("exp is %v, want %d", body["exp"], now.Add(time.Hour).Unix())
	}
}

func TestTheScopesAreTheOnesTheGatewayRouteTableNames(t *testing.T) {
	// dev-token.mjs says of the same three strings: "copied from the route table rather than
	// guessed. Getting these wrong is a 403 that reads exactly like a bad token." This is that
	// comment made into a test, which is the difference between knowing the trap and being caught
	// by it when the gateway adds a fourth scope.
	declared := scopesInRouteTable(t)
	minted := append([]string(nil), identity.Scopes()...)
	sort.Strings(declared)
	sort.Strings(minted)

	if strings.Join(declared, " ") != strings.Join(minted, " ") {
		t.Errorf("the gateway routes %v and this mints %v", declared, minted)
	}
}

func TestSettingsThatWouldMintAnUnusableTokenAreRefused(t *testing.T) {
	// Each of these produces a 401 at the gateway that says nothing about which field was wrong.
	// Refusing at the point the fixture is configured is the only place the answer is cheap.
	cases := map[string]identity.Settings{
		"no issuer":   {Audience: "a", Scopes: identity.Scopes(), TTL: time.Hour},
		"no audience": {Issuer: "i", Scopes: identity.Scopes(), TTL: time.Hour},
		"no scopes":   {Issuer: "i", Audience: "a", TTL: time.Hour},
		"no ttl":      {Issuer: "i", Audience: "a", Scopes: identity.Scopes()},
	}
	for name, broken := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := identity.Generate(broken); err == nil {
				t.Error("accepted settings that cannot mint a usable token")
			}
		})
	}
}

func TestATokenForNobodyIsRefused(t *testing.T) {
	// The gateway rejects a token with no subject, having already done the signature work. There is
	// nobody to rate limit and nobody to name in a log, so there is nothing to mint.
	if _, err := issuer(t).Token("", time.Now()); err != identity.ErrNoSubject {
		t.Errorf("minting for nobody returned %v", err)
	}
}

func TestTheWalletMintsOncePerSubject(t *testing.T) {
	now := time.Date(2026, 8, 31, 9, 0, 0, 0, time.UTC)
	wallet := identity.NewWallet(issuer(t))

	first, err := wallet.For("CU0000000001", now)
	if err != nil {
		t.Fatalf("minting: %v", err)
	}
	again, err := wallet.For("CU0000000001", now.Add(time.Second))
	if err != nil {
		t.Fatalf("minting: %v", err)
	}
	if first != again {
		t.Error("the wallet re-signed a token it already held, which is a millisecond of RSA in " +
			"the request path measured as the bank's latency")
	}

	other, err := wallet.For("CU0000000002", now)
	if err != nil {
		t.Fatalf("minting: %v", err)
	}
	if other == first {
		t.Fatal("two subjects share a token, so the rate limiter sees one caller")
	}
	if wallet.Held() != 2 {
		t.Errorf("the wallet holds %d tokens, want 2", wallet.Held())
	}
}

func TestTheWalletReplacesATokenBeforeItExpires(t *testing.T) {
	// Not after. A token that expires between the check and the response is a 401 in the middle of
	// a run, and it presents as the estate refusing a request it should have served.
	now := time.Date(2026, 8, 31, 9, 0, 0, 0, time.UTC)
	minted, err := identity.Generate(identity.Settings{
		Issuer: "i", Audience: "a", Scopes: identity.Scopes(), TTL: 90 * time.Second,
	})
	if err != nil {
		t.Fatalf("generating: %v", err)
	}
	wallet := identity.NewWallet(minted)

	first, _ := wallet.For("CU0000000001", now)
	// 40 seconds in: 50 seconds of life left, which is inside the renewal margin.
	renewed, _ := wallet.For("CU0000000001", now.Add(40*time.Second))
	if renewed == first {
		t.Error("a token with less than a minute left was handed out again")
	}
}

func TestTheWalletIsSafeUnderConcurrentDraw(t *testing.T) {
	// The driver draws tokens from as many goroutines as it has requests in flight. Run under
	// -race, which is how make test-workload runs the whole module.
	wallet := identity.NewWallet(issuer(t))
	now := time.Now()

	var group sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		group.Add(1)
		go func(worker int) {
			defer group.Done()
			for draw := 0; draw < 25; draw++ {
				if _, err := wallet.For("CU000000000"+strconv.Itoa(draw%5), now); err != nil {
					t.Errorf("minting: %v", err)
					return
				}
			}
		}(worker)
	}
	group.Wait()

	if wallet.Held() != 5 {
		t.Errorf("the wallet holds %d tokens for five subjects", wallet.Held())
	}
}

func publicKey(t *testing.T, minted *identity.Issuer) []byte {
	t.Helper()
	key, err := minted.PublicKeyPEM()
	if err != nil {
		t.Fatalf("rendering the public key: %v", err)
	}
	return key
}

// scopesInRouteTable reads the scope constants out of the gateway's routing package.
func scopesInRouteTable(t *testing.T) []string {
	t.Helper()
	file, err := parser.ParseFile(token.NewFileSet(), routeTable, nil, 0)
	if err != nil {
		t.Fatalf("reading %s: %v", routeTable, err)
	}

	var found []string
	for _, declaration := range file.Decls {
		general, isGeneral := declaration.(*ast.GenDecl)
		if !isGeneral || general.Tok != token.CONST {
			continue
		}
		for _, spec := range general.Specs {
			value, isValue := spec.(*ast.ValueSpec)
			if !isValue {
				continue
			}
			for index, name := range value.Names {
				if !strings.HasPrefix(name.Name, "Scope") || index >= len(value.Values) {
					continue
				}
				literal, isLiteral := value.Values[index].(*ast.BasicLit)
				if !isLiteral || literal.Kind != token.STRING {
					continue
				}
				unquoted, err := strconv.Unquote(literal.Value)
				if err != nil {
					t.Fatalf("unquoting %s: %v", literal.Value, err)
				}
				found = append(found, unquoted)
			}
		}
	}

	if len(found) == 0 {
		t.Fatalf("%s declares no Scope constants - the reader is looking at the wrong file", routeTable)
	}
	return found
}
