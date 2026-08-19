// Command gateway is the edge of Tessera Bank: the one place a customer request is authenticated,
// rate limited, given a correlation id, and forwarded to services/ledger-core.
//
// It holds no business logic. If this program ever needs to understand what a transfer is, the
// design has gone wrong.
package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/config"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/health"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/server"
)

// readinessWindow collapses readiness polls arriving within it into one dial at the ledger.
const readinessWindow = 2 * time.Second

func main() {
	if err := run(); err != nil {
		// Configuration failures happen before there is a logger, and an operator reading a crash
		// loop wants the reason in plain text, not wrapped in JSON.
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load(os.LookupEnv)
	if err != nil {
		return err
	}

	log := logging.New(cfg.LogLevel, os.Stdout)

	// SIGTERM is what an orchestrator sends before it removes the process; SIGINT is what a
	// developer sends. Both mean "stop accepting, finish what you hold".
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	readiness := health.Cached(health.DialProbe(cfg.LedgerURL, cfg.DownstreamTimeout), readinessWindow)

	mux := http.NewServeMux()
	mux.Handle("/healthz", health.Handler(readiness))
	mux.Handle("/readyz", health.Handler(readiness))

	srv, err := server.Listen(server.Settings{
		ListenAddress:     cfg.ListenAddress,
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		ShutdownGrace:     cfg.ShutdownGrace,
	}, mux)
	if err != nil {
		return fmt.Errorf("cannot bind %s: %w", cfg.ListenAddress, err)
	}

	// The resolved settings are logged at boot so an operator can see what the process actually
	// runs on rather than what the deployment intended. No credential is among them: the gateway
	// holds a path to public keys, never a secret.
	log.Info("gateway starting",
		"address", srv.Address(),
		"ledger", cfg.LedgerURL.String(),
		"downstream_timeout", cfg.DownstreamTimeout.String(),
		"downstream_attempts", cfg.DownstreamAttempts,
		"max_request_bytes", cfg.MaxRequestBytes,
		"rate_per_second", cfg.RatePerSecond,
		"rate_burst", cfg.RateBurst,
		"jwt_issuer", cfg.JWTIssuer,
		"jwt_audience", cfg.JWTAudience,
	)

	if err := srv.Serve(ctx); err != nil {
		log.Error("gateway stopped with an error", "error", err.Error())
		return err
	}
	log.Info("gateway stopped cleanly")
	return nil
}
