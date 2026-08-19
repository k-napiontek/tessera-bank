// Command gateway is the edge of Tessera Bank: the one place a customer request is authenticated,
// rate limited, given a correlation id, and forwarded to services/ledger-core.
//
// It holds no business logic. If this program ever needs to understand what a transfer is, the
// design has gone wrong.
//
// Two listeners are served. The customer-facing one serves the operations the OpenAPI contract
// declares and nothing else; the administrative one serves health probes and metrics, on a port
// that is not published. Serving both on one port is exactly the arrangement in the ledger that
// this gateway exists to keep off the internet.
package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/config"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/gateway"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/health"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/logging"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/metrics"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/ratelimit"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/server"
)

// readinessWindow collapses readiness polls arriving within it into one dial at the ledger.
const readinessWindow = 2 * time.Second

func main() {
	if err := run(); err != nil {
		// Configuration failures happen before there is a logger, and an operator reading a crash
		// loop wants the reason in plain text rather than wrapped in JSON.
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

	verifier, err := auth.NewVerifier(auth.Settings{
		Issuer:   cfg.JWTIssuer,
		Audience: cfg.JWTAudience,
		KeysPath: cfg.JWTKeysPath,
	})
	if err != nil {
		// A gateway that cannot verify a signature must not serve: it would have to either refuse
		// everything, which is an outage nobody can diagnose, or trust everything, which is worse.
		return fmt.Errorf("cannot load the JWT keys: %w", err)
	}

	deps := gateway.Dependencies{
		Config:   cfg,
		Verifier: verifier,
		Limiter: ratelimit.New(ratelimit.Settings{
			PerSecond: cfg.RatePerSecond,
			Burst:     cfg.RateBurst,
		}),
		Metrics: metrics.New(),
		Log:     log,
	}
	readiness := health.Cached(health.DialProbe(cfg.LedgerURL, cfg.DownstreamTimeout), readinessWindow)

	settings := server.Settings{
		ListenAddress:     cfg.ListenAddress,
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		ShutdownGrace:     cfg.ShutdownGrace,
	}
	public, err := server.Listen(settings, gateway.Public(deps))
	if err != nil {
		return fmt.Errorf("cannot bind the customer listener on %s: %w", cfg.ListenAddress, err)
	}
	settings.ListenAddress = cfg.AdminAddress
	admin, err := server.Listen(settings, gateway.Admin(deps, readiness))
	if err != nil {
		return fmt.Errorf("cannot bind the administrative listener on %s: %w", cfg.AdminAddress, err)
	}

	// SIGTERM is what an orchestrator sends before it removes the process; SIGINT is what a
	// developer sends. Both mean "stop accepting, finish what you hold".
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	// The resolved settings are logged at boot so an operator sees what the process actually runs
	// on rather than what the deployment intended. No credential is among them: the gateway holds a
	// path to public keys, never a secret.
	log.Info("gateway starting",
		"address", public.Address(),
		"admin_address", admin.Address(),
		"ledger", cfg.LedgerURL.String(),
		"downstream_timeout", cfg.DownstreamTimeout.String(),
		"downstream_attempts", cfg.DownstreamAttempts,
		"max_request_bytes", cfg.MaxRequestBytes,
		"rate_per_second", cfg.RatePerSecond,
		"rate_burst", cfg.RateBurst,
		"jwt_issuer", cfg.JWTIssuer,
		"jwt_audience", cfg.JWTAudience,
	)

	var (
		wait   sync.WaitGroup
		mu     sync.Mutex
		failed error
	)
	for name, srv := range map[string]*server.Server{"public": public, "admin": admin} {
		name, srv := name, srv
		wait.Add(1)
		go func() {
			defer wait.Done()
			if err := srv.Serve(ctx); err != nil {
				log.Error("listener stopped with an error", "listener", name, "error", err.Error())
				mu.Lock()
				if failed == nil {
					failed = err
				}
				mu.Unlock()
				// One listener failing takes the whole process down. A gateway serving customers
				// with no metrics, or metrics with no customers, is a half-running process that an
				// orchestrator has no way to notice.
				stop()
			}
		}()
	}
	wait.Wait()

	if failed != nil {
		return failed
	}
	log.Info("gateway stopped cleanly")
	return nil
}
