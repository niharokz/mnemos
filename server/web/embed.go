// Package web holds the files built into the server binary: the editor
// (dist/, copied from frontend/dist by the Docker build or `make web`) and
// the home page.
package web

import "embed"

//go:embed all:dist
var Dist embed.FS

//go:embed landing.html icon.svg
var Landing embed.FS
