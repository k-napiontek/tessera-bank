// Package ratelimit caps how fast one caller may use one route.
//
// The state lives in this process and nowhere else. That is a decision with a consequence worth
// stating plainly rather than discovering: n instances of the gateway permit n times the configured
// rate, because each holds its own buckets. A shared counter would need a store this repository is
// deliberately not allowed to deploy (ADR 0001), and a limiter that lies about being global is
// worse than one that is honestly local - the first is relied upon, the second is understood.
//
// The bucket is keyed by subject and route class, never by address. Two customers behind one bank
// branch share an address and must not share a budget; and an address is personal data under GDPR,
// which this repository holds nowhere.
package ratelimit

import (
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/problem"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

// defaultIdleTimeout is how long a bucket outlives its last use. Without an expiry the map grows
// with every distinct subject the gateway ever sees, which is a leak a caller can accelerate.
const defaultIdleTimeout = 10 * time.Minute

// Settings configure a Limiter.
type Settings struct {
	// PerSecond is the sustained rate one subject may use one route class at.
	PerSecond float64
	// Burst is how many requests may arrive at once before the rate applies.
	Burst int
	// Now is the clock. Nil means time.Now; a test winds its own.
	Now func() time.Time
	// IdleTimeout is how long an unused bucket is kept. Zero means the default.
	IdleTimeout time.Duration
}

// Limiter holds one token bucket per subject and route class.
type Limiter struct {
	perSecond   float64
	burst       float64
	now         func() time.Time
	idleTimeout time.Duration

	mu        sync.Mutex
	buckets   map[string]*bucket
	lastSweep time.Time
}

type bucket struct {
	tokens   float64
	lastFill time.Time
}

// New builds a limiter.
func New(settings Settings) *Limiter {
	now := settings.Now
	if now == nil {
		now = time.Now
	}
	idle := settings.IdleTimeout
	if idle <= 0 {
		idle = defaultIdleTimeout
	}
	return &Limiter{
		perSecond:   settings.PerSecond,
		burst:       float64(settings.Burst),
		now:         now,
		idleTimeout: idle,
		buckets:     make(map[string]*bucket),
		lastSweep:   now(),
	}
}

// Allow takes a token for the key if one is available. When none is, it reports how long until the
// next one arrives.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	if now.Sub(l.lastSweep) >= l.idleTimeout {
		l.sweep(now)
	}

	b, known := l.buckets[key]
	if !known {
		b = &bucket{tokens: l.burst, lastFill: now}
		l.buckets[key] = b
	}

	// Refill by elapsed time, capped at the burst. An idle hour must not buy an hour of requests to
	// spend in one instant - the burst is the cap on what a single caller can do at once.
	elapsed := now.Sub(b.lastFill).Seconds()
	if elapsed > 0 {
		b.tokens = math.Min(l.burst, b.tokens+elapsed*l.perSecond)
		b.lastFill = now
	}

	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}

	needed := (1 - b.tokens) / l.perSecond
	return false, time.Duration(needed * float64(time.Second))
}

// Tracked is how many buckets are held. It exists for the test that proves they are forgotten, and
// for the metric that would notice if they were not.
func (l *Limiter) Tracked() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}

// sweep drops buckets untouched for longer than the idle timeout. The caller holds the lock.
//
// Sweeping is driven by the clock rather than by a request count: a limiter that sweeps every n
// requests never sweeps at all once the traffic that filled its buckets has gone, which is exactly
// the state an abusive burst leaves behind.
func (l *Limiter) sweep(now time.Time) {
	l.lastSweep = now
	for key, b := range l.buckets {
		if now.Sub(b.lastFill) > l.idleTimeout {
			delete(l.buckets, key)
		}
	}
}

// Middleware refuses a request that is over its limit.
func Middleware(limiter *Limiter) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			principal, authenticated := auth.PrincipalFrom(r.Context())
			if !authenticated {
				// Anything else would put every unauthenticated request in one bucket, so a single
				// caller could deny the route to everybody. The chain authenticates first.
				problem.Write(w, r, http.StatusUnauthorized, problem.Unauthenticated,
					"A valid bearer token is required.")
				return
			}
			class := "unrouted"
			if route, matched := routing.FromContext(r.Context()); matched {
				class = route.Class
			}

			allowed, wait := limiter.Allow(principal.Subject + "\x00" + class)
			if allowed {
				next.ServeHTTP(w, r)
				return
			}

			// RFC 9110 permits a delta in seconds, rounded up: a Retry-After of 0 invites the
			// immediate retry the limit exists to prevent.
			seconds := int(math.Ceil(wait.Seconds()))
			if seconds < 1 {
				seconds = 1
			}
			w.Header().Set("Retry-After", strconv.Itoa(seconds))
			problem.Write(w, r, http.StatusTooManyRequests, problem.RateLimited,
				"Too many requests. Retry after the interval in the Retry-After header.")
		})
	}
}
