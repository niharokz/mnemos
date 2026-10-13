#!/usr/bin/env bash
# Mnemos for Android build helper.
#   ./build.sh keystore   create the signing key once (outside the repo)
#   ./build.sh            build the signed release APK in Docker → dist/mnemos-<version>.apk
#   ./build.sh check      unit tests + lint (warnings are errors)
#   ./build.sh profile    regenerate the Baseline Profile on a connected device
#   ./build.sh install    install the built APK over USB with adb
set -euo pipefail
cd "$(dirname "$0")"

KEY_DIR="${MNEMOS_KEY_DIR:-$HOME/.mnemos-keys}"
KEYSTORE="$KEY_DIR/mnemos.jks"
# Passwords live next to the key, so re-extracting or replacing the repo never loses them.
ENV_FILE="$KEY_DIR/keystore.env"
# Tagged by the Dockerfile's hash: editing the Dockerfile rebuilds the image automatically.
IMAGE="mnemos-build:$(sha256sum docker/Dockerfile | cut -c1-12)"
VERSION="$(grep '^versionName=' version.properties | cut -d= -f2)"
APK="dist/mnemos-$VERSION.apk"

# One-time moves from the project's old name (Samanote): signing key folder and Gradle cache.
migrate_old_name() {
  if [ ! -d "$KEY_DIR" ] && [ -d "$HOME/.samanote-keys" ]; then
    mv "$HOME/.samanote-keys" "$KEY_DIR"
    echo "Moved signing key folder ~/.samanote-keys → $KEY_DIR"
  fi
  if ! docker volume inspect mnemos-gradle-cache >/dev/null 2>&1 && docker volume inspect samanote-gradle-cache >/dev/null 2>&1; then
    echo "Copying the Gradle cache from the old samanote volume (saves a re-download)…"
    docker run --rm -v samanote-gradle-cache:/from:ro -v mnemos-gradle-cache:/to alpine cp -a /from/. /to/
  fi
}
migrate_old_name

image() {
  docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -t "$IMAGE" docker
}

# Runs Gradle inside the build image; the Gradle cache is a named volume so later builds work offline.
gradle_in_docker() {
  image
  local keys=()
  # The signing key is only mounted when it exists (release builds).
  if [ -f "$KEYSTORE" ]; then
    keys=(-v "$KEY_DIR":/keys:ro -e MNEMOS_KEYSTORE=/keys/mnemos.jks -e MNEMOS_STORE_PASSWORD -e MNEMOS_KEY_PASSWORD -e MNEMOS_KEY_ALIAS)
  fi
  docker run --rm \
    -v "$PWD":/src -v mnemos-gradle-cache:/cache/gradle \
    "${keys[@]}" \
    -u "$(id -u):$(id -g)" -e HOME=/tmp \
    "$IMAGE" ./gradlew --no-daemon "$@"
}

load_keys() {
  # Older layout kept keystore.env in the repo; move it next to the key once.
  if [ ! -f "$ENV_FILE" ] && [ -f keystore.env ]; then mv keystore.env "$ENV_FILE" && chmod 600 "$ENV_FILE"; fi
  if [ ! -f "$KEYSTORE" ]; then echo "No signing key. Run ./build.sh keystore first." >&2; exit 1; fi
  if [ ! -f "$ENV_FILE" ]; then
    echo "Found $KEYSTORE but not its passwords ($ENV_FILE)." >&2
    echo "Restore keystore.env from your backup, or (only if nothing was ever installed with this key) delete the key and run ./build.sh keystore." >&2
    exit 1
  fi
  set -a; . "$ENV_FILE"; set +a
}

case "${1:-release}" in
  keystore)
    if [ -f "$KEYSTORE" ]; then echo "Keystore already exists at $KEYSTORE (passwords: $ENV_FILE)"; exit 0; fi
    mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"
    pass="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
    image
    docker run --rm -v "$KEY_DIR":/keys -u "$(id -u):$(id -g)" "$IMAGE" \
      keytool -genkeypair -v -keystore /keys/mnemos.jks -alias mnemos -keyalg RSA -keysize 4096 -validity 10000 \
      -storepass "$pass" -keypass "$pass" -dname "CN=Mnemos, O=nihars, C=IN"
    printf 'MNEMOS_STORE_PASSWORD=%s\nMNEMOS_KEY_PASSWORD=%s\nMNEMOS_KEY_ALIAS=mnemos\n' "$pass" "$pass" > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo "Keystore: $KEYSTORE, passwords: $ENV_FILE. Back up both; updates must be signed with this key."
    ;;
  release)
    load_keys
    # --continue: report errors from every module in one run, not just the first.
    gradle_in_docker --continue :app:assembleRelease
    mkdir -p dist
    cp app/build/outputs/apk/release/app-release.apk "$APK"
    echo "Built $APK"
    # Hard rule: the APK must not ask for INTERNET.
    if docker run --rm -v "$PWD":/src "$IMAGE" sh -c "\$ANDROID_HOME/build-tools/36.0.0/aapt dump permissions /src/$APK" | grep -q 'android.permission.INTERNET'; then
      echo "ERROR: INTERNET permission found in the APK" >&2; exit 1
    fi
    echo "Checked: no INTERNET permission."
    ;;
  check)
    gradle_in_docker check lint
    ;;
  profile)
    # Runs on the host: it needs a device over adb and a local Android SDK (ANDROID_HOME).
    load_keys
    ./gradlew :app:generateReleaseBaselineProfile
    ;;
  install)
    [ -f "$APK" ] || { echo "Build first: ./build.sh" >&2; exit 1; }
    adb install -r "$APK"
    ;;
  *)
    sed -n '2,8p' "$0"; exit 1 ;;
esac
