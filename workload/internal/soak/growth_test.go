package soak

import (
	"strconv"
	"strings"
	"testing"
)

// Real lines, in the shape Micrometer actually writes them - Java scientific notation for the sizes,
// a trailing comma inside the label set, and an application label on every series. Copied from
// workload/baselines/with-broker/after.prom rather than composed here.
func scrape(outbox, idempotency, dead, size, vacuums float64) string {
	return `# HELP ledger_db_live_tuples
ledger_db_live_tuples{application="ledger-api",table="posting",} 6718282.0
ledger_db_live_tuples{application="ledger-api",table="balance",} 338228.0
ledger_db_live_tuples{application="ledger-api",table="outbox_record",} ` + format(outbox) + `
ledger_db_live_tuples{application="ledger-api",table="idempotency_record",} ` + format(idempotency) + `
ledger_db_dead_tuples{application="ledger-api",table="balance",} ` + format(dead) + `
ledger_db_table_size_bytes{application="ledger-api",table="outbox_record",} ` + format(size) + `
ledger_db_table_size_bytes{application="ledger-api",table="idempotency_record",} 1.0E7
ledger_db_autovacuums{application="ledger-api",table="balance",} ` + format(vacuums) + `
ledger_db_autovacuum_age_seconds{application="ledger-api",table="hold",} NaN
ledger_transfers_total{application="ledger-api",operation="transfer",outcome="posted",} 100.0
`
}

// Micrometer writes Java scientific notation for large values and a plain decimal for small ones.
// Both forms go through the same reader, so the fixture emits both.
func format(v float64) string {
	if v >= 1e6 {
		return strconv.FormatFloat(v, 'E', -1, 64)
	}
	return strconv.FormatFloat(v, 'f', 1, 64)
}

func TestASeriesIsReadPerTableAcrossEveryDay(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(0, 0, 0, 0, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2)},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 10, 1e6, 3), Scrape: scrape(2000, 8000, 20, 2e6, 3)},
		{BusinessDate: "2026-06-03", Before: scrape(2000, 8000, 20, 2e6, 4), Scrape: scrape(3000, 12000, 30, 3e6, 4)},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}

	outbox := growth.Table("outbox_record")
	if outbox == nil {
		t.Fatal("outbox_record has no series")
	}
	if len(outbox.Rows) != 3 {
		t.Fatalf("read %d days, wanted 3", len(outbox.Rows))
	}
	if outbox.RowsPerDay != 1000 {
		t.Errorf("rows per day read as %g, and the series rises by exactly 1000 a day", outbox.RowsPerDay)
	}
	if outbox.BytesPerDay != 1e6 {
		t.Errorf("bytes per day read as %g", outbox.BytesPerDay)
	}
}

// The whole point of F-28: these two tables only ever grow, because nothing prunes them. A soak that
// could not tell a rising series from a flat one would not be evidence of anything.
func TestAFlatSeriesIsNotReportedAsGrowth(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(1000, 4000, 10, 1e6, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2)},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 10, 1e6, 9), Scrape: scrape(1000, 4000, 10, 1e6, 9)},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	if got := growth.Table("outbox_record").RowsPerDay; got != 0 {
		t.Errorf("a series that did not move was reported as growing at %g rows a day", got)
	}
}

// A soak needs at least two points to have a slope at all, and one point presented as a rate is the
// most confident wrong answer this report could produce.
func TestASingleDayIsRefusedRatherThanExtrapolatedFrom(t *testing.T) {
	_, err := Measure([]Day{{BusinessDate: "2026-06-01", Before: scrape(0, 0, 0, 0, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2)}})
	if err == nil {
		t.Fatal("a rate was computed from one point")
	}
	if !strings.Contains(err.Error(), "two") {
		t.Errorf("the error does not say what is missing: %v", err)
	}
}

// Rows per day is a property of the dial - it moves with --scale and --compress. Rows per posting is
// a property of the ledger, and it is the only one of the two that transfers to a real day. A report
// quoting the first alone would be F-84 in another costume.
func TestGrowthIsReportedPerPostingAsWellAsPerDay(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(0, 0, 5, 0, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2), Postings: 500},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 10, 1e6, 3), Scrape: scrape(2000, 8000, 20, 2e6, 3), Postings: 500},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	outbox := growth.Table("outbox_record")
	if outbox.RowsPerPosting != 2 {
		t.Errorf("rows per posting read as %g; 1000 rows arrived over 500 postings each day", outbox.RowsPerPosting)
	}
}

func TestPostingsAreNotInventedWhenTheRunDidNotReportThem(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(0, 0, 0, 0, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2)},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 10, 1e6, 3), Scrape: scrape(2000, 8000, 20, 2e6, 3)},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	if got := growth.Table("outbox_record").RowsPerPosting; got != 0 {
		t.Errorf("rows per posting was computed as %g from a run that reported no postings", got)
	}
}

// Dead tuples are the other half of the question, and they are read against the autovacuum count
// rather than on their own: a table whose dead tuples rise while nothing vacuums is a different
// finding from one where the collector is running and losing.
func TestDeadTuplesAreReadAgainstTheAutovacuumCount(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(0, 0, 50, 0, 2), Scrape: scrape(1000, 4000, 100, 1e6, 2)},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 100, 1e6, 2), Scrape: scrape(2000, 8000, 900, 2e6, 2)},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	churn := growth.Churn("balance")
	if churn == nil {
		t.Fatal("balance has no churn series")
	}
	if churn.PeakDeadTuples != 900 {
		t.Errorf("peak dead tuples read as %g", churn.PeakDeadTuples)
	}
	if churn.Autovacuums != 0 {
		t.Errorf("autovacuum count rose by %g over a soak in which it did not run", churn.Autovacuums)
	}
	if churn.KeepingUp {
		t.Error("a soak in which dead tuples rose ninefold and nothing vacuumed was read as keeping up")
	}
}

func TestAutovacuumKeepingPaceIsSaidSoRatherThanInferred(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(0, 0, 400, 0, 2), Scrape: scrape(1000, 4000, 800, 1e6, 2)},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 800, 1e6, 3), Scrape: scrape(2000, 8000, 120, 2e6, 7)},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	churn := growth.Churn("balance")
	if churn.Autovacuums != 5 {
		t.Errorf("autovacuums over the soak read as %g", churn.Autovacuums)
	}
	if !churn.KeepingUp {
		t.Error("dead tuples fell over the soak and autovacuum ran five times; that is keeping up")
	}
}

// An extrapolation is not a measurement, and the difference has to survive being read by somebody in
// a hurry. Renders it as its own labelled section rather than as another row in the table.
func TestTheExtrapolationSaysThatItIsOne(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(500, 2000, 5, 5e5, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2), Postings: 500},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 10, 1e6, 3), Scrape: scrape(2000, 8000, 20, 2e6, 3), Postings: 500},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	rendered := growth.Render(Conditions{
		Scale: 0.002, Compression: 720, Window: "branch-hours", Hardware: "darwin arm64",
	})
	if !strings.Contains(rendered, "extrapolation") && !strings.Contains(rendered, "Extrapolat") {
		t.Fatalf("nothing in the report says the yearly figure is an extrapolation:\n%s", rendered)
	}
	if !strings.Contains(rendered, "2 business dates") {
		t.Errorf("the report does not say how many points it was measured from:\n%s", rendered)
	}
	if !strings.Contains(rendered, "0.002") || !strings.Contains(rendered, "720") {
		t.Errorf("the report does not carry the dials it was measured at:\n%s", rendered)
	}
}

// n_live_tup is an estimate the statistics collector maintains, and pg_table_size is exact. A report
// that presented both as measurements would be overstating half of itself.
func TestTheRowCountIsLabelledAnEstimateAndTheSizeIsNot(t *testing.T) {
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(0, 0, 0, 0, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2)},
		{BusinessDate: "2026-06-02", Before: scrape(1000, 4000, 10, 1e6, 3), Scrape: scrape(2000, 8000, 20, 2e6, 3)},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	rendered := growth.Render(Conditions{Scale: 0.002, Compression: 720})
	if !strings.Contains(rendered, "estimate") {
		t.Errorf("nothing says the row counts are estimates:\n%s", rendered)
	}
}

// The invariant the bracket change exists for.
//
// A run seeds before it drives - it opens and funds the accounts the day will use - and the driver
// takes its opening scrape *after* seeding. So everything the fixture wrote to set the day up sits
// between one day's closing scrape and the next day's opening one. Both figures below are taken from
// each day's own two scrapes, so seeding is outside the numerator and the denominator alike; a rate
// taken from consecutive closing scrapes would charge the ledger for the fixture's setup.
func TestTheFixturesSeedingIsOutsideTheRateEntirely(t *testing.T) {
	// Between day one closing at 1000 and day two opening at 40000, the fixture wrote 39000 rows
	// setting the day up. Each driven day itself adds exactly 1000 over 500 postings.
	days := []Day{
		{BusinessDate: "2026-06-01", Before: scrape(0, 0, 5, 0, 2), Scrape: scrape(1000, 4000, 10, 1e6, 2), Postings: 500},
		{BusinessDate: "2026-06-02", Before: scrape(40000, 4000, 10, 4e7, 3), Scrape: scrape(41000, 8000, 20, 4.1e7, 3), Postings: 500},
	}
	growth, err := Measure(days)
	if err != nil {
		t.Fatal(err)
	}
	outbox := growth.Table("outbox_record")
	if outbox.RowsPerDay != 1000 {
		t.Errorf("rows per driven day read as %g; each day drove exactly 1000 and the fixture "+
			"wrote 39000 more between them", outbox.RowsPerDay)
	}
	if outbox.RowsPerPosting != 2 {
		t.Errorf("rows per posting read as %g, wanted 2", outbox.RowsPerPosting)
	}
	// The trajectory is the other claim, and it does include everything, because it is what the
	// table actually holds.
	if outbox.AddedOverTheSoak != 40000 {
		t.Errorf("the soak's total growth read as %g; the table went from 1000 to 41000",
			outbox.AddedOverTheSoak)
	}
}
