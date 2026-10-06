module gitlab.com/niharokz/samanote

go 1.23

require (
	github.com/yuin/goldmark v1.7.8
	golang.org/x/crypto v0.31.0
)

// Fetched through its GitHub mirror (same approach as Arthik/Omnimo/Kronos), so the
// build works where golang.org is blocked.
replace golang.org/x/crypto => github.com/golang/crypto v0.31.0
