#!/bin/sh
# Builds the release files in the builder image (docker/builder/Dockerfile):
#   dist/release/mnemostxt-X.Y.Z-amd64.deb       Linux
#   dist/release/mnemostxt-X.Y.Z-setup.exe       Windows installer
#   dist/release/mnemostxt-X.Y.Z.apk             Android (arm64), signed
#   dist/release/SHA256SUMS
#
#   sh scripts/build-release.sh            all three
#   sh scripts/build-release.sh deb apk    some of them
#
# Used by the GitLab runner on a tag, and by hand on any Linux box
# with Docker. The Android key comes from MNEMOSTXT_KEY_DIR
# (default $KEYS_HOME/.mnemostxt-keys, else ~/.local/share/keys/.mnemostxt-keys).
# Caches (cargo, target, gradle, npm, xwin) live in Docker volumes, so the
# second build is much faster than the first.
set -eu
cd "$(dirname "$0")/.."
TARGETS=${*:-deb exe apk}
BUILDER_TAG=3   # bump when docker/builder/Dockerfile changes (and the image: in .gitlab-ci.yml)
IMAGE=mnemostxt-builder:$BUILDER_TAG
KEY_DIR=${MNEMOSTXT_KEY_DIR:-${KEYS_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/keys}/.mnemostxt-keys}

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "▸ building $IMAGE (first time only, ~15 min)"
  docker build -t "$IMAGE" docker/builder
fi

case " $TARGETS " in
  *" apk "*) [ -f "$KEY_DIR/mnemostxt.jks" ] || { echo "✗ no signing key in $KEY_DIR — run: sh scripts/make-keystore.sh"; exit 1; } ;;
esac

KEYS_MOUNT=""
[ -d "$KEY_DIR" ] && KEYS_MOUNT="-v $KEY_DIR:/keys:ro"

# runs as you, so files in dist/ are yours; caches live in a Docker volume
# shellcheck disable=SC2086
docker run --rm --user "$(id -u):$(id -g)" -e BUILD_CACHE=/cache -e KEY_DIR=/keys \
  -v mnemostxt-build-cache:/cache -v "$PWD:/src" -w /src $KEYS_MOUNT \
  "$IMAGE" sh scripts/build-in-container.sh $TARGETS
ls -l dist/release
