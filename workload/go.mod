module github.com/k-napiontek/tessera-bank/workload

// Follows edge/api-gateway/go.mod, per the decision log: the tier follows what its justified
// dependencies require. This module has none - standard library only - so it simply matches the
// other Go module in the estate rather than drifting to whatever the machine happens to have.
go 1.25.0
