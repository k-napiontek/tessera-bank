package bankday

import (
	"errors"
	"fmt"
	"time"
)

// This file names float64, and internal/money/source_test.go requires a recorded reason for that.
// The reason is that an intensity is continuous: it is events per second at an instant, not a count
// of anything, and the shape it comes from is a ratio between hours. No amount of money passes
// through this file, and none may - the scanner enforces that half separately.

// CurveSpec is the calendar half of a workload model, as the model states it.
type CurveSpec struct {
	// Diurnal is relative intensity by civil hour. Shape only: whatever these add to, a day
	// integrates to DailyEventCount.
	Diurnal [24]float64
	// Weekday scales the whole day. All seven days must be present.
	Weekday map[time.Weekday]float64
	// PaydayDays are days of the month that carry PaydayFactor.
	PaydayDays   []int
	PaydayFactor float64
	// MonthEndDays counts back from the last day of the month, inclusive, carrying MonthEndFactor.
	MonthEndDays   int
	MonthEndFactor float64
	// DailyEventCount is what an ordinary day - every multiplier at 1.0 - contains at scale 1.0.
	DailyEventCount int64
}

// Curve is the composed intensity function: one number of events per second of business time, for
// any minute of any business date, at any scale.
type Curve struct {
	spec     CurveSpec
	shapeSum float64
}

var (
	// ErrShape reports a diurnal curve that cannot be normalised.
	ErrShape = errors.New("bankday: the diurnal curve must have a positive hour in it")
	// ErrWeekday reports an incomplete set of weekday multipliers.
	ErrWeekday = errors.New("bankday: every day of the week needs a multiplier")
)

// NewCurve composes a spec into an intensity function, refusing one it cannot normalise.
func NewCurve(spec CurveSpec) (Curve, error) {
	sum := 0.0
	for hour, weight := range spec.Diurnal {
		if weight < 0 {
			return Curve{}, fmt.Errorf("bankday: hour %d has a negative weight %v", hour, weight)
		}
		sum += weight
	}
	if sum <= 0 {
		return Curve{}, ErrShape
	}
	for day := time.Sunday; day <= time.Saturday; day++ {
		if _, ok := spec.Weekday[day]; !ok {
			return Curve{}, fmt.Errorf("%w: %v is missing", ErrWeekday, day)
		}
	}
	if spec.DailyEventCount <= 0 {
		return Curve{}, errors.New("bankday: a day with no events in it is not a workload")
	}
	return Curve{spec: spec, shapeSum: sum}, nil
}

// PeakToTrough is the busiest hour of the diurnal shape divided by the quietest. Declared beside
// the curve in the model and asserted against it, because a flat rate with a nice name is what this
// exists not to be.
func (c Curve) PeakToTrough() float64 {
	high, low := c.spec.Diurnal[0], c.spec.Diurnal[0]
	for _, weight := range c.spec.Diurnal {
		if weight > high {
			high = weight
		}
		if weight < low {
			low = weight
		}
	}
	return high / low
}

// DayMultiplier is the whole-day factor for a business date: the weekday shape, times payday when
// the date is one, times month-end when it is within the declared band.
//
// The two compound rather than replacing each other, and there is a test for the case where they
// coincide. The 25th of a February is both, and it is the busiest ordinary day a Polish retail bank
// has - so a model where one silently overrode the other would understate its own peak.
func (c Curve) DayMultiplier(date Date) float64 {
	multiplier := c.spec.Weekday[date.Weekday()]
	for _, day := range c.spec.PaydayDays {
		if date.DayOfMonth() == day {
			multiplier *= c.spec.PaydayFactor
			break
		}
	}
	if c.spec.MonthEndDays > 0 && date.DaysFromMonthEnd() < c.spec.MonthEndDays {
		multiplier *= c.spec.MonthEndFactor
	}
	return multiplier
}

// DayTotal is how many events a business date contains at a given scale.
func (c Curve) DayTotal(date Date, scale float64) float64 {
	return float64(c.spec.DailyEventCount) * scale * c.DayMultiplier(date)
}

// RatePerSecond is the arrival intensity at one minute of a business date: events per second **of
// business time**. A driver running compressed multiplies by the compression factor to get the rate
// it must actually offer, which is why the manifest records both dials.
func (c Curve) RatePerSecond(date Date, minute Minute, scale float64) float64 {
	perHour := c.DayTotal(date, scale) * c.spec.Diurnal[minute.Hour()] / c.shapeSum
	return perHour / 3600
}

// PeakRatePerSecond is the highest business-time rate the date reaches.
func (c Curve) PeakRatePerSecond(date Date, scale float64) float64 {
	peak := 0.0
	for hour := 0; hour < 24; hour++ {
		minute := Minute(hour * 60)
		if rate := c.RatePerSecond(date, minute, scale); rate > peak {
			peak = rate
		}
	}
	return peak
}

// HourShape is the raw relative weight of a civil hour, for reporting.
func (c Curve) HourShape(hour int) float64 { return c.spec.Diurnal[hour] }
