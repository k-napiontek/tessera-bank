package migration

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// The lock the migration takes, read from pg_locks while it is held.
//
// WP-24b asks for the lock **and** for what the customer experienced while it was held, and those
// are answered in two different places on purpose: the lock here, because only the database knows
// what mode was taken on what relation; the latency from the gateway's own scrapes, because what an
// operator needs to know is what the customer experienced rather than what the database was doing.
// WP-23 put the ledger's metrics where the ledger could measure them for the same reason.
//
// The sampler is the only honest way to read a lock. It exists for the length of the migration and
// then stops: a lock is held for a window and a query run afterwards finds nothing, which is exactly
// how a runbook comes to say a migration takes no lock at all.

// Sample is one reading.
type Sample struct {
	At             string `json:"at"`
	Waiting        int    `json:"waiting"`
	GrantedOnTable string `json:"grantedOnTable"`
	QueuedOnTable  string `json:"queuedOnTable"`
}

// LockSummary is what the samples add up to. The maxima rather than the means: a lock that blocked
// forty connections for two seconds and nothing for the other twenty is not well described by an
// average of two.
type LockSummary struct {
	Samples      int      `json:"samples"`
	MaxWaiting   int      `json:"maxWaiting"`
	ModesGranted []string `json:"modesGranted"`
	ModesQueued  []string `json:"modesQueued"`

	// The window the migration's **own** lock was actually held, as against how long the whole
	// Flyway invocation took.
	//
	// These are different numbers and the difference is not small. `docker run` of the Flyway image
	// starts a container and a JVM before it sends a single statement - and on an arm64 machine the
	// published Flyway 9 image is amd64, so that startup is emulated. Timing the migration by timing
	// the command would charge the database for the client's boot, and the customer-side figures
	// would be diluted by however long that took. The samples know better: the migration's own mode
	// appears on the table when the lock is taken and leaves when it is released.
	//
	// MaxWaitingWhileHeld is the peak *inside* that window, which is the number an operator is
	// actually asking about - the bank queueing behind the migration rather than behind anything
	// else that happened to be running.
	OwnMode             string `json:"ownLockMode"`
	HeldFrom            string `json:"lockHeldFrom,omitempty"`
	HeldTo              string `json:"lockHeldTo,omitempty"`
	SamplesHolding      int    `json:"samplesWithTheLockHeld"`
	MaxWaitingWhileHeld int    `json:"maxWaitingWhileHeld"`

	// Raw keeps every sample, because a summary nobody can recompute is a summary nobody can check -
	// the same reason workload/baselines commits the scrapes beside the reports.
	Raw []Sample `json:"samples_raw"`
}

// HeldForSamples says how much of the sampled window the lock was held for, in samples. Multiplied
// by the sampling interval it is a duration, and it is reported that way rather than as a timestamp
// difference because the samples carry the database's clock rather than this process's.
func (s LockSummary) HeldFor() time.Duration {
	if s.SamplesHolding == 0 {
		return 0
	}
	return time.Duration(s.SamplesHolding) * sampleEvery
}

// lockQuery asks three questions in one round trip, because a sampler that took three would report
// three different instants as one.
//
//   - how many backends are waiting on a lock, which is the bank queueing;
//   - what modes are held on the table being migrated;
//   - what modes are queued for it.
//
// The table name is an identifier this package was handed by the caller, never anything a contract
// did not already bound - the same rule internal/injector's lock statement is held to.
func (m *Migration) lockQuery() string {
	return fmt.Sprintf(`SELECT
  to_char(clock_timestamp() AT TIME ZONE 'UTC', 'HH24:MI:SS.MS'),
  (SELECT count(*) FROM pg_stat_activity
     WHERE datname = current_database() AND wait_event_type = 'Lock'),
  coalesce((SELECT string_agg(DISTINCT l.mode, ',' ORDER BY l.mode) FROM pg_locks l
     JOIN pg_class c ON c.oid = l.relation
     WHERE c.relname = '%[1]s' AND l.granted), ''),
  coalesce((SELECT string_agg(DISTINCT l.mode, ',' ORDER BY l.mode) FROM pg_locks l
     JOIN pg_class c ON c.oid = l.relation
     WHERE c.relname = '%[1]s' AND NOT l.granted), '')`, m.settings.Table)
}

// sample starts reading pg_locks and returns the function that stops the sampler and hands back
// everything it read.
func (m *Migration) sample(ctx context.Context) func() []Sample {
	samples := make(chan Sample, 4096)
	done := make(chan struct{})
	var once sync.Once
	var collected []Sample
	var finished sync.WaitGroup

	finished.Add(1)
	go func() {
		defer finished.Done()
		defer close(samples)
		for {
			select {
			case <-done:
				return
			case <-ctx.Done():
				return
			default:
			}
			if one, ok := m.readLocks(ctx); ok {
				select {
				case samples <- one:
				default:
					// A full channel means the migration outlived the buffer. Dropping the reading
					// is better than blocking the sampler, and the count in the summary says how
					// many were taken rather than how many were meant to be.
				}
			}
			timer := time.NewTimer(sampleEvery)
			select {
			case <-done:
				timer.Stop()
				return
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
		}
	}()

	stop := func() []Sample {
		once.Do(func() {
			close(done)
			finished.Wait()
			for one := range samples {
				collected = append(collected, one)
			}
		})
		return collected
	}
	return stop
}

const sampleEvery = 250 * time.Millisecond

func (m *Migration) readLocks(ctx context.Context) (Sample, bool) {
	out, err := m.settings.Fixture.RunInContainer(ctx, m.settings.DatabaseContainer,
		"psql", "-U", "tessera", "-d", "tessera", "-tA", "-F", "|", "-c", m.lockQuery())
	if err != nil {
		// A failed sample is not a failed migration. The migration is the thing under way, and
		// aborting it because one psql call lost a race would destroy the measurement to report a
		// problem with reading it.
		return Sample{}, false
	}
	return parseSample(string(out))
}

func parseSample(line string) (Sample, bool) {
	fields := strings.Split(strings.TrimSpace(line), "|")
	if len(fields) < 4 {
		return Sample{}, false
	}
	waiting, err := strconv.Atoi(strings.TrimSpace(fields[1]))
	if err != nil {
		return Sample{}, false
	}
	return Sample{
		At:             strings.TrimSpace(fields[0]),
		Waiting:        waiting,
		GrantedOnTable: strings.TrimSpace(fields[2]),
		QueuedOnTable:  strings.TrimSpace(fields[3]),
	}, true
}

// OwnLockMode is the lock the migration itself takes on the table, which is how a sample can be told
// apart from the RowExclusiveLock every ordinary writer holds.
//
// A plain CREATE INDEX takes SHARE, which blocks every writer and lets readers through. CREATE INDEX
// CONCURRENTLY takes SHARE UPDATE EXCLUSIVE, which blocks neither and pays for it with two passes
// over the table and a wait for every transaction that could see it.
func (v Variant) OwnLockMode() string {
	if v == Concurrent {
		return "ShareUpdateExclusiveLock"
	}
	return "ShareLock"
}

func summarise(collected []Sample, own string) LockSummary {
	summary := LockSummary{Samples: len(collected), Raw: collected, OwnMode: own}
	granted := map[string]bool{}
	queued := map[string]bool{}
	for _, one := range collected {
		if one.Waiting > summary.MaxWaiting {
			summary.MaxWaiting = one.Waiting
		}
		if holding(one, own) {
			summary.SamplesHolding++
			if summary.HeldFrom == "" {
				summary.HeldFrom = one.At
			}
			summary.HeldTo = one.At
			if one.Waiting > summary.MaxWaitingWhileHeld {
				summary.MaxWaitingWhileHeld = one.Waiting
			}
		}
		for _, mode := range strings.Split(one.GrantedOnTable, ",") {
			if mode != "" {
				granted[mode] = true
			}
		}
		for _, mode := range strings.Split(one.QueuedOnTable, ",") {
			if mode != "" {
				queued[mode] = true
			}
		}
	}
	summary.ModesGranted = sorted(granted)
	summary.ModesQueued = sorted(queued)
	return summary
}

// holding is exact rather than a substring test, because ShareLock is a prefix of nothing but
// ShareRowExclusiveLock and ShareUpdateExclusiveLock are both real modes an ordinary statement can
// hold. A substring match would report a concurrent build as a blocking one.
func holding(one Sample, own string) bool {
	for _, mode := range strings.Split(one.GrantedOnTable, ",") {
		if mode == own {
			return true
		}
	}
	return false
}

func sorted(set map[string]bool) []string {
	out := make([]string, 0, len(set))
	for each := range set {
		out = append(out, each)
	}
	sort.Strings(out)
	return out
}

// Render writes the samples as the committed locks.txt: one line per reading, with a header saying
// what the columns are. A committed artefact that needs this package to be read is one nobody will
// read.
func (s LockSummary) Render(variant Variant, table string) string {
	var out strings.Builder
	fmt.Fprintf(&out, "# pg_locks, sampled every %s while the %s migration on %s was applied.\n",
		sampleEvery, variant, table)
	fmt.Fprintf(&out, "# time | backends waiting on a lock | modes granted on %s | modes queued for %s\n",
		table, table)
	if s.SamplesHolding > 0 {
		fmt.Fprintf(&out, "# %s was held from %s to %s - %d of %d samples, about %s.\n",
			s.OwnMode, s.HeldFrom, s.HeldTo, s.SamplesHolding, s.Samples, s.HeldFor())
	} else {
		fmt.Fprintf(&out, "# %s was never observed on %s in these samples.\n", s.OwnMode, table)
	}
	for _, one := range s.Raw {
		fmt.Fprintf(&out, "%s|%d|%s|%s\n", one.At, one.Waiting, one.GrantedOnTable, one.QueuedOnTable)
	}
	return out.String()
}
