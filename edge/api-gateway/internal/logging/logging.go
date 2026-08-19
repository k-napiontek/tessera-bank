// Package logging builds the one logger the gateway uses.
//
// JSON, on stdout, always. The estate's log pipeline reads structured lines from every tier, and a
// human-friendly format at the edge is a format nothing downstream can query - which is discovered
// during the incident where the query is needed.
package logging

import (
	"io"
	"log/slog"
)

// New builds a JSON logger at the given level. The level is validated by the config package, so an
// unrecognised one here is a programming error rather than an operator's typo; it falls back to
// info rather than panicking a process that is otherwise ready to serve.
func New(level string, out io.Writer) *slog.Logger {
	return slog.New(slog.NewJSONHandler(out, &slog.HandlerOptions{Level: Level(level)}))
}

// Level maps the configured name onto a slog level.
func Level(name string) slog.Level {
	switch name {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
