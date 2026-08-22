// Package soak turns a series of daily scrapes into what a table did over time.
//
// It exists because F-28 is a prediction and this is what turns it into a rate. Nothing prunes
// `outbox_record` or `idempotency_record`: a dispatched outbox row and a completed idempotency
// record are kept forever, because a retention sweep needs a retention period and that is a
// regulatory question rather than an engineering one. `V5__idempotency.sql` said so in 2026 and
// nothing has answered it since. What has been missing is the figure - how fast, in rows and in
// bytes, and per what.
//
// **Every figure here is reported per business day and per posting.** Rows per day is a property of
// the dials: it moves with --scale and with --compress, and it describes the fixture's compressed
// day rather than the bank's. Rows per posting is a property of the ledger, and it is the only one
// of the two that transfers to a real day. F-84 is the same mistake made about outbox lag, where a
// number that described the compression dial read as a number describing the relay.
//
// It performs no I/O - cmd/workload-soak hands it the scrapes it read - and is classified as driver
// rather than engine for the reason internal/slo is: by role. Two drivers share the engine, and a
// report on what one estate's tables did is not part of the model either of them executes.
package soak

import (
	"fmt"
	"sort"
	"strings"

	"github.com/k-napiontek/tessera-bank/workload/internal/reconcile"
)

// The metric names, as the ledger exposes them. Read out of DatabaseSignals rather than invented
// here; contracts/slo/tessera-slo-v1.json carries each of them as a signal with no objective, and
// the `noObjectiveBecause` on ledger_db_table_size_bytes names F-28 directly.
const (
	liveTuples = "ledger_db_live_tuples"
	deadTuples = "ledger_db_dead_tuples"
	tableSize  = "ledger_db_table_size_bytes"
	autovacuum = "ledger_db_autovacuums"
)

// Grown is what the soak watches: the two tables F-28 names.
var Grown = []string{"outbox_record", "idempotency_record"}

// Churned is the table every posting rewrites in place. One row per account and an UPDATE per
// posting, so under churn it is a pure dead-tuple generator against a fixed live count.
var Churned = []string{"balance"}

// Day is one business date of the soak: the ledger's scrapes at each end of the driven day, and how
// many money movements the ledger posted between them.
//
// **Both ends, not just the closing one, and that is a correctness fix rather than a convenience.**
// A run seeds before it drives - it opens and funds the accounts the day will use - and the driver
// takes its opening scrape *after* seeding. So the growth between two consecutive closing scrapes
// includes the next day's seeding, while the postings counted between that day's own two scrapes do
// not. Dividing the first by the second would inflate rows-per-posting by whatever the fixture's
// setup wrote, which on this fixture is several times the day itself. Numerator and denominator are
// therefore both taken from the same bracket.
type Day struct {
	BusinessDate string
	Before       string
	Scrape       string
	// Postings is the ledger's own count of what it posted between Before and Scrape, or zero when
	// the run did not report one. Zero means unknown and is never treated as none: a rate divided by
	// a count nobody supplied would be invented.
	Postings float64
}

// Point is one table on one day.
type Point struct {
	BusinessDate string  `json:"businessDate"`
	Rows         float64 `json:"rows"`
	Bytes        float64 `json:"bytes"`
}

// Series is what one table did over the soak.
type Series struct {
	Name string  `json:"table"`
	Rows []Point `json:"points"`

	// The absolute trajectory: where the table stood at the end of the first day and the last, and
	// how much of that the soak as a whole added. This includes everything the fixture did, seeding
	// and all, because it is what the table actually holds.
	AddedOverTheSoak float64 `json:"rowsAddedOverTheSoak"`
	BytesOverTheSoak float64 `json:"bytesAddedOverTheSoak"`

	// The rate, from each day's own bracket rather than from consecutive closing scrapes, so that
	// the fixture's seeding is outside both the numerator and the denominator.
	RowsPerDay  float64 `json:"rowsPerDrivenDay"`
	BytesPerDay float64 `json:"bytesPerDrivenDay"`
	// RowsPerPosting is zero when no run reported its postings, which means unknown rather than none.
	RowsPerPosting float64 `json:"rowsPerPosting"`
}

// Churn is what a table rewritten in place did: dead tuples against the autovacuum count.
//
// The two together rather than dead tuples alone, because they answer different questions. Dead
// tuples rising while nothing vacuums is a collector that never ran; dead tuples rising while it
// runs repeatedly is a collector that is running and losing. An operator needs to know which.
type Churn struct {
	Name           string  `json:"table"`
	PeakDeadTuples float64 `json:"peakDeadTuples"`
	FirstDead      float64 `json:"firstDeadTuples"`
	LastDead       float64 `json:"lastDeadTuples"`
	Autovacuums    float64 `json:"autovacuumsDuringTheSoak"`
	// KeepingUp is true when the collector ran and dead tuples did not end higher than they started.
	KeepingUp bool `json:"autovacuumKeepingUp"`
}

// Conditions are the dials every figure was taken at. A number without them is a hunch wearing a
// decimal point, which is this repository's own rule rather than a flourish.
type Conditions struct {
	Scale       float64
	Compression int
	Window      string
	Hardware    string
	GitSHA      string
	Accounts    float64
}

// Growth is the whole measurement.
type Growth struct {
	Days   int      `json:"businessDates"`
	Tables []Series `json:"tables"`
	Churns []Churn  `json:"churn"`
}

// Measure reads the series out of the daily scrapes.
func Measure(days []Day) (Growth, error) {
	if len(days) < 2 {
		return Growth{}, fmt.Errorf("soak: %d business date(s), and a growth rate needs at least "+
			"two points - one point presented as a rate is an invention rather than a measurement",
			len(days))
	}

	growth := Growth{Days: len(days)}
	postings := 0.0
	for _, day := range days {
		postings += day.Postings
	}

	for _, table := range Grown {
		series := Series{Name: table}
		driven, drivenBytes := 0.0, 0.0
		for _, day := range days {
			series.Rows = append(series.Rows, Point{
				BusinessDate: day.BusinessDate,
				Rows:         gauge(day.Scrape, liveTuples, table),
				Bytes:        gauge(day.Scrape, tableSize, table),
			})
			// What the driven day itself added, from that day's own two scrapes.
			driven += gauge(day.Scrape, liveTuples, table) - gauge(day.Before, liveTuples, table)
			drivenBytes += gauge(day.Scrape, tableSize, table) - gauge(day.Before, tableSize, table)
		}
		first, last := series.Rows[0], series.Rows[len(series.Rows)-1]
		series.AddedOverTheSoak = last.Rows - first.Rows
		series.BytesOverTheSoak = last.Bytes - first.Bytes

		series.RowsPerDay = driven / float64(len(days))
		series.BytesPerDay = drivenBytes / float64(len(days))
		if postings > 0 {
			series.RowsPerPosting = driven / postings
		}
		growth.Tables = append(growth.Tables, series)
	}

	for _, table := range Churned {
		first := gauge(days[0].Scrape, deadTuples, table)
		last := gauge(days[len(days)-1].Scrape, deadTuples, table)
		churn := Churn{
			Name:        table,
			FirstDead:   first,
			LastDead:    last,
			Autovacuums: gauge(days[len(days)-1].Scrape, autovacuum, table) - gauge(days[0].Scrape, autovacuum, table),
		}
		for _, day := range days {
			if dead := gauge(day.Scrape, deadTuples, table); dead > churn.PeakDeadTuples {
				churn.PeakDeadTuples = dead
			}
		}
		churn.KeepingUp = churn.Autovacuums > 0 && last <= first
		growth.Churns = append(growth.Churns, churn)
	}
	return growth, nil
}

// gauge reads one table's value of one metric out of a scrape.
//
// Through internal/reconcile's reader rather than a third parser of the same format. F-61, F-64 and
// F-66 each record a second copy of something rotting, and an exposition parser is exactly the kind
// of thing that acquires a fix in one copy and not the other.
func gauge(exposition, metric, table string) float64 {
	for _, sample := range reconcile.Read(exposition, metric) {
		if sample.Labels["table"] == table {
			return sample.Value
		}
	}
	return 0
}

// Table returns one series, or nil.
func (g Growth) Table(name string) *Series {
	for i := range g.Tables {
		if g.Tables[i].Name == name {
			return &g.Tables[i]
		}
	}
	return nil
}

// Churn returns one churn record, or nil.
func (g Growth) Churn(name string) *Churn {
	for i := range g.Churns {
		if g.Churns[i].Name == name {
			return &g.Churns[i]
		}
	}
	return nil
}

const businessDaysPerYear = 250

// Render writes the report.
//
// Three things it is careful about, and each of them is a way this report could have been read as
// saying more than it measured.
//
// The **row counts are estimates**. `n_live_tup` is maintained by the statistics collector and
// corrected by autovacuum, so it is close but it is not a count; `pg_table_size` is exact. Printing
// both as though they were the same kind of number would overstate half the report.
//
// The **yearly figure is an extrapolation** and says so in its own section rather than sitting in a
// column beside measured ones.
//
// And every figure carries the **dials it was taken at**, because rows per day moves with them.
func (g Growth) Render(conditions Conditions) string {
	var out strings.Builder

	fmt.Fprintf(&out, "== The soak ==\n")
	fmt.Fprintf(&out, "  %d business dates, scale %g, compression %dx", g.Days, conditions.Scale, conditions.Compression)
	if conditions.Window != "" {
		fmt.Fprintf(&out, ", window %s", conditions.Window)
	}
	fmt.Fprintf(&out, "\n")
	if conditions.Accounts > 0 {
		fmt.Fprintf(&out, "  %.0f accounts\n", conditions.Accounts)
	}
	if conditions.Hardware != "" {
		fmt.Fprintf(&out, "  %s", conditions.Hardware)
		if conditions.GitSHA != "" {
			fmt.Fprintf(&out, "   commit %s", conditions.GitSHA)
		}
		fmt.Fprintf(&out, "\n")
	}

	fmt.Fprintf(&out, "\n== What nothing prunes ==\n")
	fmt.Fprintf(&out, "  The two tables F-28 names. Row counts are PostgreSQL's own estimate\n")
	fmt.Fprintf(&out, "  (n_live_tup, maintained by the statistics collector); sizes are exact\n")
	fmt.Fprintf(&out, "  (pg_table_size).\n\n")

	for _, series := range g.Tables {
		first, last := series.Rows[0], series.Rows[len(series.Rows)-1]
		fmt.Fprintf(&out, "  %s\n", series.Name)
		fmt.Fprintf(&out, "    %-26s %.0f rows, %s\n", "at "+first.BusinessDate, first.Rows, bytes(first.Bytes))
		fmt.Fprintf(&out, "    %-26s %.0f rows, %s\n", "at "+last.BusinessDate, last.Rows, bytes(last.Bytes))
		fmt.Fprintf(&out, "    %-26s %+.0f rows, %s\n", "added over the whole soak",
			series.AddedOverTheSoak, signedBytes(series.BytesOverTheSoak))
		fmt.Fprintf(&out, "    %-26s %.0f rows, %s\n", "per driven day",
			series.RowsPerDay, bytes(series.BytesPerDay))
		if series.RowsPerPosting > 0 {
			fmt.Fprintf(&out, "    %-26s %.2f rows\n", "per posting", series.RowsPerPosting)
		} else {
			fmt.Fprintf(&out, "    %-26s not reported by these runs\n", "per posting")
		}
		fmt.Fprintf(&out, "\n")
	}
	fmt.Fprintf(&out, "  \"Added over the whole soak\" includes what the fixture wrote to set each day\n")
	fmt.Fprintf(&out, "  up - it opens and funds the accounts a day will use before driving it. \"Per\n")
	fmt.Fprintf(&out, "  driven day\" and \"per posting\" are taken from each day's own two scrapes, which\n")
	fmt.Fprintf(&out, "  bracket the day and not its setup, so the fixture's seeding is outside both.\n\n")

	fmt.Fprintf(&out, "== What is rewritten in place ==\n")
	for _, churn := range g.Churns {
		fmt.Fprintf(&out, "  %s\n", churn.Name)
		fmt.Fprintf(&out, "    %-22s %.0f\n", "dead tuples, first", churn.FirstDead)
		fmt.Fprintf(&out, "    %-22s %.0f\n", "dead tuples, peak", churn.PeakDeadTuples)
		fmt.Fprintf(&out, "    %-22s %.0f\n", "dead tuples, last", churn.LastDead)
		fmt.Fprintf(&out, "    %-22s %.0f\n", "autovacuums", churn.Autovacuums)
		if churn.KeepingUp {
			fmt.Fprintf(&out, "    autovacuum ran and dead tuples did not end higher: it is keeping up\n")
		} else if churn.Autovacuums == 0 {
			fmt.Fprintf(&out, "    autovacuum did not run at all during the soak, so this says nothing\n")
			fmt.Fprintf(&out, "    about whether it would keep up - only that it was not asked to\n")
		} else {
			fmt.Fprintf(&out, "    autovacuum ran %.0f times and dead tuples still ended higher\n", churn.Autovacuums)
		}
		fmt.Fprintf(&out, "\n")
	}

	fmt.Fprintf(&out, "== Extrapolation, which is not a measurement ==\n")
	fmt.Fprintf(&out, "  Everything below is arithmetic on the per-day rate above, not something\n")
	fmt.Fprintf(&out, "  this soak observed. It was measured over %d business dates at scale %g\n", g.Days, conditions.Scale)
	fmt.Fprintf(&out, "  and %dx compression; a real day offers different volume, so read the\n", conditions.Compression)
	fmt.Fprintf(&out, "  per-posting figure above rather than this one when the volume differs.\n\n")
	for _, series := range g.Tables {
		fmt.Fprintf(&out, "  %-22s %.0f rows and %s over %d business days\n",
			series.Name, series.RowsPerDay*businessDaysPerYear,
			bytes(series.BytesPerDay*businessDaysPerYear), businessDaysPerYear)
	}
	fmt.Fprintf(&out, "\n  Nothing prunes either table. The retention period is a regulatory\n")
	fmt.Fprintf(&out, "  question rather than an engineering one, and this package does not\n")
	fmt.Fprintf(&out, "  invent one - F-28 stays open with these figures attached.\n")

	return out.String()
}

func bytes(v float64) string {
	units := []struct {
		suffix string
		size   float64
	}{{"GB", 1 << 30}, {"MB", 1 << 20}, {"kB", 1 << 10}}
	for _, unit := range units {
		if v >= unit.size {
			return fmt.Sprintf("%.1f %s", v/unit.size, unit.suffix)
		}
	}
	return fmt.Sprintf("%.0f B", v)
}

func signedBytes(v float64) string {
	if v < 0 {
		return "-" + bytes(-v)
	}
	return "+" + bytes(v)
}

// Tables names every table with a series, sorted, so a caller can iterate without depending on the
// order the constants happen to be in.
func (g Growth) TableNames() []string {
	names := make([]string, 0, len(g.Tables))
	for _, series := range g.Tables {
		names = append(names, series.Name)
	}
	sort.Strings(names)
	return names
}
