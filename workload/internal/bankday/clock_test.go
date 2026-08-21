package bankday

import (
	"testing"
	"time"
)

func TestMinuteOfRejectsWhatIsNotATimeOfDay(t *testing.T) {
	if _, err := MinuteOf(24, 0); err == nil {
		t.Error("MinuteOf(24, 0) was accepted")
	}
	if _, err := MinuteOf(-1, 0); err == nil {
		t.Error("MinuteOf(-1, 0) was accepted")
	}
	if _, err := MinuteOf(12, 60); err == nil {
		t.Error("MinuteOf(12, 60) was accepted")
	}
	got, err := MinuteOf(20, 0)
	if err != nil || got != 1200 {
		t.Errorf("MinuteOf(20, 0) = %d, %v; want 1200, nil", got, err)
	}
}

func TestMinuteReportsItsHour(t *testing.T) {
	for _, c := range []struct {
		minute Minute
		hour   int
	}{{0, 0}, {59, 0}, {60, 1}, {1200, 20}, {1439, 23}} {
		if got := c.minute.Hour(); got != c.hour {
			t.Errorf("Minute(%d).Hour() = %d, want %d", c.minute, got, c.hour)
		}
	}
}

func TestAnOrdinaryWindowContainsItsOwnSpan(t *testing.T) {
	branch := Window{ID: "branch-hours", Start: 540, End: 1080}
	if branch.WrapsMidnight() {
		t.Error("branch hours were read as wrapping midnight")
	}
	for _, c := range []struct {
		minute Minute
		want   bool
	}{{539, false}, {540, true}, {1079, true}, {1080, false}, {0, false}} {
		if got := branch.Contains(c.minute); got != c.want {
			t.Errorf("branch-hours.Contains(%d) = %v, want %v", c.minute, got, c.want)
		}
	}
}

func TestAWrappingWindowIsTheOneThatSpansTheNight(t *testing.T) {
	// The overnight batch starts at 20:30 and ends at 05:00. Written the obvious way - start <= m
	// && m < end - it contains nothing at all, and a driver would report that the estate spends no
	// time in its batch window. Nothing else would go wrong, which is what makes it worth a test.
	batch := Window{ID: "overnight-batch", Start: 1230, End: 300}
	if !batch.WrapsMidnight() {
		t.Fatal("the overnight batch was not read as wrapping midnight")
	}
	for _, c := range []struct {
		minute Minute
		want   bool
	}{{1229, false}, {1230, true}, {1439, true}, {0, true}, {299, true}, {300, false}, {720, false}} {
		if got := batch.Contains(c.minute); got != c.want {
			t.Errorf("overnight-batch.Contains(%d) = %v, want %v", c.minute, got, c.want)
		}
	}
}

func TestWindowLengthCountsAcrossMidnight(t *testing.T) {
	if got := (Window{Start: 540, End: 1080}).Length(); got != 540 {
		t.Errorf("branch hours are %d minutes, want 540", got)
	}
	if got := (Window{Start: 1230, End: 300}).Length(); got != 510 {
		t.Errorf("the overnight batch is %d minutes, want 510", got)
	}
}

func TestABusinessDateKnowsWhereItSitsInTheWeekAndTheMonth(t *testing.T) {
	// 2026-08-31 is a Monday and the last day of a 31-day month.
	date := NewDate(2026, time.August, 31)
	if got := date.Weekday(); got != time.Monday {
		t.Errorf("2026-08-31 is a %v, want Monday", got)
	}
	if got := date.DayOfMonth(); got != 31 {
		t.Errorf("DayOfMonth() = %d, want 31", got)
	}
	if got := date.DaysFromMonthEnd(); got != 0 {
		t.Errorf("DaysFromMonthEnd() = %d, want 0", got)
	}
	if got := NewDate(2026, time.August, 30).DaysFromMonthEnd(); got != 1 {
		t.Errorf("2026-08-30 is %d days from month end, want 1", got)
	}
	// February in a leap year, because a month length hard-coded at 30 or 31 passes eleven months.
	if got := NewDate(2028, time.February, 29).DaysFromMonthEnd(); got != 0 {
		t.Errorf("2028-02-29 is %d days from month end, want 0", got)
	}
	if got := NewDate(2027, time.February, 28).DaysFromMonthEnd(); got != 0 {
		t.Errorf("2027-02-28 is %d days from month end, want 0", got)
	}
}

func TestParseDateTakesAnIsoDateAndRefusesAnythingElse(t *testing.T) {
	date, err := ParseDate("2026-08-31")
	if err != nil {
		t.Fatalf("ParseDate: %v", err)
	}
	if date.String() != "2026-08-31" {
		t.Errorf("round trip gave %q", date.String())
	}
	for _, bad := range []string{"31-08-2026", "2026-8-31", "2026-13-01", "", "today"} {
		if _, err := ParseDate(bad); err == nil {
			t.Errorf("ParseDate(%q) was accepted", bad)
		}
	}
}

func TestTheClockCompressesADayWithoutTheModelChanging(t *testing.T) {
	// The whole point of the dial: 24 hours of business watched in twenty minutes.
	clock, err := NewClock(72)
	if err != nil {
		t.Fatalf("NewClock(72): %v", err)
	}
	if got := clock.RealDayLength(); got != 20*time.Minute {
		t.Errorf("a day at 72x runs for %v, want 20m", got)
	}
	if got := clock.Virtual(time.Minute); got != 72*time.Minute {
		t.Errorf("one real minute is %v of business, want 1h12m", got)
	}
	if got := clock.Real(72 * time.Minute); got != time.Minute {
		t.Errorf("72 minutes of business takes %v, want 1m", got)
	}
}

func TestAClockAtOneIsRealTime(t *testing.T) {
	clock, err := NewClock(1)
	if err != nil {
		t.Fatalf("NewClock(1): %v", err)
	}
	if got := clock.RealDayLength(); got != 24*time.Hour {
		t.Errorf("an uncompressed day runs for %v, want 24h", got)
	}
	if got := clock.Real(time.Hour); got != time.Hour {
		t.Errorf("Real(1h) = %v, want 1h", got)
	}
}

func TestTheClockRefusesACompressionThatIsNotOne(t *testing.T) {
	for _, bad := range []int{0, -1, -72, 100000} {
		if _, err := NewClock(bad); err == nil {
			t.Errorf("NewClock(%d) was accepted", bad)
		}
	}
}

func TestVirtualTimeOfDayWrapsAtMidnight(t *testing.T) {
	clock, _ := NewClock(72)
	// Ten real minutes at 72x is twelve business hours.
	if got := clock.MinuteAt(0, 10*time.Minute); got != 720 {
		t.Errorf("MinuteAt(00:00, 10m real) = %d, want 720", got)
	}
	// Starting at 20:00, twelve business hours later is 08:00 the next morning.
	if got := clock.MinuteAt(1200, 10*time.Minute); got != 480 {
		t.Errorf("MinuteAt(20:00, 10m real) = %d, want 480", got)
	}
}

func TestADateStepsByCalendarDaysAndNotByHours(t *testing.T) {
	// The last Sunday in March 2026 is when most of Europe moves its clocks. A step written as
	// +24h would land on the 29th at 23:00 or on the 30th at 01:00 depending on the location; a
	// calendar step lands on the 30th everywhere.
	start := NewDate(2026, time.March, 29)
	if got := start.AddDays(1).String(); got != "2026-03-30" {
		t.Fatalf("a day after 2026-03-29 is %s, want 2026-03-30", got)
	}
	if got := NewDate(2026, time.February, 28).AddDays(1).String(); got != "2026-03-01" {
		t.Fatalf("2026 is not a leap year, so a day after 28 February is %s, want 2026-03-01", got)
	}
	if got := NewDate(2024, time.February, 28).AddDays(1).String(); got != "2024-02-29" {
		t.Fatalf("2024 is a leap year, so a day after 28 February is %s, want 2024-02-29", got)
	}
	if got := start.AddDays(-29).String(); got != "2026-02-28" {
		t.Fatalf("29 days before 2026-03-29 is %s, want 2026-02-28", got)
	}
	if got := start.AddDays(0); got != start {
		t.Fatalf("stepping by no days moved %s to %s", start, got)
	}
}

func TestADateKnowsWhichSideOfAnotherItFallsOn(t *testing.T) {
	earlier := NewDate(2026, time.August, 20)
	later := NewDate(2026, time.August, 21)

	if !earlier.Before(later) {
		t.Fatal("20 August does not fall before 21 August")
	}
	if later.Before(earlier) {
		t.Fatal("21 August falls before 20 August")
	}
	// A range that runs from a date to itself is one day long, not zero, so the comparison a caller
	// loops on has to be exclusive rather than inclusive.
	if earlier.Before(earlier) {
		t.Fatal("a date falls before itself")
	}
}
