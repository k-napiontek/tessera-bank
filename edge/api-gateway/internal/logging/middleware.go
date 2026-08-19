package logging

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
)

type contextKey struct{}

// Middleware writes one access line per request and puts a logger carrying the request's
// correlation id on the context, so a handler that logs cannot forget to include it.
//
// What is deliberately absent from every line matters as much as what is present:
//
//   - No Authorization header and no token. A credential in a log store is replayable for as long
//     as it lives, and a log store is read by far more people than the ledger is.
//   - No client address. An IP address identifies a person under GDPR, and this repository holds no
//     personal data anywhere.
//   - No query string and no body. Both are caller-controlled, and callers do put credentials in
//     query strings.
//
// The path is logged, because an account reference is exactly what this estate is supposed to log.
func Middleware(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			started := time.Now()

			requestLog := log.With(correlation.LogKey, correlation.FromContext(r.Context()))
			recorder := &recordingWriter{ResponseWriter: w, status: http.StatusOK}

			next.ServeHTTP(recorder, r.WithContext(context.WithValue(r.Context(), contextKey{}, requestLog)))

			// r.URL.Path, not r.URL.String or RequestURI: the second and third carry the query.
			requestLog.Info("request served",
				"method", r.Method,
				"path", r.URL.Path,
				"status", recorder.status,
				"bytes", recorder.written,
				"duration_ms", time.Since(started).Milliseconds(),
			)
		})
	}
}

// FromContext returns the logger for the request being served. It is never nil: a handler running
// outside the chain logs nowhere rather than panicking in production.
func FromContext(ctx context.Context) *slog.Logger {
	if log, ok := ctx.Value(contextKey{}).(*slog.Logger); ok {
		return log
	}
	return slog.New(slog.NewJSONHandler(io.Discard, nil))
}

// recordingWriter remembers the status and size the handler produced, which is the only way an
// access line can report them.
type recordingWriter struct {
	http.ResponseWriter
	status  int
	written int
	wrote   bool
}

func (w *recordingWriter) WriteHeader(status int) {
	if w.wrote {
		return
	}
	w.status = status
	w.wrote = true
	w.ResponseWriter.WriteHeader(status)
}

func (w *recordingWriter) Write(b []byte) (int, error) {
	if !w.wrote {
		// net/http implies 200 on the first write. Recording it here keeps the access line honest
		// for a handler that never calls WriteHeader.
		w.WriteHeader(http.StatusOK)
	}
	n, err := w.ResponseWriter.Write(b)
	w.written += n
	return n, err
}

// Unwrap lets net/http reach the underlying writer, so http.ResponseController keeps working for
// anything that needs flushing or a deadline.
func (w *recordingWriter) Unwrap() http.ResponseWriter {
	return w.ResponseWriter
}
