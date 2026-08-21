package bankday

import (
	"math"
	"testing"
	"time"
)

// The committed model's shape, transcribed. A test that read the curve out of the file it is
// checking would agree with any curve, including a flat one.
var shape = [24]float64{
	0.10, 0.06, 0.05, 0.05, 0.06, 0.10,
	0.22, 0.48, 0.85, 1.20, 1.40, 1.45,
	1.60, 1.50, 1.35, 1.30, 1.35, 1.50,
	1.45, 1.25, 0.90, 0.55, 0.30, 0.16,
}

var weekday = map[time.Weekday]float64{
	time.Monday: 1.15, time.Tuesday: 1.05, time.Wednesday: 1.00, time.Thursday: 1.02,
	time.Friday: 1.20, time.Saturday: 0.45, time.Sunday: 0.30,
}

func testCurve(t *testing.T) Curve {
	t.Helper()
	curve, err := NewCurve(CurveSpec{
		Diurnal:         shape,
		Weekday:         weekday,
		PaydayDays:      []int{10, 25},
		PaydayFactor:    1.60,
		MonthEndDays:    2,
		MonthEndFactor:  1.35,
		DailyEventCount: 21_588_000,
	})
	if err != nil {
		t.Fatalf("NewCurve: %v", err)
	}
	return curve
}

func TestTheCurveReportsTheDeclaredPeakToTrough(t *testing.T) {
	if got := testCurve(t).PeakToTrough(); math.Abs(got-32.0) > 1e-9 {
		t.Errorf("PeakToTrough() = %v, want 32", got)
	}
}

func TestADayIntegratesToTheVolumeTheModelDeclares(t *testing.T) {
	// The curve is a shape, not a rate. Whatever its hours add to, a plain Wednesday at scale 1.0
	// must produce exactly the daily volume the model states - otherwise the model's headline
	// figure and the load a driver offers are two different numbers that look like one.
	curve := testCurve(t)
	wednesday := NewDate(2026, time.August, 19)
	if wednesday.Weekday() != time.Wednesday {
		t.Fatalf("2026-08-19 is a %v", wednesday.Weekday())
	}
	got := curve.DayTotal(wednesday, 1.0)
	if math.Abs(got-21_588_000) > 1e-6 {
		t.Errorf("a plain Wednesday totals %.6f events, want 21588000", got)
	}
}

func TestIntegratingTheRateOverTheDayGivesTheSameTotal(t *testing.T) {
	// DayTotal is arithmetic on the multiplier; this walks all 1440 minutes and adds up what a
	// driver would actually be told to send. The two must agree, or the headline figure is a claim
	// about a curve nobody is executing.
	curve := testCurve(t)
	friday := NewDate(2026, time.August, 21)
	total := 0.0
	for m := Minute(0); m < MinutesPerDay; m++ {
		total += curve.RatePerSecond(friday, m, 1.0) * 60
	}
	want := curve.DayTotal(friday, 1.0)
	if math.Abs(total-want)/want > 1e-9 {
		t.Errorf("minute-by-minute total %.4f, DayTotal %.4f", total, want)
	}
}

func TestScaleIsLinearAndSeparateFromEverythingElse(t *testing.T) {
	curve := testCurve(t)
	day := NewDate(2026, time.August, 19)
	full := curve.DayTotal(day, 1.0)
	tenth := curve.DayTotal(day, 0.1)
	if math.Abs(tenth*10-full)/full > 1e-9 {
		t.Errorf("scale 0.1 gives %.4f, and a tenth of full is %.4f", tenth, full/10)
	}
}

func TestASaturdayIsNotATuesdayWithFewerPeopleAwake(t *testing.T) {
	curve := testCurve(t)
	tuesday := curve.DayMultiplier(NewDate(2026, time.August, 18))
	saturday := curve.DayMultiplier(NewDate(2026, time.August, 22))
	if saturday >= tuesday {
		t.Errorf("Saturday multiplier %v is not below Tuesday's %v", saturday, tuesday)
	}
	if math.Abs(saturday-0.45) > 1e-9 {
		t.Errorf("Saturday multiplier = %v, want 0.45", saturday)
	}
}

func TestPaydayAndMonthEndCompoundWhenTheyCoincide(t *testing.T) {
	curve := testCurve(t)

	// 2026-08-25 is a payday and not within two days of the month end.
	payday := curve.DayMultiplier(NewDate(2026, time.August, 25))
	tuesday := weekday[time.Tuesday]
	if math.Abs(payday-tuesday*1.60) > 1e-9 {
		t.Errorf("a payday Tuesday = %v, want %v", payday, tuesday*1.60)
	}

	// 2026-08-31 is the last day of the month and a Monday, and not a payday.
	monthEnd := curve.DayMultiplier(NewDate(2026, time.August, 31))
	monday := weekday[time.Monday]
	if math.Abs(monthEnd-monday*1.35) > 1e-9 {
		t.Errorf("a month-end Monday = %v, want %v", monthEnd, monday*1.35)
	}

	// The committed model's paydays are the 10th and the 25th and its band is the last two days, so
	// the two never coincide - the shortest month is 28 days. The compounding still has to be right,
	// because the model is a contract other models are written against, so it is proved on a spec
	// where they do collide: a payday on the 28th, in February.
	collides, err := NewCurve(CurveSpec{
		Diurnal: shape, Weekday: weekday,
		PaydayDays: []int{28}, PaydayFactor: 1.60,
		MonthEndDays: 2, MonthEndFactor: 1.35,
		DailyEventCount: 1000,
	})
	if err != nil {
		t.Fatalf("NewCurve: %v", err)
	}
	leapish := NewDate(2026, time.February, 28)
	if leapish.DaysFromMonthEnd() != 0 || leapish.DayOfMonth() != 28 {
		t.Fatal("2026-02-28 is not the last day of February - the fixture is wrong")
	}
	both := collides.DayMultiplier(leapish)
	saturday := weekday[time.Saturday]
	if math.Abs(both-saturday*1.60*1.35) > 1e-9 {
		t.Errorf("a payday on the last day of February = %v, want %v", both, saturday*1.60*1.35)
	}
}

func TestTheBusiestMinuteIsInTheBusiestHour(t *testing.T) {
	curve := testCurve(t)
	day := NewDate(2026, time.August, 19)
	best, at := 0.0, Minute(0)
	for m := Minute(0); m < MinutesPerDay; m++ {
		if rate := curve.RatePerSecond(day, m, 1.0); rate > best {
			best, at = rate, m
		}
	}
	if at.Hour() != 12 {
		t.Errorf("the busiest minute is at %v, and the curve peaks at hour 12", at)
	}
}

func TestTheCurveRefusesAShapeThatIsNotOne(t *testing.T) {
	flat := shape
	for i := range flat {
		flat[i] = 0
	}
	if _, err := NewCurve(CurveSpec{Diurnal: flat, Weekday: weekday, DailyEventCount: 1}); err == nil {
		t.Error("a curve of zeroes was accepted, and it would divide by zero on the first minute")
	}

	if _, err := NewCurve(CurveSpec{Diurnal: shape, Weekday: nil, DailyEventCount: 1}); err == nil {
		t.Error("a curve with no weekday multipliers was accepted")
	}

	short := weekday
	short = map[time.Weekday]float64{time.Monday: 1}
	if _, err := NewCurve(CurveSpec{Diurnal: shape, Weekday: short, DailyEventCount: 1}); err == nil {
		t.Error("a curve missing six days of the week was accepted")
	}
}
