#!/bin/sh
# Publishes a release on GitLab: the signed APK goes to the project's Generic
# Package Registry, and release v<version> links it as "mnemos.apk", so
#   https://gitlab.com/niharokz/mnemos/-/releases/permalink/latest/downloads/mnemos.apk
# always serves the newest APK (the about page links there).
#
# CI runs it after the APK is built (see .gitlab-ci.yml) with CI_JOB_TOKEN.
# By hand, after `cd android && ./build.sh`:
#   GITLAB_TOKEN=glpat-… sh scripts/release.sh     # token scope: api
#
# Needs curl and jq. Safe to re-run: an existing release is left as it is.
set -eu
cd "$(dirname "$0")/.."

V=$(tr -d ' \r\n' < VERSION)
TAG="v$V"
APK="${1:-android/dist/mnemos-$V.apk}"
NOTES="${2:-}"
API="${CI_API_V4_URL:-https://gitlab.com/api/v4}"
PROJECT_ID="${CI_PROJECT_ID:-$(printf '%s' "${GITLAB_PROJECT:-niharokz/mnemos}" | sed 's|/|%2F|g')}"
PROJECT_URL="${CI_PROJECT_URL:-https://gitlab.com/${GITLAB_PROJECT:-niharokz/mnemos}}"

if [ -n "${CI_JOB_TOKEN:-}" ]; then
  AUTH="JOB-TOKEN: $CI_JOB_TOKEN"
else
  AUTH="PRIVATE-TOKEN: ${GITLAB_TOKEN:?set GITLAB_TOKEN (scope: api), or run in CI}"
fi
[ -f "$APK" ] || { echo "✗ $APK not found — build it first: cd android && ./build.sh"; exit 1; }
sh scripts/check-version.sh "${CI_COMMIT_TAG:-}"

# release notes: this version's section of both changelogs (or a prepared file)
section() { awk -v v="$V" '$0 ~ "^## "v"( |$)" {on=1; next} /^## /{on=0} on' "$1"; }
if [ -n "$NOTES" ] && [ -f "$NOTES" ]; then
  body=$(cat "$NOTES")
else
  body=$(printf '### Web app\n%s\n\n### Android app\n%s\n' "$(section CHANGELOG.md)" "$(section android/CHANGELOG.md)")
fi
sha=$(sha256sum "$APK" | cut -d' ' -f1)
body=$(printf '%s\n\n**Install on Android:** open `mnemos.apk` below on the phone (allow installs from your browser when asked).\n\nAPK SHA-256: `%s`\n' "$body" "$sha")

echo "→ uploading $APK"
PKG="$API/projects/$PROJECT_ID/packages/generic/mnemos-android/$V/mnemos-$V.apk"
curl --fail-with-body -sS -H "$AUTH" --upload-file "$APK" "$PKG" >/dev/null

echo "→ creating release $TAG"
json=$(jq -n --arg tag "$TAG" --arg name "Mnemos $V" --arg desc "$body" --arg url "$PKG" --arg ref "${CI_COMMIT_SHA:-$(git rev-parse HEAD)}" '{
  name: $name, tag_name: $tag, ref: $ref, description: $desc,
  assets: {links: [{name: "mnemos.apk", url: $url, direct_asset_path: "/mnemos.apk", link_type: "package"}]}
}')
code=$(curl -sS -o /tmp/release.json -w '%{http_code}' -H "$AUTH" -H "Content-Type: application/json" -d "$json" "$API/projects/$PROJECT_ID/releases")
case "$code" in
  201) echo "✓ released $TAG" ;;
  409) echo "✓ release $TAG already exists — left as it is" ;;
  *) echo "✗ creating the release failed ($code):"; cat /tmp/release.json; echo; exit 1 ;;
esac
echo "  $PROJECT_URL/-/releases/$TAG"
echo "  $PROJECT_URL/-/releases/permalink/latest/downloads/mnemos.apk"
