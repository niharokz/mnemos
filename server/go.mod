module gitlab.com/niharokz/mnemostxt/server

go 1.23

require golang.org/x/crypto v0.31.0

// Dependencies are vendored (vendor/), so building needs no network. The GitHub
// mirror of x/crypto is the same code; it is only used when re-vendoring.
replace golang.org/x/crypto => github.com/golang/crypto v0.31.0
