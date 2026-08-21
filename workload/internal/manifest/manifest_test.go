package manifest_test

import (
	"encoding/json"
	"math"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/manifest"
	"github.com/k-napiontek/tessera-bank/workload/internal/model"
)

const committed = "../../../contracts/workload/tessera-day-v1.json"

func loadModel(t *testing.T) model.Model {
	t.Helper()
	source, err := os.ReadFile(committed)
	if err != nil {
		t.Fatalf("reading %s: %v", committed, err)
	}
	loaded, err := model.Decode(source)
	if err != nil {
		t.Fatalf("decoding %s: %v", committed, err)
	}
	return loaded
}

func testRun(t *testing.T) manifest.Run {
	t.Helper()
	return manifest.Run{
		Model:        loadModel(t),
		BusinessDate: bankday.NewDate(2026, time.August, 31),
		Seed:         42,
		Scale:        0.01,
		Compression:  72,
		From:         0,
		To:           bankday.MinutesPerDay,
		GitSHA:       "5f6c7fe",
	}
}

func TestTheManifestRecordsBothDials(t *testing.T) {
	// The Constraint this exists for: scale and compression are separate, and a throughput figure
	// means nothing without both. A manifest that recorded only one would let a run at 72x be
	// reported as if it were real time.
	built, err := manifest.New(testRun(t))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if built.Scale != 0.01 {
		t.Errorf("scale = %v", built.Scale)
	}
	if built.Compression != 72 {
		t.Errorf("compression = %v", built.Compression)
	}
	if built.Seed != 42 {
		t.Errorf("seed = %v", built.Seed)
	}
	if built.GitSHA != "5f6c7fe" {
		t.Errorf("gitSha = %v", built.GitSHA)
	}
	if built.ModelDigest != loadModel(t).Digest() {
		t.Error("the manifest does not carry the model's digest")
	}
}

func TestCompressionMultipliesTheOfferedRate(t *testing.T) {
	// The other half of the same Constraint. The business-time rate is a property of the model; the
	// rate a driver must actually offer is that times the compression, and this is where the two
	// are told apart in writing rather than in somebody's head.
	run := testRun(t)
	built, err := manifest.New(run)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if ratio := built.Offered.MeanPerSecond / built.Business.MeanPerSecond; math.Abs(ratio-72) > 1e-9 {
		t.Errorf("the offered rate is %vx the business rate, and compression is 72", ratio)
	}
	if ratio := built.Offered.PeakPerSecond / built.Business.PeakPerSecond; math.Abs(ratio-72) > 1e-9 {
		t.Errorf("the offered peak is %vx the business peak", ratio)
	}
}

func TestTheRunLengthIsTheCompressedDay(t *testing.T) {
	built, _ := manifest.New(testRun(t))
	if built.RealDuration != 20*time.Minute {
		t.Errorf("a whole day at 72x runs for %v, want 20m", built.RealDuration)
	}
}

func TestAWindowShortensTheRunAndTheVolume(t *testing.T) {
	run := testRun(t)
	run.From, run.To = 540, 1080 // branch hours
	built, err := manifest.New(run)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if built.Window.FromMinute != 540 || built.Window.ToMinute != 1080 {
		t.Errorf("window = %+v", built.Window)
	}

	whole, _ := manifest.New(testRun(t))
	if built.EventCount >= whole.EventCount {
		t.Errorf("nine hours of the day contains %d events and the whole day %d",
			built.EventCount, whole.EventCount)
	}
	if built.RealDuration >= whole.RealDuration {
		t.Errorf("nine hours runs for %v and the whole day %v", built.RealDuration, whole.RealDuration)
	}
}

func TestTheDigestChangesWhenTheModelDoes(t *testing.T) {
	source, err := os.ReadFile(committed)
	if err != nil {
		t.Fatalf("reading %s: %v", committed, err)
	}
	before, _ := manifest.New(testRun(t))

	edited := strings.Replace(string(source), `"multiplier": 1.60`, `"multiplier": 1.70`, 1)
	changed, err := model.Decode([]byte(edited))
	if err != nil {
		t.Fatalf("decoding the edited model: %v", err)
	}
	run := testRun(t)
	run.Model = changed
	after, _ := manifest.New(run)

	if before.ModelDigest == after.ModelDigest {
		t.Error("changing the payday multiplier left the manifest digest unchanged")
	}
	if before.ModelVersion != after.ModelVersion {
		t.Fatal("the fixture changed the version too - the digest proves nothing here")
	}
}

func TestTheManifestIsJsonAndNamesItsOwnFormat(t *testing.T) {
	built, _ := manifest.New(testRun(t))
	encoded, err := json.MarshalIndent(built, "", "  ")
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var round manifest.Manifest
	if err := json.Unmarshal(encoded, &round); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if round.FormatID != manifest.FormatID {
		t.Errorf("formatId = %q, want %q", round.FormatID, manifest.FormatID)
	}
	if round.ModelDigest != built.ModelDigest || round.Seed != built.Seed {
		t.Error("the manifest does not survive a round trip")
	}
}

func TestTheManifestCarriesNothingResemblingPersonalData(t *testing.T) {
	// data-classification.md asks that verification grep the actual output rather than assert about
	// intent. A manifest describes a run, and there is no field in it a person could appear in -
	// this is the check that keeps it that way.
	built, _ := manifest.New(testRun(t))
	encoded, err := json.Marshal(built)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	for _, forbidden := range []string{
		"name", "email", "address", "phone", "pesel", "iban", "holder", "birth",
	} {
		if strings.Contains(strings.ToLower(string(encoded)), forbidden) {
			t.Errorf("the manifest contains %q: %s", forbidden, encoded)
		}
	}
}

func TestARunThatCannotBeDescribedIsRefused(t *testing.T) {
	cases := map[string]func(*manifest.Run){
		"a scale of zero":       func(r *manifest.Run) { r.Scale = 0 },
		"a negative scale":      func(r *manifest.Run) { r.Scale = -1 },
		"no compression":        func(r *manifest.Run) { r.Compression = 0 },
		"a window ending first": func(r *manifest.Run) { r.From, r.To = 900, 540 },
		"a window off the day":  func(r *manifest.Run) { r.From, r.To = 0, 2000 },
		"an empty window":       func(r *manifest.Run) { r.From, r.To = 600, 600 },
	}
	for name, breakIt := range cases {
		t.Run(name, func(t *testing.T) {
			run := testRun(t)
			breakIt(&run)
			if _, err := manifest.New(run); err == nil {
				t.Errorf("a run with %s was accepted", name)
			}
		})
	}
}
