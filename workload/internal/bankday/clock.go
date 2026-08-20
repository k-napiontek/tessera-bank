// Package bankday is the calendar and the clock of Tessera Bank's business day.
//
// Everything here is pure arithmetic. Nothing in this package reads the wall clock, opens a file or
// resolves a timezone, and that is what makes a run reproducible: the same business date and the
// same compression give the same schedule on any machine, in any month, at any hour of the night.
//
// Two conventions worth knowing before reading further.
//
// Every time in the workload model is **local civil time at the bank**. There is no timezone in the
// model and none here. Mapping a minute of the business day onto an instant is the driver's job,
// because the two drivers map it differently - WP-21 sends HTTP now, WP-25 feeds a batch window.
// time.UTC appears below purely as a calendar with no daylight saving in it, never as a location.
//
// A window may wrap midnight, and the overnight batch does. Written the obvious way it would
// contain no minute at all, and the only symptom would be a driver reporting that the estate spends
// no time in its batch window - a plausible-looking figure that is simply wrong, which is this
// repository's most-repeated failure mode.
package bankday

import (
	"errors"
	"fmt"
	"time"
)

// MinutesPerDay is the modulus everything here works in.
const MinutesPerDay = 24 * 60

// maxCompression is a guard rather than a physical limit. Above this a "day" is over in under a
// second, which measures a burst rather than a day, and the manifest would record a figure nobody
// could defend.
const maxCompression = 8640

// Minute is a minute of the business day, 0 for 00:00 through 1439 for 23:59.
type Minute int

// MinuteOf builds a Minute from a civil hour and minute.
func MinuteOf(hour, minute int) (Minute, error) {
	if hour < 0 || hour > 23 || minute < 0 || minute > 59 {
		return 0, fmt.Errorf("bankday: %02d:%02d is not a time of day", hour, minute)
	}
	return Minute(hour*60 + minute), nil
}

// Hour is the civil hour this minute falls in, which is the index into the diurnal curve.
func (m Minute) Hour() int { return int(m) / 60 }

// String renders the civil time, zero-padded.
func (m Minute) String() string { return fmt.Sprintf("%02d:%02d", int(m)/60, int(m)%60) }

// Window is a named span of the business day. End is exclusive. A window whose End is not after its
// Start wraps midnight.
type Window struct {
	ID      string
	Start   Minute
	End     Minute
	Purpose string
}

// WrapsMidnight reports whether the window runs through 00:00 into the following day.
func (w Window) WrapsMidnight() bool { return w.End <= w.Start }

// Contains reports whether a minute of the day falls inside the window.
func (w Window) Contains(m Minute) bool {
	if w.WrapsMidnight() {
		return m >= w.Start || m < w.End
	}
	return m >= w.Start && m < w.End
}

// Length is the window's duration in minutes, counted across midnight when it wraps.
func (w Window) Length() int {
	if w.WrapsMidnight() {
		return MinutesPerDay - int(w.Start) + int(w.End)
	}
	return int(w.End - w.Start)
}

// Instant is a named moment of the business day rather than a span.
type Instant struct {
	ID      string
	At      Minute
	Purpose string
}

// Date is a business date. It carries no time and no location: it answers where a day sits in the
// week and in the month, which is all the calendar multipliers need.
type Date struct{ t time.Time }

// NewDate builds a business date. See the package comment on time.UTC.
func NewDate(year int, month time.Month, day int) Date {
	return Date{t: time.Date(year, month, day, 0, 0, 0, 0, time.UTC)}
}

// ParseDate reads an ISO 8601 calendar date and refuses anything else, including a date that looks
// well-formed and does not exist.
func ParseDate(text string) (Date, error) {
	parsed, err := time.ParseInLocation("2006-01-02", text, time.UTC)
	if err != nil {
		return Date{}, fmt.Errorf("bankday: %q is not an ISO business date (YYYY-MM-DD)", text)
	}
	return Date{t: parsed}, nil
}

// Weekday is the day of week, which selects the weekday multiplier.
func (d Date) Weekday() time.Weekday { return d.t.Weekday() }

// DayOfMonth is the calendar day, which is what a payday date is compared against.
func (d Date) DayOfMonth() int { return d.t.Day() }

// DaysFromMonthEnd counts back from the last day of the month: 0 on the last day, 1 the day before.
//
// Derived rather than tabulated, so February in a leap year is right for the same reason every
// other month is. A table of month lengths gets eleven months correct and is wrong once every four
// years, in the one month a bank's month-end matters most.
func (d Date) DaysFromMonthEnd() int {
	firstOfNextMonth := time.Date(d.t.Year(), d.t.Month(), 1, 0, 0, 0, 0, time.UTC).AddDate(0, 1, 0)
	lastOfThisMonth := firstOfNextMonth.AddDate(0, 0, -1)
	return int(lastOfThisMonth.Sub(d.t).Hours()) / 24
}

// String renders the ISO date.
func (d Date) String() string { return d.t.Format("2006-01-02") }

// Clock converts between real elapsed time and business time.
//
// Compression is a separate dial from scale, and this is where the difference lives: compression
// makes the same day happen faster, so it multiplies the offered rate. Scale changes how much
// demand the day contains. The run manifest records both, because a throughput figure means nothing
// without them.
type Clock struct{ compression int }

// ErrCompression reports a compression factor that is not a whole speed-up.
var ErrCompression = errors.New("bankday: compression must be between 1 and 8640")

// NewClock builds a clock at the given compression. 1 is real time; 72 runs a day in twenty minutes.
func NewClock(compression int) (Clock, error) {
	if compression < 1 || compression > maxCompression {
		return Clock{}, ErrCompression
	}
	return Clock{compression: compression}, nil
}

// Compression is the factor this clock runs at.
func (c Clock) Compression() int { return c.compression }

// RealDayLength is how long a whole business day takes to run at this compression.
func (c Clock) RealDayLength() time.Duration {
	return 24 * time.Hour / time.Duration(c.compression)
}

// Virtual converts elapsed real time into elapsed business time.
func (c Clock) Virtual(real time.Duration) time.Duration {
	return real * time.Duration(c.compression)
}

// Real converts elapsed business time into the real time it takes to run.
func (c Clock) Real(virtual time.Duration) time.Duration {
	return virtual / time.Duration(c.compression)
}

// MinuteAt is the business minute reached after running for `real` from `start`, wrapping at
// midnight. A compressed day can run past midnight into the next business day's early hours, which
// is exactly what the overnight window is for.
func (c Clock) MinuteAt(start Minute, real time.Duration) Minute {
	elapsed := int(c.Virtual(real) / time.Minute)
	return Minute(((int(start)+elapsed)%MinutesPerDay + MinutesPerDay) % MinutesPerDay)
}
