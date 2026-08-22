// Package hardware says what a measurement was taken on.
//
// One line, and it exists as a package because there were about to be two of them. `ceiling.sh`
// computed it in shell with uname and sysctl; WP-24a needed the same string in a run manifest, and
// the Go answer would have been `darwin/arm64` where the shell one said `Darwin arm64` - the same
// laptop, described two ways, in two committed measurements somebody would later put side by side
// and read as two machines. F-61, F-64 and F-66 each record what the second copy costs.
//
// It is read from the Go runtime rather than from uname, so it needs no subprocess and works
// wherever the toolchain does. What it deliberately does not report is anything that varies between
// two runs on one machine - load, free memory, thermal state. A conditions block is for what makes
// two measurements comparable, and a field that changes while you are reading it makes them look
// different when they are not.
package hardware

import (
	"fmt"
	"runtime"
)

// Describe names the operating system, the architecture, the core count and the toolchain.
func Describe() string {
	return fmt.Sprintf("%s %s, %d cores, %s",
		runtime.GOOS, runtime.GOARCH, runtime.NumCPU(), runtime.Version())
}
