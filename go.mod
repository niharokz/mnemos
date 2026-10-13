module gitlab.com/niharokz/mnemos

go 1.23

require (
	github.com/yuin/goldmark v1.7.8
	golang.org/x/crypto v0.31.0
)

// Dependencies are vendored (vendor/), so building needs no network. The GitHub
// mirror of x/crypto is the same code; it is only used when re-vendoring.
replace golang.org/x/crypto => github.com/golang/crypto v0.31.0
