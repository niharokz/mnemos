// Package mnemos holds files shared by the web app and the Android app. The demo
// vault lives once, in android/demo-vault, and is embedded here for the web demo.
package mnemos

import (
	"embed"
	"strings"
)

//go:embed VERSION
var version string

// Version is the release, from the VERSION file — the one place it is set for the
// web app (android/version.properties must match; CI checks it).
func Version() string { return strings.TrimSpace(version) }

// DemoVault is the sample vault of the demo user (android/demo-vault).
//
//go:embed android/demo-vault
var DemoVault embed.FS
