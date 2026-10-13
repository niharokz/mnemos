#!/bin/sh
# Checks that every place naming the release agrees:
#   VERSION                      the web app (embedded in the binary)
#   android/version.properties   the Android app (versionName)
#   CHANGELOG.md, android/CHANGELOG.md   a "## <version>" section each
# With a tag argument (CI passes $CI_COMMIT_TAG) the tag must be v<version>.
#
#   sh scripts/check-version.sh            # before tagging
#   sh scripts/check-version.sh v1.0.1     # what CI runs for a tag
set -eu
cd "$(dirname "$0")/.."
fail=0

v=$(tr -d ' \r\n' < VERSION)
echo "$v" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "✗ VERSION is not x.y.z: $v"; exit 1; }

a=$(sed -n 's/^versionName=//p' android/version.properties | tr -d ' \r')
[ "$a" = "$v" ] || { echo "✗ android/version.properties versionName=$a, VERSION=$v"; fail=1; }

for f in CHANGELOG.md android/CHANGELOG.md; do
  grep -Eq "^## $v( |$)" "$f" || { echo "✗ $f has no \"## $v\" section"; fail=1; }
done

if [ -n "${1:-}" ] && [ "$1" != "v$v" ]; then
  echo "✗ tag $1 does not match VERSION ($v): tag v$v instead"; fail=1
fi

[ "$fail" -eq 0 ] && echo "✓ version $v everywhere"
exit "$fail"
