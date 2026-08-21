package model_test

import (
	"encoding/json"
	"math"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/model"
)

// The committed model, which is a contract artefact rather than a fixture of this package. Loading
// the real one is the point: a decoder proved only against a hand-written sample is verified
// against something nobody runs.
const committed = "../../../contracts/workload/tessera-day-v1.json"

func load(t *testing.T) ([]byte, model.Model) {
	t.Helper()
	source, err := os.ReadFile(committed)
	if err != nil {
		t.Fatalf("reading %s: %v", committed, err)
	}
	loaded, err := model.Decode(source)
	if err != nil {
		t.Fatalf("decoding %s: %v", committed, err)
	}
	return source, loaded
}

func TestTheCommittedModelDecodes(t *testing.T) {
	_, loaded := load(t)

	if loaded.ModelID != "TB-WORKLOAD-DAY-V1" {
		t.Errorf("modelId = %q", loaded.ModelID)
	}
	if loaded.ModelVersion != "1.0.0" {
		t.Errorf("modelVersion = %q", loaded.ModelVersion)
	}
	if loaded.RealTime.DailyEventCount != 21_588_000 {
		t.Errorf("dailyEventCount = %d", loaded.RealTime.DailyEventCount)
	}
	if len(loaded.Population.Cohorts) != 4 {
		t.Errorf("%d cohorts, want 4", len(loaded.Population.Cohorts))
	}
	if loaded.Population.Size != 1_200_000 {
		t.Errorf("population size = %d", loaded.Population.Size)
	}
}

func TestTheCurveTheModelBuildsMatchesWhatItDeclares(t *testing.T) {
	_, loaded := load(t)
	curve, err := loaded.Curve()
	if err != nil {
		t.Fatalf("Curve: %v", err)
	}
	if got := curve.PeakToTrough(); math.Abs(got-loaded.RealTime.PeakToTroughRatio) > 1e-9 {
		t.Errorf("the built curve has a ratio of %v and the model declares %v",
			got, loaded.RealTime.PeakToTroughRatio)
	}
	// A plain Wednesday - every multiplier at 1.0 - must total the declared daily volume, or the
	// headline figure describes a day nobody executes.
	wednesday := bankday.NewDate(2026, time.August, 19)
	if got := curve.DayTotal(wednesday, 1.0); math.Abs(got-21_588_000) > 1e-6 {
		t.Errorf("a plain Wednesday totals %v, want 21588000", got)
	}
}

func TestThePopulationTheModelBuildsGeneratesTheDeclaredVolume(t *testing.T) {
	// The cross-check the contract checker makes in Python, made again here in the code that
	// actually runs. The two are independent on purpose: one proves the document is coherent, the
	// other proves the loader read it the same way.
	_, loaded := load(t)
	people, err := loaded.People()
	if err != nil {
		t.Fatalf("People: %v", err)
	}
	if people.Size() != 1_200_000 {
		t.Errorf("population size = %d", people.Size())
	}
	generated := int64(0)
	for _, cohort := range people.Cohorts() {
		generated += int64(math.Round(cohort.Share*float64(people.Size()))) * int64(cohort.EventsPerCustomerPerDay)
	}
	if generated != loaded.RealTime.DailyEventCount {
		t.Errorf("the population generates %d events and the model declares %d",
			generated, loaded.RealTime.DailyEventCount)
	}
}

func TestTheNamedWindowsSurviveDecodingIncludingTheOneThatWraps(t *testing.T) {
	_, loaded := load(t)

	batch, found := loaded.Window("overnight-batch")
	if !found {
		t.Fatal("the model declares no overnight-batch window")
	}
	if !batch.WrapsMidnight() {
		t.Error("the overnight batch did not survive decoding as a wrapping window")
	}
	if !batch.Contains(0) || !batch.Contains(1439) {
		t.Error("the overnight batch does not contain midnight")
	}

	cutOff, found := loaded.Instant("online-cut-off")
	if !found {
		t.Fatal("the model declares no online-cut-off instant")
	}
	if cutOff.At != 1200 {
		t.Errorf("the cut-off is at %v, want 20:00", cutOff.At)
	}
	if !strings.Contains(cutOff.Purpose, "ADR 0015") {
		t.Error("the cut-off's purpose no longer says it is not the reconciliation cut-off")
	}
}

func TestTheDigestChangesWithTheModelAndNotWithItsFormatting(t *testing.T) {
	// The manifest records the digest as well as the version, because a version somebody forgot to
	// raise is what this guards against. It hashes the decoded model rather than the file, so
	// reindenting the document does not invalidate every run report that came before it.
	source, loaded := load(t)
	digest := loaded.Digest()
	if len(digest) != 64 {
		t.Fatalf("digest %q is not a sha256", digest)
	}

	var reformatted any
	if err := json.Unmarshal(source, &reformatted); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	compact, err := json.Marshal(reformatted)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	sameModel, err := model.Decode(compact)
	if err != nil {
		t.Fatalf("decoding the compacted model: %v", err)
	}
	if sameModel.Digest() != digest {
		t.Error("reindenting the document changed its digest")
	}

	for _, change := range []struct {
		name string
		edit func(string) string
	}{
		{"the daily volume", func(s string) string { return strings.Replace(s, "21588000", "21588001", 1) }},
		{"one hour of the curve", func(s string) string { return strings.Replace(s, "1.60,", "1.61,", 1) }},
		{"the payday multiplier", func(s string) string { return strings.Replace(s, "1.60\n", "1.65\n", 1) }},
		{"a cohort's currency mix", func(s string) string { return strings.Replace(s, "0.96", "0.95", 1) }},
	} {
		t.Run(change.name, func(t *testing.T) {
			edited := change.edit(string(source))
			if edited == string(source) {
				t.Fatal("the edit changed nothing - the fixture no longer matches the model")
			}
			// Some edits break a sum the loader checks; that is a change too, and still a failure
			// to be identical rather than a passing digest.
			changed, err := model.Decode([]byte(edited))
			if err != nil {
				return
			}
			if changed.Digest() == digest {
				t.Errorf("changing %s left the digest unchanged", change.name)
			}
		})
	}
}

func TestDecodeRefusesADocumentItDoesNotFullyUnderstand(t *testing.T) {
	source, _ := load(t)
	cases := map[string]string{
		"an unknown field": strings.Replace(string(source),
			`"modelVersion": "1.0.0",`, `"modelVersion": "1.0.0", "throughput": 9,`, 1),
		"a different format id": strings.Replace(string(source),
			"TB-WORKLOAD-DAY-V1", "TB-WORKLOAD-DAY-V2", 1),
		"a weekday that is not one": strings.Replace(string(source),
			`"wednesday": 1.00,`, `"middleday": 1.00,`, 1),
		"a curve of the wrong length": strings.Replace(string(source),
			"0.10, 0.06, 0.05, 0.05, 0.06, 0.10,", "0.10, 0.06, 0.05,", 1),
		"not JSON at all": "the bank has a busy morning",
	}
	for name, document := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := model.Decode([]byte(document)); err == nil {
				t.Errorf("a model with %s was accepted", name)
			}
		})
	}
}

func TestDecodeRefusesAModelWhoseOwnNumbersDisagree(t *testing.T) {
	source, _ := load(t)
	// The loader repeats the contract checker's cross-check rather than trusting that validate.sh
	// ran. WP-21 will load a model from wherever it is pointed, and nothing guarantees that file
	// went through the contract suite.
	broken := strings.Replace(string(source), `"dailyEventCount": 21588000`, `"dailyEventCount": 21588001`, 1)
	if _, err := model.Decode([]byte(broken)); err == nil {
		t.Error("a model whose population does not generate its declared volume was accepted")
	}
	flat := strings.Replace(string(source), `"peakToTroughRatio": 32.0`, `"peakToTroughRatio": 3.0`, 1)
	if _, err := model.Decode([]byte(flat)); err == nil {
		t.Error("a model whose curve does not have its declared peak-to-trough was accepted")
	}
}
