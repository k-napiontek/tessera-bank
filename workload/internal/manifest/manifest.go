// Package manifest describes a run well enough to reproduce it and to compare it with another.
//
// A load figure with no manifest behind it is an anecdote. WP-23 will put a baseline in this
// repository and WP-24 will compare a soak run against it, and neither claim survives unless the
// two runs can be shown to be the same run: same model, same seed, same dials, same window, same
// commit. That is all this file is.
//
// The two dials are recorded separately and deliberately. Scale changes how much demand a day
// contains; compression makes the same day happen faster and therefore multiplies the rate offered.
// A manifest that recorded one and not the other would let a twenty-minute run at 72x be read as a
// day in real time, and every rate in the report would be out by that factor - a plausible-looking
// figure that is simply wrong, which is the failure this estate keeps a trap list about.
package manifest

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/model"
)

// This file names float64, and internal/money/source_test.go requires a recorded reason. The reason
// is that every figure here is a rate - events per second - and none of them is money.

// FormatID names this manifest format, so that a reader can refuse one it does not understand.
const FormatID = "TB-WORKLOAD-RUN-V1"

// Run is what a caller asks for.
type Run struct {
	Model        model.Model
	BusinessDate bankday.Date
	Seed         uint64
	Scale        float64
	Compression  int
	// From and To bound the run to part of the business day. To is exclusive, and the whole day is
	// From 0, To bankday.MinutesPerDay.
	From, To bankday.Minute
	// GitSHA is the commit the run was made at, or "unknown" when it could not be determined. Never
	// left blank: a field that is sometimes absent and sometimes a commit is a field a report has to
	// guess about.
	GitSHA string
	// Hardware is what it ran on, or "unrecorded". Never left blank, for the same reason as GitSHA.
	//
	// Added by WP-24a, which has to commit a second baseline beside the first. Two measurements that
	// do not state their conditions cannot be compared, and comparing them anyway is how a team
	// concludes a regression exists - and until now the only conditions block in this repository was
	// cmd/workload-ceiling's, so a run manifest could not say which laptop produced it.
	Hardware string
	// Scenario names the degradation this run was executed under, empty for an undegraded one. The
	// digest is beside the identifier for the reason the model's is: a catalogue somebody edited
	// between two runs is two catalogues, and only the digest says so.
	ScenarioID     string
	ScenarioDigest string
}

// Window is the part of the business day a run covers.
type Window struct {
	FromMinute int    `json:"fromMinute"`
	ToMinute   int    `json:"toMinute"`
	From       string `json:"from"`
	To         string `json:"to"`
}

// Rates are events per second, at one of the two clocks.
type Rates struct {
	MeanPerSecond float64 `json:"meanPerSecond"`
	PeakPerSecond float64 `json:"peakPerSecond"`
}

// Manifest is the record of one run.
type Manifest struct {
	FormatID     string `json:"formatId"`
	ModelID      string `json:"modelId"`
	ModelVersion string `json:"modelVersion"`
	ModelDigest  string `json:"modelDigest"`
	GitSHA       string `json:"gitSha"`
	Hardware     string `json:"hardware"`

	// ScenarioID and ScenarioDigest are absent from an undegraded run rather than empty, so that a
	// baseline's manifest does not carry two blank fields implying a condition nobody injected.
	ScenarioID     string `json:"scenarioId,omitempty"`
	ScenarioDigest string `json:"scenarioDigest,omitempty"`

	BusinessDate string `json:"businessDate"`
	Weekday      string `json:"weekday"`
	Seed         uint64 `json:"seed"`

	Scale       float64 `json:"scale"`
	Compression int     `json:"compression"`
	Window      Window  `json:"window"`

	// DayMultiplier is what the calendar does to this date: weekday, payday and month-end composed.
	DayMultiplier float64 `json:"dayMultiplier"`
	// EventCount is how many events the window contains at this scale.
	EventCount int64 `json:"eventCount"`

	// Business is the rate in business time - a property of the model and the scale.
	Business Rates `json:"businessRates"`
	// Offered is the rate a driver must actually produce - Business times the compression. This is
	// the only figure that may be compared with a measured throughput.
	Offered Rates `json:"offeredRates"`

	// RealDuration is how long the run takes on a wall clock.
	RealDuration time.Duration `json:"-"`
	// RealDurationSeconds carries the same figure into JSON, in a unit a reader can see.
	RealDurationSeconds float64 `json:"realDurationSeconds"`
}

// ErrRun reports a run that cannot be described.
var ErrRun = errors.New("manifest: the run is not one this model can describe")

// New builds the manifest for a run, computing what that run will actually ask of the estate.
func New(run Run) (Manifest, error) {
	if run.Scale <= 0 {
		return Manifest{}, fmt.Errorf("%w: a scale of %v", ErrRun, run.Scale)
	}
	if run.From < 0 || run.To > bankday.MinutesPerDay || run.To <= run.From {
		return Manifest{}, fmt.Errorf("%w: the window %v to %v is not part of a day",
			ErrRun, run.From, run.To)
	}
	if run.GitSHA == "" {
		return Manifest{}, fmt.Errorf("%w: no commit recorded - pass \"unknown\" rather than nothing",
			ErrRun)
	}
	if run.Hardware == "" {
		return Manifest{}, fmt.Errorf("%w: no hardware recorded - pass \"unrecorded\" rather than "+
			"nothing, because a figure whose conditions are unstated is worse than one that says it "+
			"does not know them", ErrRun)
	}
	if (run.ScenarioID == "") != (run.ScenarioDigest == "") {
		return Manifest{}, fmt.Errorf("%w: a scenario identifier and its digest go together, and "+
			"only one of %q and %q is set", ErrRun, run.ScenarioID, run.ScenarioDigest)
	}

	clock, err := bankday.NewClock(run.Compression)
	if err != nil {
		return Manifest{}, err
	}
	curve, err := run.Model.Curve()
	if err != nil {
		return Manifest{}, err
	}

	// Walk the window a minute at a time rather than scaling the day's total by the fraction of it
	// covered. The curve is not flat, so nine hours of branch time is not nine twenty-fourths of a
	// day - and a manifest that said it was would understate every busy window and overstate every
	// quiet one.
	events, peak := 0.0, 0.0
	for minute := run.From; minute < run.To; minute++ {
		rate := curve.RatePerSecond(run.BusinessDate, minute, run.Scale)
		events += rate * 60
		if rate > peak {
			peak = rate
		}
	}

	businessSeconds := float64(run.To-run.From) * 60
	realDuration := clock.Real(time.Duration(run.To-run.From) * time.Minute)
	compression := float64(run.Compression)

	return Manifest{
		FormatID:     FormatID,
		ModelID:      run.Model.ModelID,
		ModelVersion: run.Model.ModelVersion,
		ModelDigest:  run.Model.Digest(),
		GitSHA:       run.GitSHA,
		Hardware:     run.Hardware,

		ScenarioID:     run.ScenarioID,
		ScenarioDigest: run.ScenarioDigest,

		BusinessDate: run.BusinessDate.String(),
		Weekday:      run.BusinessDate.Weekday().String(),
		Seed:         run.Seed,

		Scale:       run.Scale,
		Compression: run.Compression,
		Window: Window{
			FromMinute: int(run.From),
			ToMinute:   int(run.To),
			From:       run.From.String(),
			To:         run.To.String(),
		},

		DayMultiplier: curve.DayMultiplier(run.BusinessDate),
		EventCount:    int64(events + 0.5),

		Business: Rates{
			MeanPerSecond: events / businessSeconds,
			PeakPerSecond: peak,
		},
		Offered: Rates{
			MeanPerSecond: events / businessSeconds * compression,
			PeakPerSecond: peak * compression,
		},

		RealDuration:        realDuration,
		RealDurationSeconds: realDuration.Seconds(),
	}, nil
}

// Read decodes a manifest a run wrote.
//
// New builds one; this reads one back, which is what WP-23's report needs in order to say which run
// it is describing. A manifest whose format it does not recognise is refused rather than read
// partially: the fields it would find are exactly the fields both versions happen to share, and a
// report that named the wrong seed or the wrong compression would be a plausible account of a run
// that never took place.
func Read(document []byte) (Manifest, error) {
	var record Manifest
	decoder := json.NewDecoder(bytes.NewReader(document))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&record); err != nil {
		return Manifest{}, fmt.Errorf("%w: %v", ErrRun, err)
	}
	if record.FormatID != FormatID {
		return Manifest{}, fmt.Errorf(
			"%w: it announces itself as %q and this reads %q", ErrRun, record.FormatID, FormatID)
	}
	// RealDuration is json:"-", so a manifest read back carries only the seconds it published.
	record.RealDuration = time.Duration(record.RealDurationSeconds * float64(time.Second))
	return record, nil
}
