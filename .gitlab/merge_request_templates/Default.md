## What and why

## Checks

- [ ] `go vet ./... && go test ./...` and `gofmt -l cmd internal web` is empty
- [ ] `sh scripts/check-public.sh` (no secrets, keys or private words)
- [ ] `sh scripts/check-version.sh` (only when releasing)
- [ ] CHANGELOG.md / android/CHANGELOG.md updated (user-visible changes)
- [ ] Android changes: `cd android && ./build.sh check` passes
