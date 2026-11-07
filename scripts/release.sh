#!/bin/sh
# One-command release: bumps the version everywhere, checks, commits, tags and
# pushes. The tag starts the release pipeline (GitLab builds the .deb, the
# setup.exe and the .apk on your own runner and publishes a release).
#
#   sh scripts/release.sh 0.0.2
#
# Write the "## 0.0.2 - YYYY-MM-DD" section in CHANGELOG.md first.
set -eu
cd "$(dirname "$0")/.."
new=${1:?usage: sh scripts/release.sh X.Y.Z}
echo "$new" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "version must be X.Y.Z"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "commit or stash your changes first"; exit 1; }
git rev-parse -q --verify "refs/tags/v$new" >/dev/null && { echo "tag v$new already exists"; exit 1; }

printf '%s\n' "$new" > VERSION
sed -i "0,/^  \"version\": \".*\",$/s//  \"version\": \"$new\",/" frontend/package.json app/tauri.conf.json
sed -i "0,/^version = \".*\"$/s//version = \"$new\"/" app/Cargo.toml
(cd frontend && npm install --package-lock-only --ignore-scripts >/dev/null)
(cd app && cargo update -p mnemostxt --offline >/dev/null 2>&1 || true)
sh scripts/check-version.sh "v$new"

git add VERSION frontend/package.json frontend/package-lock.json app/Cargo.toml app/Cargo.lock app/tauri.conf.json
git commit -m "mnemostxt $new"
git tag -a "v$new" -m "mnemostxt $new"
printf 'Push main and tag v%s to every remote? [y/N] ' "$new"
read -r ok
case "$ok" in y|Y) git push origin HEAD && git push origin "v$new" ;; *) echo "Not pushed. Later: git push origin HEAD && git push origin v$new" ;; esac
