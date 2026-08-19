package auth_test

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
)

const (
	issuer   = "https://issuer.tesserabank.example"
	audience = "tessera-bank-ledger"
	subject  = "CUST-00000042"
)

// One key pair for the whole file: generating RSA keys is the slowest thing these tests do.
var (
	once     sync.Once
	signing  *rsa.PrivateKey
	imposter *rsa.PrivateKey
)

func keys(t *testing.T) (*rsa.PrivateKey, *rsa.PrivateKey) {
	t.Helper()
	once.Do(func() {
		var err error
		if signing, err = rsa.GenerateKey(rand.Reader, 2048); err != nil {
			panic(err)
		}
		if imposter, err = rsa.GenerateKey(rand.Reader, 2048); err != nil {
			panic(err)
		}
	})
	return signing, imposter
}

func publicKeyFile(t *testing.T, keys ...*rsa.PrivateKey) string {
	t.Helper()
	var buffer strings.Builder
	for _, key := range keys {
		encoded, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
		if err != nil {
			t.Fatalf("marshal public key: %v", err)
		}
		if err := pem.Encode(&buffer, &pem.Block{Type: "PUBLIC KEY", Bytes: encoded}); err != nil {
			t.Fatalf("encode pem: %v", err)
		}
	}
	return write(t, "keys.pem", buffer.String())
}

func write(t *testing.T, name, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write %s: %v", name, err)
	}
	return path
}

func verifier(t *testing.T, path string) *auth.Verifier {
	t.Helper()
	v, err := auth.NewVerifier(auth.Settings{Issuer: issuer, Audience: audience, KeysPath: path})
	if err != nil {
		t.Fatalf("new verifier: %v", err)
	}
	return v
}

type claims struct {
	Scope string `json:"scope,omitempty"`
	jwt.RegisteredClaims
}

func signed(t *testing.T, key *rsa.PrivateKey, c claims) string {
	t.Helper()
	token, err := jwt.NewWithClaims(jwt.SigningMethodRS256, c).SignedString(key)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return token
}

func valid() claims {
	now := time.Now()
	return claims{
		Scope: "ledger:read ledger:write",
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    issuer,
			Subject:   subject,
			Audience:  jwt.ClaimStrings{audience},
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(time.Hour)),
		},
	}
}

// call drives one request through the correlation and authentication middleware.
func call(t *testing.T, v *auth.Verifier, authorization string) (*httptest.ResponseRecorder, *auth.Principal) {
	t.Helper()

	var reached *auth.Principal
	handler := correlation.Middleware(auth.Middleware(v)(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		if principal, ok := auth.PrincipalFrom(r.Context()); ok {
			reached = &principal
		}
	})))

	request := httptest.NewRequest(http.MethodPost, "/transfers", nil)
	if authorization != "" {
		request.Header.Set("Authorization", authorization)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	return recorder, reached
}

func TestAValidTokenReachesTheHandler(t *testing.T) {
	signing, _ := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	response, principal := call(t, v, "Bearer "+signed(t, signing, valid()))

	if principal == nil {
		t.Fatalf("a valid token was rejected: %d %s", response.Code, response.Body)
	}
	if principal.Subject != subject {
		t.Errorf("subject = %q, want %q", principal.Subject, subject)
	}
	if !principal.HasScope("ledger:write") || !principal.HasScope("ledger:read") {
		t.Errorf("scopes = %v, want both ledger:read and ledger:write", principal.Scopes)
	}
	if principal.HasScope("ledger:admin") {
		t.Error("a scope the token does not carry was granted")
	}
}

func TestAlgNoneIsRejected(t *testing.T) {
	signing, _ := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	unsigned, err := jwt.NewWithClaims(jwt.SigningMethodNone, valid()).SignedString(jwt.UnsafeAllowNoneSignatureType)
	if err != nil {
		t.Fatalf("sign none: %v", err)
	}

	// The oldest JWT vulnerability there is: a token that declares it needs no signature, accepted
	// by a verifier that believes the token about what verifying it requires.
	response, principal := call(t, v, "Bearer "+unsigned)
	if principal != nil {
		t.Fatal("a token signed with alg=none was accepted")
	}
	if response.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", response.Code)
	}
}

func TestAlgorithmConfusionIsRejected(t *testing.T) {
	signing, _ := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	// The RSA public key, used as an HMAC secret. The key is public by definition, so a verifier
	// that lets the token choose a symmetric algorithm lets anybody mint a token.
	encoded, err := x509.MarshalPKIXPublicKey(&signing.PublicKey)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	secret := pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: encoded})
	forged, err := jwt.NewWithClaims(jwt.SigningMethodHS256, valid()).SignedString(secret)
	if err != nil {
		t.Fatalf("sign hs256: %v", err)
	}

	if _, principal := call(t, v, "Bearer "+forged); principal != nil {
		t.Fatal("a token signed with HS256 over the public key was accepted")
	}
}

func TestATokenSignedByAnotherKeyIsRejected(t *testing.T) {
	signing, imposter := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	if _, principal := call(t, v, "Bearer "+signed(t, imposter, valid())); principal != nil {
		t.Fatal("a token signed by an unknown key was accepted")
	}
}

func TestClaimsAreValidated(t *testing.T) {
	signing, _ := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	expired := valid()
	expired.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-2 * time.Hour))

	notYet := valid()
	notYet.NotBefore = jwt.NewNumericDate(time.Now().Add(time.Hour))

	noExpiry := valid()
	noExpiry.ExpiresAt = nil

	wrongIssuer := valid()
	wrongIssuer.Issuer = "https://issuer.attacker.example"

	wrongAudience := valid()
	wrongAudience.Audience = jwt.ClaimStrings{"some-other-service"}

	noSubject := valid()
	noSubject.Subject = ""

	cases := map[string]claims{
		"expired":       expired,
		"not yet valid": notYet,
		// A token without an expiry never stops being valid, which is the same as a password that
		// is never rotated.
		"no expiry":      noExpiry,
		"wrong issuer":   wrongIssuer,
		"wrong audience": wrongAudience,
		// Without a subject there is nobody to rate limit, nobody to authorise and nobody to name
		// in an audit trail.
		"no subject": noSubject,
	}
	for name, c := range cases {
		if _, principal := call(t, v, "Bearer "+signed(t, signing, c)); principal != nil {
			t.Errorf("a token that is %s was accepted", name)
		}
	}
}

func TestAMalformedAuthorizationHeaderIsRejected(t *testing.T) {
	signing, _ := keys(t)
	v := verifier(t, publicKeyFile(t, signing))
	token := signed(t, signing, valid())

	for name, header := range map[string]string{
		"absent":       "",
		"empty bearer": "Bearer ",
		"wrong scheme": "Basic dXNlcjpwYXNz",
		"no scheme":    token,
		"two tokens":   "Bearer " + token + " " + token,
		"not a jwt":    "Bearer not.a.jwt",
		"truncated":    "Bearer " + token[:len(token)-8],
	} {
		response, principal := call(t, v, header)
		if principal != nil {
			t.Errorf("an authorization header that is %s was accepted", name)
			continue
		}
		if response.Code != http.StatusUnauthorized {
			t.Errorf("%s: status = %d, want 401", name, response.Code)
		}
	}
}

func TestTheSchemeIsCaseInsensitive(t *testing.T) {
	signing, _ := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	// RFC 7235: the scheme is a case-insensitive token. A gateway that only accepts "Bearer"
	// rejects a conforming client for a reason no error message will ever explain.
	if _, principal := call(t, v, "bearer "+signed(t, signing, valid())); principal == nil {
		t.Error("a lower-case bearer scheme was rejected")
	}
}

func TestARejectionSaysNothingAboutWhy(t *testing.T) {
	signing, imposter := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	response, _ := call(t, v, "Bearer "+signed(t, imposter, valid()))

	if got := response.Header().Get("WWW-Authenticate"); !strings.HasPrefix(got, "Bearer") {
		t.Errorf("WWW-Authenticate = %q, want a Bearer challenge", got)
	}
	if got := response.Header().Get("Content-Type"); got != "application/problem+json" {
		t.Errorf("content type = %q", got)
	}

	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("body is not JSON: %v", err)
	}
	if body["type"] != "https://problems.tesserabank.example/unauthenticated" {
		t.Errorf("type = %v", body["type"])
	}
	// The reason belongs in the log line, not in the response. Telling a caller which check failed
	// tells them which one to work on next.
	detail := strings.ToLower(body["detail"].(string))
	for _, leak := range []string{"signature", "issuer", "audience", "expired", "key", "claim"} {
		if strings.Contains(detail, leak) {
			t.Errorf("detail %q names the failing check", detail)
		}
	}
}

func TestKeyLoadingRefusesAFileThatIsNotUsable(t *testing.T) {
	signing, _ := keys(t)

	private := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(signing),
	})

	for name, path := range map[string]string{
		"missing file": filepath.Join(t.TempDir(), "absent.pem"),
		"empty file":   write(t, "empty.pem", ""),
		"not pem":      write(t, "text.pem", "this is not a key\n"),
		// A signing key at the edge means the gateway can mint tokens. It must not be able to, and
		// a key file that contains one is a deployment mistake worth refusing to start over.
		"private key": write(t, "private.pem", string(private)),
	} {
		if _, err := auth.NewVerifier(auth.Settings{Issuer: issuer, Audience: audience, KeysPath: path}); err == nil {
			t.Errorf("a key file that is a %s was accepted", name)
		}
	}
}

func TestMoreThanOneKeyIsAcceptedSoKeysCanRotate(t *testing.T) {
	signing, imposter := keys(t)
	// During a rotation both the outgoing and the incoming key verify. A gateway that holds one
	// key at a time makes every rotation an outage.
	v := verifier(t, publicKeyFile(t, imposter, signing))

	if _, principal := call(t, v, "Bearer "+signed(t, signing, valid())); principal == nil {
		t.Error("a token signed by the second key in the file was rejected")
	}
}

func TestClockSkewIsToleratedButNotIndefinitely(t *testing.T) {
	signing, _ := keys(t)
	v := verifier(t, publicKeyFile(t, signing))

	justExpired := valid()
	justExpired.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-5 * time.Second))
	if _, principal := call(t, v, "Bearer "+signed(t, signing, justExpired)); principal == nil {
		t.Error("a token five seconds past expiry was rejected; issuer and gateway clocks are never identical")
	}

	wellExpired := valid()
	wellExpired.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-5 * time.Minute))
	if _, principal := call(t, v, "Bearer "+signed(t, signing, wellExpired)); principal != nil {
		t.Error("a token five minutes past expiry was accepted; the leeway is not a grace period")
	}
}
