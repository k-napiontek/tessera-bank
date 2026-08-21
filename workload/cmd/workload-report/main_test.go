package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// generate renders the committed fixtures into a report.
func generate(t *testing.T) string {
	t.Helper()
	var out strings.Builder
	err := run([]string{
		"--manifest", filepath.Join("testdata", "run.json"),
		"--catalogue", filepath.Join("..", "..", "..", "contracts", "slo", "tessera-slo-v1.json"),
		"--before", filepath.Join("testdata", "before.prom"),
		"--after", filepath.Join("testdata", "after.prom"),
	}, &out)
	if err != nil {
		t.Fatalf("generating the report: %v", err)
	}
	return out.String()
}

// The Definition of Done asks for this and the reason is the one batch/reporting already pays for:
// a report that cannot be regenerated is a report nobody can check. A wall clock anywhere in it -
// a generation timestamp, an "as at" line - makes this impossible by construction, which is why
// this command reads no clock at all.
func TestTheSameInputsProduceAByteIdenticalReport(t *testing.T) {
	first := generate(t)
	second := generate(t)

	// Compared as bytes rather than by parsing either: two reports that differ only in whitespace
	// still differ, and a diff is how a run is compared with the committed baseline.
	if first != second {
		t.Fatalf("two runs over the same inputs differ:\n--- first\n%s\n--- second\n%s", first, second)
	}
	if len(first) == 0 {
		t.Fatal("the report is empty, so being identical proves nothing")
	}
}

func TestTheReportJudgesTheObjectivesItCanAndSaysSoAboutTheRest(t *testing.T) {
	report := generate(t)

	// The run posted 90 transfers and released 3 holds against 1 failure, so the ledger's
	// availability objective is missed - 93 of 94 is 0.98936 against a target of 0.999.
	if !strings.Contains(report, "SLO-LEDGER-MOVEMENT-SUCCESS") {
		t.Fatal("the ledger's availability objective is not in the report")
	}
	for _, want := range []string{"MISSED", "target 0.99900", "0.98936 of 94 events"} {
		if !strings.Contains(report, want) {
			t.Errorf("the report does not say %q:\n%s", want, report)
		}
	}

	// An objective stated over a proportion of a window cannot be answered by two samples, and the
	// report says which two samples it has instead of producing a number from them.
	if !strings.Contains(report, "Objectives this report cannot answer") {
		t.Error("the report answers objectives it cannot answer, or hides that it cannot")
	}
	if !strings.Contains(report, "ledger_outbox_lag_seconds was 0 at the start and 4 at the end") {
		t.Errorf("the two points a reader does have are missing:\n%s", report)
	}
}

func TestTheReportRefusesAManifestFromARunItCannotDescribe(t *testing.T) {
	stale := filepath.Join(t.TempDir(), "stale.json")
	if err := os.WriteFile(stale, []byte(`{"formatId":"TB-WORKLOAD-RUN-V0","seed":1}`), 0o644); err != nil {
		t.Fatal(err)
	}
	var out strings.Builder
	err := run([]string{
		"--manifest", stale,
		"--catalogue", filepath.Join("..", "..", "..", "contracts", "slo", "tessera-slo-v1.json"),
		"--before", filepath.Join("testdata", "before.prom"),
		"--after", filepath.Join("testdata", "after.prom"),
	}, &out)
	if err == nil {
		t.Fatal("a manifest in a format this tool does not read was accepted, and every figure in " +
			"the report would then describe a run it had guessed at")
	}
}
