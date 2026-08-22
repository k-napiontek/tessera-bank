package migration

import (
	"strings"
	"testing"
)

// A real line, as psql -tA -F'|' prints it against the query in locks.go.
const sampleLine = "14:22:07.418|11|RowExclusiveLock,ShareLock|RowExclusiveLock"

func TestASampleIsReadAsTheFourThingsItSays(t *testing.T) {
	one, ok := parseSample(sampleLine)
	if !ok {
		t.Fatal("a well-formed sample was not read")
	}
	if one.At != "14:22:07.418" {
		t.Errorf("time read as %q", one.At)
	}
	if one.Waiting != 11 {
		t.Errorf("waiting read as %d", one.Waiting)
	}
	if one.GrantedOnTable != "RowExclusiveLock,ShareLock" {
		t.Errorf("granted read as %q", one.GrantedOnTable)
	}
	if one.QueuedOnTable != "RowExclusiveLock" {
		t.Errorf("queued read as %q", one.QueuedOnTable)
	}
}

// A sampler that turned a lost race into a zero would report a lock nobody was waiting for, which
// is the more dangerous of the two failures: it reads as a migration that cost nothing.
func TestAnUnreadableSampleIsDroppedRatherThanReadAsZero(t *testing.T) {
	for _, line := range []string{"", "psql: error: connection to server failed", "14:22:07|x|a|b", "14:22:07|3"} {
		if _, ok := parseSample(line); ok {
			t.Errorf("%q was accepted as a sample", line)
		}
	}
}

// The maxima rather than the means. A lock that blocked forty connections for two seconds and
// nothing for the other twenty is not described by an average of two, and the peak is the number an
// operator is actually asking about.
func TestTheSummaryKeepsThePeakAndEveryModeItSaw(t *testing.T) {
	summary := summarise([]Sample{
		{At: "1", Waiting: 0, GrantedOnTable: "RowExclusiveLock", QueuedOnTable: ""},
		{At: "2", Waiting: 40, GrantedOnTable: "ShareLock", QueuedOnTable: "RowExclusiveLock"},
		{At: "3", Waiting: 2, GrantedOnTable: "RowExclusiveLock", QueuedOnTable: ""},
	})
	if summary.MaxWaiting != 40 {
		t.Errorf("peak read as %d, and the average would have been 14", summary.MaxWaiting)
	}
	if summary.Samples != 3 {
		t.Errorf("kept %d samples", summary.Samples)
	}
	if got := strings.Join(summary.ModesGranted, ","); got != "RowExclusiveLock,ShareLock" {
		t.Errorf("modes granted read as %q", got)
	}
	if got := strings.Join(summary.ModesQueued, ","); got != "RowExclusiveLock" {
		t.Errorf("modes queued read as %q", got)
	}
}

// The committed artefact has to be readable without this package. A capture nobody can read is one
// nobody will check, which is the same argument workload/baselines makes for committing the scrapes.
func TestTheRenderedFileSaysWhatItsColumnsAre(t *testing.T) {
	summary := summarise([]Sample{{At: "14:22:07.418", Waiting: 11,
		GrantedOnTable: "ShareLock", QueuedOnTable: "RowExclusiveLock"}})
	rendered := summary.Render(Blocking, "posting")

	if !strings.Contains(rendered, "# time | backends waiting on a lock") {
		t.Errorf("no header naming the columns:\n%s", rendered)
	}
	if !strings.Contains(rendered, "blocking migration on posting") {
		t.Errorf("the header does not say what was being applied:\n%s", rendered)
	}
	if !strings.Contains(rendered, "14:22:07.418|11|ShareLock|RowExclusiveLock") {
		t.Errorf("the sample itself is not in the file:\n%s", rendered)
	}
}

// The sampler runs for the length of the migration and hands back what it read. Nothing here waits
// on a real clock: Fixture is a recorder and the loop is stopped as soon as it has produced.
func TestTheSamplerReadsWhileTheMigrationRunsAndStopsWithIt(t *testing.T) {
	r := &recorder{
		imageReply: appliedOutput,
		locksReply: sampleLine,
		indexReply: "posting_exercise_ix|t",
		sampled:    make(chan struct{}),
	}
	s := settings(t, r)
	m, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	record, err := m.Apply(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if record.Locks.Samples == 0 {
		t.Fatal("the migration was applied and no lock reading was taken while it ran")
	}
	if record.Locks.MaxWaiting != 11 {
		t.Errorf("the reading did not reach the record: peak %d", record.Locks.MaxWaiting)
	}
}
