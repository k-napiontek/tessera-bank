package soap

import (
	"bytes"
	"encoding/xml"
	"strconv"
)

// Transfer is the canonical Transfer, as the WSDL's NotifyTransferPosted carries it.
//
// Money is an int64 of minor units and a currency code, never a decimal and never a float, which is
// `CLAUDE.md`'s rule and the canonical model's own: an amount without its currency is meaningless
// and two amounts are comparable only when their currencies are equal.
type Transfer struct {
	TransferRef      string
	DebitAccountRef  string
	CreditAccountRef string
	AmountMinor      int64
	Currency         string
	Status           string
	Reference        string
	RequestedAt      string
	PostedAt         string
	CorrelationID    string
}

// Movement is one leg of the posting, and NotifyTransferPosted carries exactly two.
type Movement struct {
	MovementRef string
	TransferRef string
	LegNo       int
	AccountRef  string
	Direction   string
	AmountMinor int64
	Currency    string
	ValueDate   string
	PostedAt    string
	Reference   string
}

// GetAccountRequest asks for one account's metadata.
func GetAccountRequest(accountRef string) []byte {
	var body bytes.Buffer
	body.WriteString("    <v1:GetAccount>\n")
	element(&body, "v1", "accountRef", accountRef, 6)
	body.WriteString("    </v1:GetAccount>\n")
	return envelope(body.Bytes())
}

// GetAccountsByCustomerRequest asks for every account one customer holds. An empty list is a valid
// answer rather than a fault, which the WSDL says in as many words.
func GetAccountsByCustomerRequest(customerRef string) []byte {
	var body bytes.Buffer
	body.WriteString("    <v1:GetAccountsByCustomer>\n")
	element(&body, "v1", "customerRef", customerRef, 6)
	body.WriteString("    </v1:GetAccountsByCustomer>\n")
	return envelope(body.Bytes())
}

// NotifyTransferPostedRequest is the write path: the operation integration/esb-adapter calls after a
// transfer posts in the modern ledger. The schema requires exactly two movements, so the signature
// takes exactly two rather than a slice - a slice would let the wrong count reach the wire and be
// refused by a schema validator with a message about cardinality.
func NotifyTransferPostedRequest(transfer Transfer, debit, credit Movement) []byte {
	var body bytes.Buffer
	body.WriteString("    <v1:NotifyTransferPosted>\n")

	body.WriteString("      <v1:transfer>\n")
	element(&body, "tb", "transferRef", transfer.TransferRef, 8)
	element(&body, "tb", "debitAccountRef", transfer.DebitAccountRef, 8)
	element(&body, "tb", "creditAccountRef", transfer.CreditAccountRef, 8)
	money(&body, "amount", transfer.AmountMinor, transfer.Currency, 8)
	element(&body, "tb", "status", transfer.Status, 8)
	optional(&body, "tb", "reference", transfer.Reference, 8)
	element(&body, "tb", "requestedAt", transfer.RequestedAt, 8)
	optional(&body, "tb", "postedAt", transfer.PostedAt, 8)
	element(&body, "tb", "correlationId", transfer.CorrelationID, 8)
	body.WriteString("      </v1:transfer>\n")

	for _, leg := range []Movement{debit, credit} {
		body.WriteString("      <v1:movement>\n")
		element(&body, "tb", "movementRef", leg.MovementRef, 8)
		element(&body, "tb", "transferRef", leg.TransferRef, 8)
		element(&body, "tb", "legNo", strconv.Itoa(leg.LegNo), 8)
		element(&body, "tb", "accountRef", leg.AccountRef, 8)
		element(&body, "tb", "direction", leg.Direction, 8)
		money(&body, "amount", leg.AmountMinor, leg.Currency, 8)
		element(&body, "tb", "valueDate", leg.ValueDate, 8)
		element(&body, "tb", "postedAt", leg.PostedAt, 8)
		optional(&body, "tb", "reference", leg.Reference, 8)
		body.WriteString("      </v1:movement>\n")
	}

	body.WriteString("    </v1:NotifyTransferPosted>\n")
	return envelope(body.Bytes())
}

// envelope wraps an operation element in the SOAP 1.1 envelope, declaring both namespaces once at
// the top the way a generated client does.
func envelope(body []byte) []byte {
	var out bytes.Buffer
	out.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	out.WriteString(`<soapenv:Envelope xmlns:soapenv="` + envelopeNS + `"` + "\n")
	out.WriteString(`                  xmlns:v1="` + serviceNS + `"` + "\n")
	out.WriteString(`                  xmlns:tb="` + canonicalNS + `">` + "\n")
	out.WriteString("  <soapenv:Body>\n")
	out.Write(body)
	out.WriteString("  </soapenv:Body>\n")
	out.WriteString("</soapenv:Envelope>\n")
	return out.Bytes()
}

// element writes one leaf, escaped. Every value that reaches here came from a drawn population and
// none of them contains a metacharacter today - which is exactly why interpolating them raw would
// go unnoticed until one did.
func element(out *bytes.Buffer, prefix, name, value string, indent int) {
	out.WriteString(spaces(indent))
	out.WriteString("<" + prefix + ":" + name + ">")
	_ = xml.EscapeText(out, []byte(value))
	out.WriteString("</" + prefix + ":" + name + ">\n")
}

// optional omits the element entirely when the value is empty. minOccurs="0" means absent, and an
// empty element is a different document - one the schema rejects for the types with a pattern.
func optional(out *bytes.Buffer, prefix, name, value string, indent int) {
	if value == "" {
		return
	}
	element(out, prefix, name, value, indent)
}

func money(out *bytes.Buffer, name string, amountMinor int64, currency string, indent int) {
	out.WriteString(spaces(indent) + "<tb:" + name + ">\n")
	element(out, "tb", "amountMinor", strconv.FormatInt(amountMinor, 10), indent+2)
	element(out, "tb", "currency", currency, indent+2)
	out.WriteString(spaces(indent) + "</tb:" + name + ">\n")
}

func spaces(n int) string {
	return "                                "[:n]
}
