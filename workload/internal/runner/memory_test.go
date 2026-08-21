package runner

// The run's memory of what it created, tested from inside the package: it is not part of the
// driver's surface, and it decides whether a dependent operation names something real.

import (
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
)

func TestAHoldIsHandedToOneEventOnly(t *testing.T) {
	// A hold can be captured or released once. Handing the same one to two events would produce a
	// conflict this driver invented, and it would land in the rejected column as though the ledger
	// had refused something a customer did.
	held := newMemory()
	held.rememberHold(client.Hold{Ref: "HL202608310000000001", Amount: money.Amount{Minor: 100, Currency: "PLN"}})

	if _, found := held.Hold(); !found {
		t.Fatal("the hold that was just placed is not there")
	}
	if _, found := held.Hold(); found {
		t.Error("the same hold was handed out twice")
	}
}

func TestATransferCanBeReadRepeatedlyAndReversedOnce(t *testing.T) {
	held := newMemory()
	held.rememberTransfer(client.Transfer{Ref: "TB202608310000000001"})

	for read := 0; read < 5; read++ {
		if _, found := held.Transfer(); !found {
			t.Fatalf("read %d found no transfer", read)
		}
	}
	if _, found := held.TakeTransfer(); !found {
		t.Fatal("nothing to reverse")
	}
	if _, found := held.TakeTransfer(); found {
		t.Error("the same transfer was reversed twice, which the ledger refuses and the driver caused")
	}
}

func TestNothingIsHandedOutBeforeTheRunHasCreatedAnything(t *testing.T) {
	held := newMemory()
	if _, found := held.Transfer(); found {
		t.Error("invented a transfer")
	}
	if _, found := held.Hold(); found {
		t.Error("invented a hold")
	}
}

func TestTheMemoryIsBoundedSoALongRunDoesNotHoldTheWholeDay(t *testing.T) {
	// A million-event run that remembered every transfer would spend its second half reading its
	// first, and the memory would be the largest thing in the process.
	held := newMemory()
	for i := 0; i < remembered*4; i++ {
		held.rememberTransfer(client.Transfer{Ref: "TB20260831000000000" + string(rune('0'+i%10))})
	}
	if len(held.transfers) > remembered {
		t.Errorf("the run is holding %d transfers", len(held.transfers))
	}
	if _, found := held.Transfer(); !found {
		t.Error("a full buffer stopped answering")
	}
}

func TestReadsMoveAroundTheBufferRatherThanRepeatingOne(t *testing.T) {
	// A run that read one transfer a thousand times would be measuring a row the ledger has cached
	// rather than the query a customer makes.
	held := newMemory()
	held.rememberTransfer(client.Transfer{Ref: "TB202608310000000001"})
	held.rememberTransfer(client.Transfer{Ref: "TB202608310000000002"})
	held.rememberTransfer(client.Transfer{Ref: "TB202608310000000003"})

	seen := map[string]bool{}
	for read := 0; read < 9; read++ {
		transfer, found := held.Transfer()
		if !found {
			t.Fatal("no transfer")
		}
		seen[transfer.Ref] = true
	}
	if len(seen) != 3 {
		t.Errorf("nine reads touched %d of the three transfers held", len(seen))
	}
}
