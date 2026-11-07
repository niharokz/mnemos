#!/bin/sh
# Checks that every place holding the version agrees with VERSION, and that
# CHANGELOG.md has a section for it. With a tag argument (v1.2.3) it also
# checks the tag matches.  Usage: sh scripts/check-version.sh [vX.Y.Z]
set -eu
cd "$(dirname "$0")/.."
v=$(tr -d ' \n\r' < VERSION)
fail=0
echo "$v" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "✗ VERSION '$v' is not X.Y.Z"; exit 1; }
check() { # file, found value
  if [ "$2" != "$v" ]; then echo "✗ $1 says '$2', VERSION says '$v'"; fail=1; fi
}
check frontend/package.json "$(sed -n 's/^  "version": "\(.*\)",$/\1/p' frontend/package.json | head -1)"
check app/Cargo.toml "$(sed -n 's/^version = "\(.*\)"$/\1/p' app/Cargo.toml | head -1)"
check app/tauri.conf.json "$(sed -n 's/^  "version": "\(.*\)",$/\1/p' app/tauri.conf.json | head -1)"
grep -q "^## $v " CHANGELOG.md || grep -q "^## $v\$" CHANGELOG.md || { echo "✗ CHANGELOG.md has no '## $v' section"; fail=1; }
if [ -n "${1:-}" ] && [ "$1" != "v$v" ]; then echo "✗ tag $1 does not match VERSION $v"; fail=1; fi
[ "$fail" -eq 0 ] && echo "✓ version $v everywhere"
exit "$fail"
