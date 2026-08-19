// Package problem writes the RFC 9457 documents the gateway refuses a request with.
//
// The shape matches the one services/ledger-api emits, field for field and namespace for namespace,
// because a client sees both and should not need to know which tier answered. The type URI is the
// part a client is allowed to branch on; title and detail may be reworded freely.
//
// A detail string is written for a human and read by an attacker. Nothing here explains which of a
// token's claims failed, whether an account exists, or what the ledger said - the log line carries
// that, and the log line is not served to the caller.
package problem

import (
	"encoding/json"
	"net/http"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
)

// The namespace is the estate's, not the gateway's. One vocabulary of problem types spans the
// tiers, and a client matching on a prefix matches both.
const namespace = "https://problems.tesserabank.example/"

// Type is a problem kind: the stable URI, and the title that goes with it.
type Type struct {
	slug  string
	title string
}

// URI is the machine-readable identifier a client branches on.
func (t Type) URI() string { return namespace + t.slug }

// Title is the human-readable summary.
func (t Type) Title() string { return t.title }

// The kinds the edge can produce. Business failures belong to the ledger and are relayed from it
// untouched; nothing here duplicates one.
var (
	Unauthenticated   = Type{"unauthenticated", "Authentication required"}
	Forbidden         = Type{"forbidden", "Not permitted"}
	RateLimited       = Type{"rate-limited", "Too many requests"}
	PayloadTooLarge   = Type{"payload-too-large", "Request body is too large"}
	NoRoute           = Type{"no-route", "No such route"}
	UpstreamTimeout   = Type{"upstream-timeout", "The ledger did not answer in time"}
	UpstreamUnusable  = Type{"upstream-unusable", "The ledger could not be reached"}
	UpstreamOversized = Type{"upstream-oversized", "The ledger's response is too large to relay"}
)

// document is the wire form, matching the Problem schema in contracts/openapi/ledger-core.yaml.
type document struct {
	Type          string `json:"type"`
	Title         string `json:"title"`
	Status        int    `json:"status"`
	Detail        string `json:"detail,omitempty"`
	Instance      string `json:"instance,omitempty"`
	CorrelationID string `json:"correlationId,omitempty"`
}

// Write replaces whatever was going to be sent with a problem document.
func Write(w http.ResponseWriter, r *http.Request, status int, kind Type, detail string) {
	body, err := json.Marshal(document{
		Type:     kind.URI(),
		Title:    kind.Title(),
		Status:   status,
		Detail:   detail,
		Instance: r.URL.Path,
		// The resolved id, so a request that arrived without one is still traceable and a caller
		// who sent something that was not a UUID does not get it echoed back at them.
		CorrelationID: correlation.FromContext(r.Context()),
	})
	if err != nil {
		// A struct of strings and an int cannot fail to marshal; if it somehow does, an empty 500
		// is still better than a half-written body.
		http.Error(w, "", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/problem+json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}
