// Package web embeds the front end (the app page, CSS, JS, the vendored
// CodeMirror bundle and icons) into the binary, so the image is a single file.
package web

import "embed"

// Files holds everything under static/.
//
//go:embed static
var Files embed.FS
