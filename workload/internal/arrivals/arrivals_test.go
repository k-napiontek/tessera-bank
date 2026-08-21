package arrivals_test

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"strings"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/arrivals"
	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
)

// The committed model's shape, transcribed rather than read from the file being checked.
var shape = [24]float64{
	0.10, 0.06, 0.05, 0.05, 0.06, 0.10,
	0.22, 0.48, 0.85, 1.20, 1.40, 1.45,
	1.60, 1.50, 1.35, 1.30, 1.35, 1.50,
	1.45, 1.25, 0.90, 0.55, 0.30, 0.16,
}

func testCurve(t *testing.T) bankday.Curve {
	t.Helper()
	curve, err := bankday.NewCurve(bankday.CurveSpec{
		Diurnal: shape,
		Weekday: map[time.Weekday]float64{
			time.Monday: 1.15, time.Tuesday: 1.05, time.Wednesday: 1.00, time.Thursday: 1.02,
			time.Friday: 1.20, time.Saturday: 0.45, time.Sunday: 0.30,
		},
		PaydayDays: []int{10, 25}, PaydayFactor: 1.60,
		MonthEndDays: 2, MonthEndFactor: 1.35,
		DailyEventCount: 21_588_000,
	})
	if err != nil {
		t.Fatalf("NewCurve: %v", err)
	}
	return curve
}

// render writes a schedule exactly as a driver would read it, so that "byte-identical" is a claim
// about bytes rather than about a struct comparison.
func render(process arrivals.Process, seed uint64, limit int) string {
	var out strings.Builder
	count := 0
	for event := range process.Events(seed) {
		if count >= limit {
			break
		}
		fmt.Fprintf(&out, "%d\t%d\t%s\n", event.Seq, event.At.Nanoseconds(), event.Minute)
		count++
	}
	return out.String()
}

func digest(text string) string {
	sum := sha256.Sum256([]byte(text))
	return hex.EncodeToString(sum[:])
}

func TestTheSameSeedProducesAByteIdenticalSchedule(t *testing.T) {
	process, err := arrivals.New(testCurve(t), bankday.NewDate(2026, time.August, 31), 0.01)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	first := render(process, 42, 5000)
	second := render(process, 42, 5000)
	if first != second {
		t.Fatalf("two runs at seed 42 differ: %s vs %s", digest(first), digest(second))
	}
	if len(first) == 0 {
		t.Fatal("the schedule is empty")
	}
}

func TestADifferentSeedProducesADifferentSchedule(t *testing.T) {
	process, _ := arrivals.New(testCurve(t), bankday.NewDate(2026, time.August, 31), 0.01)
	if digest(render(process, 42, 5000)) == digest(render(process, 43, 5000)) {
		t.Fatal("seeds 42 and 43 produced the same schedule")
	}
}

func TestADifferentDayProducesADifferentSchedule(t *testing.T) {
	curve := testCurve(t)
	// A Saturday and a month-end Monday are different days; the same seed must not give the same
	// schedule, or the calendar is decoration.
	saturday, _ := arrivals.New(curve, bankday.NewDate(2026, time.August, 22), 0.01)
	monday, _ := arrivals.New(curve, bankday.NewDate(2026, time.August, 31), 0.01)
	if digest(render(saturday, 42, 5000)) == digest(render(monday, 42, 5000)) {
		t.Fatal("a Saturday and a month-end Monday produced the same schedule")
	}
}

func TestEveryIntendedSendTimeIsInsideTheDayAndInOrder(t *testing.T) {
	process, _ := arrivals.New(testCurve(t), bankday.NewDate(2026, time.August, 19), 0.001)
	previous := time.Duration(-1)
	var seq int64
	count := 0
	for event := range process.Events(7) {
		if event.At <= previous {
			t.Fatalf("event %d at %v does not follow %v", event.Seq, event.At, previous)
		}
		if event.At < 0 || event.At >= 24*time.Hour {
			t.Fatalf("event %d at %v is outside the business day", event.Seq, event.At)
		}
		if event.Seq != seq {
			t.Fatalf("event ordinal is %d, want %d", event.Seq, seq)
		}
		if want := bankday.Minute(event.At / time.Minute); event.Minute != want {
			t.Fatalf("event %d at %v reports minute %v, want %v", event.Seq, event.At, event.Minute, want)
		}
		previous, seq = event.At, seq+1
		count++
	}
	if count == 0 {
		t.Fatal("the day contained no events")
	}
}

func TestTheRealisedRateFollowsTheDeclaredIntensity(t *testing.T) {
	// The test that makes the curve real. Without it the engine could emit a flat rate and every
	// other test in this package would still pass.
	//
	// The tolerance is stated rather than tuned: a Poisson count of n has a standard deviation of
	// sqrt(n), so the quietest hour here - around 2,800 events - carries about 1.9% of sampling
	// noise on its own. 8% is four standard deviations at the thinnest point and far more at the
	// peak, which fails a systematically wrong curve and does not fail an honest one.
	const (
		scale     = 0.05
		tolerance = 0.08
	)
	curve := testCurve(t)
	date := bankday.NewDate(2026, time.August, 19) // a plain Wednesday: every multiplier at 1.0
	process, err := arrivals.New(curve, date, scale)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	var realised [24]int
	total := 0
	for event := range process.Events(20260819) {
		realised[event.Minute.Hour()]++
		total++
	}

	for hour := 0; hour < 24; hour++ {
		want := curve.RatePerSecond(date, bankday.Minute(hour*60), scale) * 3600
		got := float64(realised[hour])
		if relative := math.Abs(got-want) / want; relative > tolerance {
			t.Errorf("hour %02d: %d events, expected %.0f, off by %.2f%%", hour, realised[hour], want, relative*100)
		}
	}

	wantTotal := curve.DayTotal(date, scale)
	if relative := math.Abs(float64(total)-wantTotal) / wantTotal; relative > 0.02 {
		t.Errorf("the day produced %d events, expected %.0f, off by %.2f%%", total, wantTotal, relative*100)
	}
}

func TestThePeakToTroughSurvivesTheSampling(t *testing.T) {
	// The declared ratio is 32. A schedule that flattened it would still pass an hour-by-hour check
	// with a generous tolerance, so the ratio is asserted on the realised counts too.
	const scale = 0.05
	curve := testCurve(t)
	date := bankday.NewDate(2026, time.August, 19)
	process, _ := arrivals.New(curve, date, scale)

	var realised [24]int
	for event := range process.Events(1) {
		realised[event.Minute.Hour()]++
	}
	high, low := realised[0], realised[0]
	for _, count := range realised {
		if count > high {
			high = count
		}
		if count < low {
			low = count
		}
	}
	ratio := float64(high) / float64(low)
	if math.Abs(ratio-curve.PeakToTrough())/curve.PeakToTrough() > 0.08 {
		t.Errorf("the realised peak-to-trough is %.2f, and the curve declares %.2f", ratio, curve.PeakToTrough())
	}
}

func TestStoppingEarlyCostsNothingAndChangesNothing(t *testing.T) {
	// The schedule is a stream because a driver consumes it as it goes; a day at scale 1.0 is 21
	// million events and holding one in memory to send the first is the wrong shape. Breaking out
	// of the range must leave the prefix identical to a full run's prefix.
	process, _ := arrivals.New(testCurve(t), bankday.NewDate(2026, time.August, 19), 0.001)
	short := render(process, 5, 100)
	long := render(process, 5, 100000)
	if !strings.HasPrefix(long, short) {
		t.Fatal("a truncated run is not a prefix of a full one")
	}
}

func TestScaleMustBePositiveAndFinite(t *testing.T) {
	curve := testCurve(t)
	date := bankday.NewDate(2026, time.August, 19)
	for _, bad := range []float64{0, -1, math.NaN(), math.Inf(1)} {
		if _, err := arrivals.New(curve, date, bad); err == nil {
			t.Errorf("New(..., %v) was accepted", bad)
		}
	}
}
