package hardware_test

import (
	"runtime"
	"strconv"
	"strings"
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/hardware"
)

func TestItNamesTheFourThingsThatMakeTwoMeasurementsComparable(t *testing.T) {
	described := hardware.Describe()
	for _, wanted := range []string{
		runtime.GOOS,
		runtime.GOARCH,
		strconv.Itoa(runtime.NumCPU()) + " cores",
		runtime.Version(),
	} {
		if !strings.Contains(described, wanted) {
			t.Errorf("%q does not name %q", described, wanted)
		}
	}
}

func TestItSaysTheSameThingTwice(t *testing.T) {
	// A conditions block is for what makes two measurements comparable. A field that changed while
	// you were reading it - load, free memory, thermal state - would make one machine look like two
	// in a diff between two committed baselines, which is the mistake this whole strand is careful
	// about in the other direction.
	if first, second := hardware.Describe(), hardware.Describe(); first != second {
		t.Errorf("two readings of one machine: %q then %q", first, second)
	}
}
