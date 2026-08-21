package slo

import (
	"bytes"
	"io"
)

// newReader exists so that Decode takes bytes rather than an io.Reader: the caller has already read
// the file, and a package that took a reader would tempt somebody into handing it an open socket.
func newReader(document []byte) io.Reader { return bytes.NewReader(document) }
