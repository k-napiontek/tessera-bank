// Package correlation gives every request the identifier that ties it to every line it produces,
// in every tier of the estate.
//
// The rule is the ledger's rule, deliberately: a canonical UUID sent by the caller is honoured, and
// anything else is discarded and replaced. Two tiers that disagree about which ids are acceptable
// produce two different ids for one request, which correlates less than either rule alone would.
//
// The strictness has a reason beyond tidiness. Whatever arrives in this header reaches log lines
// and error documents, so honouring arbitrary text would let a caller choose the contents of the
// bank's logs - including a line that looks like a log entry of its own.
package correlation

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
)

// Header is the header the OpenAPI document declares on every operation.
const Header = "X-Correlation-Id"

// LogKey is the field name the id appears under in every structured log line, matching the ledger's
// MDC key so one query reads both tiers.
const LogKey = "correlation_id"

type contextKey struct{}

// Middleware resolves the id, puts it on the request context, and echoes it on the response before
// the handler can write anything.
func Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := resolve(r.Header.Get(Header))

		// Set before the handler runs. A header set afterwards is dropped once the status has been
		// written, and the response that would lose it is the error response somebody is tracing.
		w.Header().Set(Header, id)

		// The downstream request is built from this one, so overwriting the inbound header here is
		// what makes the ledger see the resolved id rather than the caller's rejected text.
		r.Header.Set(Header, id)

		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), contextKey{}, id)))
	})
}

// FromContext returns the id of the request being served, or an empty string outside the middleware.
func FromContext(ctx context.Context) string {
	id, _ := ctx.Value(contextKey{}).(string)
	return id
}

func resolve(supplied string) string {
	if IsCanonicalUUID(supplied) {
		return supplied
	}
	return New()
}

// New returns a version 4 UUID in canonical form.
func New() string {
	var bytes [16]byte
	// crypto/rand.Read is documented never to return a short read, and a process that cannot read
	// randomness cannot safely serve anything - so a failure here is fatal rather than papered over
	// with a predictable id.
	if _, err := rand.Read(bytes[:]); err != nil {
		panic("gateway: no source of randomness: " + err.Error())
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
	bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 1, RFC 4122

	encoded := make([]byte, 36)
	hex.Encode(encoded[0:8], bytes[0:4])
	encoded[8] = '-'
	hex.Encode(encoded[9:13], bytes[4:6])
	encoded[13] = '-'
	hex.Encode(encoded[14:18], bytes[6:8])
	encoded[18] = '-'
	hex.Encode(encoded[19:23], bytes[8:10])
	encoded[23] = '-'
	hex.Encode(encoded[24:36], bytes[10:16])
	return string(encoded)
}

// IsCanonicalUUID reports whether value is exactly 8-4-4-4-12 lowercase hexadecimal.
//
// Only the canonical form passes. The ledger reaches the same answer by round-tripping through
// java.util.UUID and comparing, which rejects the abbreviated forms its parser otherwise accepts
// and rejects upper case because its own toString is lower case.
func IsCanonicalUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i := 0; i < 36; i++ {
		c := value[i]
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			isDigit := c >= '0' && c <= '9'
			isLowerHex := c >= 'a' && c <= 'f'
			if !isDigit && !isLowerHex {
				return false
			}
		}
	}
	return true
}
