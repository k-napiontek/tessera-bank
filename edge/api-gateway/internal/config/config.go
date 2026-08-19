// Package config reads the gateway's settings from the environment and refuses to start on a
// setting it does not understand.
//
// Two rules govern everything here.
//
// A setting that decides who is let in - the ledger it fronts, the issuer and audience it trusts,
// the keys it verifies with - has no default. A default there is a security decision taken by
// whoever forgot to set the variable, and the failure is silent: the gateway comes up, serves
// traffic, and trusts the wrong signer.
//
// A setting that is present but unparseable is always an error, never a fall back to the default.
// An operator who writes TB_GATEWAY_DOWNSTREAM_TIMEOUT=5 seconds meant something by it, and a
// gateway that quietly runs on 5s instead has taken their intent and discarded it.
//
// Every problem in the environment is reported at once. A loader that stops at the first one is
// discovered during an incident, one restart per variable.
package config

import (
	"fmt"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Config is the whole of the gateway's configuration. Nothing here is mutated after Load returns.
type Config struct {
	// ListenAddress is the address the customer-facing API is served on.
	ListenAddress string
	// AdminAddress is the address health probes and metrics are served on. It is a second port so
	// that the operational surface is not published with the customer one - which is the arrangement
	// this gateway exists to stop the ledger from exposing.
	AdminAddress string
	// LogLevel is one of debug, info, warn, error.
	LogLevel string

	// LedgerURL is the base URL of services/ledger-api, including any path prefix such as /v1.
	LedgerURL *url.URL

	// DownstreamTimeout bounds a single attempt at the ledger, connection through response body.
	DownstreamTimeout time.Duration
	// DownstreamAttempts is the total number of attempts, so 1 means no retry at all.
	DownstreamAttempts int
	// ShutdownGrace is how long in-flight requests have to finish after SIGTERM.
	ShutdownGrace time.Duration
	// ReadHeaderTimeout bounds a client dawdling over its request headers.
	ReadHeaderTimeout time.Duration

	// MaxRequestBytes caps a request body the gateway will forward.
	MaxRequestBytes int64
	// MaxResponseBytes caps a response body the gateway will relay back.
	MaxResponseBytes int64

	// RatePerSecond is the sustained request rate allowed per subject and route class.
	RatePerSecond float64
	// RateBurst is how far above that rate a caller may burst.
	RateBurst int

	// JWTIssuer is the only iss claim the gateway accepts.
	JWTIssuer string
	// JWTAudience is the aud claim the gateway requires.
	JWTAudience string
	// JWTKeysPath is a PEM file of public keys the gateway verifies signatures against.
	JWTKeysPath string
}

// Lookup reads one environment variable, reporting whether it was set at all. os.LookupEnv
// satisfies it; a map satisfies it in a test, which is why Load takes it rather than reading the
// process environment itself.
type Lookup func(name string) (string, bool)

// Error carries every problem found in the environment, sorted so the message is stable.
type Error struct {
	Problems []string
}

func (e *Error) Error() string {
	return fmt.Sprintf("the gateway configuration is not usable:\n  - %s", strings.Join(e.Problems, "\n  - "))
}

// The defaults for every setting that has one. They are stated here, in one place, rather than
// scattered through the calls below, because an operator's first question is what the value is
// when they set nothing.
const (
	defaultListenAddress      = ":8080"
	defaultAdminAddress       = ":9090"
	defaultLogLevel           = "info"
	defaultDownstreamTimeout  = 5 * time.Second
	defaultDownstreamAttempts = 2
	defaultShutdownGrace      = 15 * time.Second
	defaultReadHeaderTimeout  = 5 * time.Second
	defaultMaxRequestBytes    = 64 * 1024
	defaultMaxResponseBytes   = 1024 * 1024
	defaultRatePerSecond      = 10.0
	defaultRateBurst          = 20

	// maxDownstreamAttempts keeps the retry budget bounded. An edge that retries freely turns one
	// slow ledger into several times the load on it, which is how a slow dependency becomes an
	// outage rather than a delay.
	maxDownstreamAttempts = 3
)

// Load reads the configuration through lookup and validates all of it.
func Load(lookup Lookup) (Config, error) {
	l := &loader{lookup: lookup}

	cfg := Config{
		ListenAddress: l.text("TB_GATEWAY_LISTEN", defaultListenAddress),
		AdminAddress:  l.text("TB_GATEWAY_ADMIN_LISTEN", defaultAdminAddress),
		LogLevel:      l.logLevel("TB_GATEWAY_LOG_LEVEL", defaultLogLevel),

		LedgerURL: l.httpURL("TB_GATEWAY_LEDGER_URL"),

		DownstreamTimeout:  l.duration("TB_GATEWAY_DOWNSTREAM_TIMEOUT", defaultDownstreamTimeout),
		DownstreamAttempts: l.boundedInt("TB_GATEWAY_DOWNSTREAM_ATTEMPTS", defaultDownstreamAttempts, 1, maxDownstreamAttempts),
		ShutdownGrace:      l.duration("TB_GATEWAY_SHUTDOWN_GRACE", defaultShutdownGrace),
		ReadHeaderTimeout:  l.duration("TB_GATEWAY_READ_HEADER_TIMEOUT", defaultReadHeaderTimeout),

		MaxRequestBytes:  l.bytes("TB_GATEWAY_MAX_REQUEST_BYTES", defaultMaxRequestBytes),
		MaxResponseBytes: l.bytes("TB_GATEWAY_MAX_RESPONSE_BYTES", defaultMaxResponseBytes),

		RatePerSecond: l.rate("TB_GATEWAY_RATE_PER_SECOND", defaultRatePerSecond),
		RateBurst:     l.boundedInt("TB_GATEWAY_RATE_BURST", defaultRateBurst, 1, 1_000_000),

		JWTIssuer:   l.required("TB_GATEWAY_JWT_ISSUER"),
		JWTAudience: l.required("TB_GATEWAY_JWT_AUDIENCE"),
		JWTKeysPath: l.required("TB_GATEWAY_JWT_KEYS"),
	}

	if len(l.problems) > 0 {
		sort.Strings(l.problems)
		return Config{}, &Error{Problems: l.problems}
	}
	return cfg, nil
}

type loader struct {
	lookup   Lookup
	problems []string
}

func (l *loader) fail(name, format string, args ...any) {
	l.problems = append(l.problems, fmt.Sprintf("%s: %s", name, fmt.Sprintf(format, args...)))
}

// raw returns the value and whether it was supplied. A variable set to the empty string counts as
// supplied: the operator wrote it, so it is validated rather than defaulted away.
func (l *loader) raw(name string) (string, bool) {
	value, ok := l.lookup(name)
	return strings.TrimSpace(value), ok
}

func (l *loader) required(name string) string {
	value, ok := l.raw(name)
	if !ok {
		l.fail(name, "is required and was not set")
		return ""
	}
	if value == "" {
		l.fail(name, "is required and was set to an empty value")
		return ""
	}
	return value
}

func (l *loader) text(name, fallback string) string {
	value, ok := l.raw(name)
	if !ok {
		return fallback
	}
	if value == "" {
		l.fail(name, "was set to an empty value")
		return fallback
	}
	return value
}

func (l *loader) logLevel(name, fallback string) string {
	value := l.text(name, fallback)
	switch value {
	case "debug", "info", "warn", "error":
		return value
	default:
		l.fail(name, "is %q, which is not one of debug, info, warn, error", value)
		return fallback
	}
}

func (l *loader) httpURL(name string) *url.URL {
	value := l.required(name)
	if value == "" {
		return &url.URL{}
	}
	parsed, err := url.Parse(value)
	if err != nil {
		l.fail(name, "is not a URL: %v", err)
		return &url.URL{}
	}
	// Relative or non-HTTP is refused rather than coerced. A gateway that "helpfully" prefixes
	// http:// onto a bare host is a gateway that will one day send a customer's token in clear.
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		l.fail(name, "must be an absolute http or https URL, got %q", value)
		return &url.URL{}
	}
	if parsed.Host == "" {
		l.fail(name, "has no host: %q", value)
		return &url.URL{}
	}
	return parsed
}

func (l *loader) duration(name string, fallback time.Duration) time.Duration {
	value, ok := l.raw(name)
	if !ok {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		l.fail(name, "is not a duration such as 500ms or 5s: %q", value)
		return fallback
	}
	if parsed <= 0 {
		l.fail(name, "must be positive, got %v", parsed)
		return fallback
	}
	return parsed
}

func (l *loader) bytes(name string, fallback int64) int64 {
	value, ok := l.raw(name)
	if !ok {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		l.fail(name, "is not a whole number of bytes: %q", value)
		return fallback
	}
	if parsed <= 0 {
		l.fail(name, "must be positive, got %d - a zero limit rejects every request", parsed)
		return fallback
	}
	return parsed
}

func (l *loader) rate(name string, fallback float64) float64 {
	value, ok := l.raw(name)
	if !ok {
		return fallback
	}
	parsed, err := strconv.ParseFloat(value, 64)
	if err != nil {
		l.fail(name, "is not a number of requests per second: %q", value)
		return fallback
	}
	if parsed <= 0 {
		l.fail(name, "must be positive, got %v - a zero rate rejects every request", parsed)
		return fallback
	}
	return parsed
}

func (l *loader) boundedInt(name string, fallback, low, high int) int {
	value, ok := l.raw(name)
	if !ok {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		l.fail(name, "is not a whole number: %q", value)
		return fallback
	}
	if parsed < low || parsed > high {
		l.fail(name, "must be between %d and %d, got %d", low, high, parsed)
		return fallback
	}
	return parsed
}
