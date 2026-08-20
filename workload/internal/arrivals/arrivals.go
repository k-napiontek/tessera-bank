// Package arrivals turns an intensity curve into a schedule of intended send times.
//
// # The open model, and why it is the whole point
//
// A closed model - N virtual users, each waiting for a response before sending again - throttles
// itself precisely when the system under test slows down. Offered load falls exactly when the
// interesting thing is happening, the queue never builds, and the latency figures come out
// flattering. That is coordinated omission, and it is the same class of defect as the V99
// truncation in WP-04: the output looks entirely plausible and is simply wrong.
//
// So this package schedules an **intended send time** for every event, computed before the run
// starts and independent of any response. A driver that falls behind stays behind, and WP-21
// measures latency from the intended time rather than from the actual send - which is what makes a
// saturated estate look saturated. ADR 0016 records the decision.
//
// # Reproducibility
//
// The same seed, the same model and the same business date give a byte-identical schedule, on any
// machine. Two choices carry that: math/rand/v2's PCG source, whose output is a specified function
// of its seed rather than of the runtime, and inverse-transform sampling built on Float64, whose
// derivation from Uint64 is specified as well. rand.ExpFloat64 would be shorter and is a ziggurat
// whose tables are an implementation detail - the wrong trade for a figure somebody has to be able
// to reproduce from a manifest a year later.
package arrivals

import (
	"errors"
	"fmt"
	"iter"
	"math"
	"math/rand/v2"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
)

// This file names float64, and internal/money/source_test.go requires a recorded reason. The reason
// is that a Poisson process is defined over continuous time: an interarrival gap is a real number
// of seconds, and thinning compares an intensity ratio against a uniform draw. No money passes
// through here.

// stream separates this generator's random stream from any other seeded with the same number. PCG
// takes two words; using the seed for one and a fixed constant for the other means that a driver
// seeding a second generator at 42 does not replay this one.
const stream uint64 = 0x776f_726b_6c6f_6164 // "workload"

// Event is one intended arrival.
type Event struct {
	// Seq is the ordinal in the schedule, from 0. Stable for a given seed and model.
	Seq int64
	// At is the intended send time as an offset into the business day, in business time. A driver
	// running compressed divides by the compression factor to get the real delay.
	At time.Duration
	// Minute is the business minute At falls in, which is what a report buckets by.
	Minute bankday.Minute
}

// Process is a non-homogeneous Poisson process over one business date.
type Process struct {
	curve bankday.Curve
	date  bankday.Date
	scale float64
	peak  float64
}

// ErrScale reports a scale dial that is not a positive finite number.
var ErrScale = errors.New("arrivals: scale must be positive and finite")

// New builds the process for one business date at one scale.
func New(curve bankday.Curve, date bankday.Date, scale float64) (Process, error) {
	if scale <= 0 || math.IsNaN(scale) || math.IsInf(scale, 0) {
		return Process{}, ErrScale
	}
	peak := curve.PeakRatePerSecond(date, scale)
	if peak <= 0 {
		return Process{}, fmt.Errorf("arrivals: %s has no demand at scale %v", date, scale)
	}
	return Process{curve: curve, date: date, scale: scale, peak: peak}, nil
}

// Date is the business date this process schedules.
func (p Process) Date() bankday.Date { return p.date }

// Scale is the dial this process was built at.
func (p Process) Scale() float64 { return p.scale }

// Events yields the day's arrivals in time order.
//
// Lewis-Shedler thinning: draw candidates from a homogeneous process at the day's peak intensity,
// then keep each with probability lambda(t)/peak. With the present curve the intensity is piecewise
// constant by hour, so an exact per-hour sampler would reject nothing and run about twice as fast.
// Thinning is used anyway because it stays correct if the curve ever stops being piecewise constant
// - an interpolated shape, or a minute-level spike at the cut-off - and a sampler that is only
// correct for the model committed today is a trap for whoever writes the second model.
//
// The stream is lazy on purpose. A day at scale 1.0 is 21 million events, and a driver consumes
// them as it goes rather than holding a day in memory to send the first one.
func (p Process) Events(seed uint64) iter.Seq[Event] {
	return func(yield func(Event) bool) {
		random := rand.New(rand.NewPCG(seed, stream))
		const day = 24 * time.Hour

		elapsed := 0.0 // seconds of business time since 00:00
		var seq int64

		for {
			// Inverse transform on an exponential: -ln(1-U)/lambda. Float64 is in [0,1), so 1-U is
			// in (0,1] and the logarithm is finite.
			elapsed += -math.Log(1-random.Float64()) / p.peak
			at := time.Duration(elapsed * float64(time.Second))
			if at >= day {
				return
			}
			minute := bankday.Minute(at / time.Minute)
			intensity := p.curve.RatePerSecond(p.date, minute, p.scale)
			if random.Float64()*p.peak >= intensity {
				continue // thinned out
			}
			if !yield(Event{Seq: seq, At: at, Minute: minute}) {
				return
			}
			seq++
		}
	}
}
