package config_test

import (
	"strings"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/config"
)

// complete is the smallest environment that boots: the four settings that have no default,
// because a wrong guess at any of them is a security decision taken by accident.
func complete() map[string]string {
	return map[string]string{
		"TB_GATEWAY_LEDGER_URL":   "http://ledger-core.internal:8080/v1",
		"TB_GATEWAY_JWT_ISSUER":   "https://issuer.tesserabank.example",
		"TB_GATEWAY_JWT_AUDIENCE": "tessera-bank-ledger",
		"TB_GATEWAY_JWT_KEYS":     "/etc/tessera/jwt-keys.pem",
	}
}

func lookup(env map[string]string) func(string) (string, bool) {
	return func(name string) (string, bool) {
		value, ok := env[name]
		return value, ok
	}
}

func TestLoadAppliesTheDeclaredDefaults(t *testing.T) {
	cfg, err := config.Load(lookup(complete()))
	if err != nil {
		t.Fatalf("a complete environment must load: %v", err)
	}

	if cfg.ListenAddress != ":8080" {
		t.Errorf("listen address = %q, want %q", cfg.ListenAddress, ":8080")
	}
	if cfg.DownstreamTimeout != 5*time.Second {
		t.Errorf("downstream timeout = %v, want 5s", cfg.DownstreamTimeout)
	}
	if cfg.MaxRequestBytes != 65536 {
		t.Errorf("max request bytes = %d, want 65536", cfg.MaxRequestBytes)
	}
	if cfg.RateBurst != 20 {
		t.Errorf("rate burst = %d, want 20", cfg.RateBurst)
	}
	if cfg.LedgerURL.Host != "ledger-core.internal:8080" {
		t.Errorf("ledger host = %q, want ledger-core.internal:8080", cfg.LedgerURL.Host)
	}
}

func TestLoadReportsEveryMissingSettingAtOnce(t *testing.T) {
	_, err := config.Load(lookup(map[string]string{}))
	if err == nil {
		t.Fatal("an empty environment must not boot")
	}

	// One restart per missing variable is how a first-error-wins loader is discovered, at three in
	// the morning, one restart at a time.
	for _, name := range []string{
		"TB_GATEWAY_LEDGER_URL",
		"TB_GATEWAY_JWT_ISSUER",
		"TB_GATEWAY_JWT_AUDIENCE",
		"TB_GATEWAY_JWT_KEYS",
	} {
		if !strings.Contains(err.Error(), name) {
			t.Errorf("error does not name %s: %v", name, err)
		}
	}
}

func TestLoadRejectsAPresentButEmptyRequiredSetting(t *testing.T) {
	env := complete()
	env["TB_GATEWAY_JWT_AUDIENCE"] = ""

	if _, err := config.Load(lookup(env)); err == nil {
		t.Fatal("an empty audience must be refused, not treated as absent and defaulted")
	}
}

func TestLoadRejectsAnInvalidDuration(t *testing.T) {
	env := complete()
	env["TB_GATEWAY_DOWNSTREAM_TIMEOUT"] = "5 seconds"

	_, err := config.Load(lookup(env))
	if err == nil {
		t.Fatal("an unparseable duration must fail the boot, not fall back to the default")
	}
	if !strings.Contains(err.Error(), "TB_GATEWAY_DOWNSTREAM_TIMEOUT") {
		t.Errorf("error does not name the offending variable: %v", err)
	}
}

func TestLoadRejectsALedgerURLThatIsNotAbsoluteHTTP(t *testing.T) {
	for _, raw := range []string{"ledger-core.internal", "/v1", "ftp://ledger-core.internal"} {
		env := complete()
		env["TB_GATEWAY_LEDGER_URL"] = raw

		if _, err := config.Load(lookup(env)); err == nil {
			t.Errorf("ledger URL %q must be refused", raw)
		}
	}
}

func TestLoadRejectsNonPositiveLimits(t *testing.T) {
	cases := map[string]string{
		"TB_GATEWAY_MAX_REQUEST_BYTES":  "0",
		"TB_GATEWAY_MAX_RESPONSE_BYTES": "-1",
		"TB_GATEWAY_RATE_PER_SECOND":    "0",
		"TB_GATEWAY_RATE_BURST":         "0",
		"TB_GATEWAY_DOWNSTREAM_TIMEOUT": "0s",
	}
	for name, value := range cases {
		env := complete()
		env[name] = value

		if _, err := config.Load(lookup(env)); err == nil {
			t.Errorf("%s=%s must be refused: a zero limit is a limit nobody meant to set", name, value)
		}
	}
}

func TestLoadRejectsARetryBudgetThatIsNotBounded(t *testing.T) {
	env := complete()
	env["TB_GATEWAY_DOWNSTREAM_ATTEMPTS"] = "0"

	if _, err := config.Load(lookup(env)); err == nil {
		t.Fatal("zero attempts means no request is ever sent; it must be refused")
	}

	env["TB_GATEWAY_DOWNSTREAM_ATTEMPTS"] = "9"
	if _, err := config.Load(lookup(env)); err == nil {
		t.Fatal("an unbounded-looking retry budget must be refused: the edge amplifies, it does not absorb")
	}
}

func TestLoadAcceptsAnOverrideForEverySetting(t *testing.T) {
	env := complete()
	env["TB_GATEWAY_LISTEN"] = "127.0.0.1:9443"
	env["TB_GATEWAY_DOWNSTREAM_TIMEOUT"] = "1500ms"
	env["TB_GATEWAY_DOWNSTREAM_ATTEMPTS"] = "3"
	env["TB_GATEWAY_SHUTDOWN_GRACE"] = "30s"
	env["TB_GATEWAY_MAX_REQUEST_BYTES"] = "4096"
	env["TB_GATEWAY_MAX_RESPONSE_BYTES"] = "8192"
	env["TB_GATEWAY_RATE_PER_SECOND"] = "2.5"
	env["TB_GATEWAY_RATE_BURST"] = "5"
	env["TB_GATEWAY_LOG_LEVEL"] = "debug"

	cfg, err := config.Load(lookup(env))
	if err != nil {
		t.Fatalf("a fully specified environment must load: %v", err)
	}

	if cfg.ListenAddress != "127.0.0.1:9443" {
		t.Errorf("listen address = %q", cfg.ListenAddress)
	}
	if cfg.DownstreamTimeout != 1500*time.Millisecond {
		t.Errorf("downstream timeout = %v", cfg.DownstreamTimeout)
	}
	if cfg.DownstreamAttempts != 3 {
		t.Errorf("downstream attempts = %d", cfg.DownstreamAttempts)
	}
	if cfg.ShutdownGrace != 30*time.Second {
		t.Errorf("shutdown grace = %v", cfg.ShutdownGrace)
	}
	if cfg.MaxResponseBytes != 8192 {
		t.Errorf("max response bytes = %d", cfg.MaxResponseBytes)
	}
	if cfg.RatePerSecond != 2.5 {
		t.Errorf("rate per second = %v", cfg.RatePerSecond)
	}
	if cfg.LogLevel != "debug" {
		t.Errorf("log level = %q", cfg.LogLevel)
	}
}

func TestLoadRejectsAnUnknownLogLevel(t *testing.T) {
	env := complete()
	env["TB_GATEWAY_LOG_LEVEL"] = "verbose"

	if _, err := config.Load(lookup(env)); err == nil {
		t.Fatal("an unknown log level must fail the boot rather than silently logging at info")
	}
}
