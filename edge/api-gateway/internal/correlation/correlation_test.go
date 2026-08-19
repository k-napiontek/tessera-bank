package correlation_test

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
)

// serve runs the middleware over a handler that reports the id it was given.
func serve(t *testing.T, supplied string) (*httptest.ResponseRecorder, string) {
	t.Helper()

	var seen string
	handler := correlation.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		seen = correlation.FromContext(r.Context())
	}))

	request := httptest.NewRequest(http.MethodGet, "/accounts/ACC-1", nil)
	if supplied != "" {
		request.Header.Set(correlation.Header, supplied)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	return recorder, seen
}

func TestAValidIdIsPropagatedUnchanged(t *testing.T) {
	const supplied = "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e"

	response, seen := serve(t, supplied)

	// An id minted afresh at every tier correlates nothing. Honouring the caller's is the whole
	// mechanism.
	if seen != supplied {
		t.Errorf("handler saw %q, want the supplied id %q", seen, supplied)
	}
	if got := response.Header().Get(correlation.Header); got != supplied {
		t.Errorf("response header = %q, want %q", got, supplied)
	}
}

func TestAnAbsentIdIsGenerated(t *testing.T) {
	response, seen := serve(t, "")

	if !correlation.IsCanonicalUUID(seen) {
		t.Errorf("generated id %q is not a canonical UUID", seen)
	}
	if got := response.Header().Get(correlation.Header); got != seen {
		t.Errorf("response header = %q, want the generated id %q", got, seen)
	}
}

func TestGeneratedIdsDiffer(t *testing.T) {
	_, first := serve(t, "")
	_, second := serve(t, "")

	if first == second {
		t.Errorf("two requests were given the same id %q", first)
	}
}

func TestAnythingThatIsNotACanonicalUUIDIsReplaced(t *testing.T) {
	// The rule matches services/ledger-api CorrelationId.resolve exactly, including its refusal of
	// the lenient forms Java's UUID.fromString accepts. Two tiers that disagree about which ids are
	// acceptable produce two different ids for one request, which is worse than either rule alone.
	//
	// This is not pedantry about a format: whatever arrives here reaches log lines and a Problem
	// document, so honouring arbitrary text would let a caller choose this service's log contents.
	for _, supplied := range []string{
		"not-a-uuid",
		"1-1-1-1-1",
		"8F14E45F-CE0A-4C1E-9F2B-3D4A5B6C7D8E",
		"8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8",
		"8f14e45fce0a4c1e9f2b3d4a5b6c7d8e",
		"{8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e}",
		"8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e\nlevel=error msg=\"transfer failed\"",
		"  8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e  ",
	} {
		response, seen := serve(t, supplied)

		if seen == supplied {
			t.Errorf("%q was honoured, want a replacement", supplied)
		}
		if !correlation.IsCanonicalUUID(seen) {
			t.Errorf("replacement for %q is %q, which is not a canonical UUID", supplied, seen)
		}
		if got := response.Header().Get(correlation.Header); got != seen {
			t.Errorf("response header = %q, want %q", got, seen)
		}
	}
}

func TestTheHeaderIsSetBeforeTheHandlerWrites(t *testing.T) {
	handler := correlation.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))

	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/", nil))

	// A header written after WriteHeader is silently dropped, so an error response - exactly the
	// one an operator will be tracing - would be the response that lost its id.
	if !correlation.IsCanonicalUUID(recorder.Header().Get(correlation.Header)) {
		t.Error("a 500 response carries no correlation id")
	}
}

func TestFromContextIsEmptyWhenTheMiddlewareDidNotRun(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/", nil)

	if got := correlation.FromContext(request.Context()); got != "" {
		t.Errorf("id outside the middleware = %q, want empty", got)
	}
}

func TestNewIsCanonical(t *testing.T) {
	id := correlation.New()

	if !correlation.IsCanonicalUUID(id) {
		t.Fatalf("New produced %q, which is not a canonical UUID", id)
	}
	// Version 4, variant 1 - the random UUID. A generator that gets the bits wrong still looks like
	// a UUID to every eye that reads it.
	if id[14] != '4' {
		t.Errorf("version nibble = %q, want 4 in %q", id[14], id)
	}
	if !containsAny("89ab", id[19]) {
		t.Errorf("variant nibble = %q, want one of 8, 9, a, b in %q", id[19], id)
	}
}

func containsAny(set string, b byte) bool {
	for i := 0; i < len(set); i++ {
		if set[i] == b {
			return true
		}
	}
	return false
}
