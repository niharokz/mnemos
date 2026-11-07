#!/bin/sh
# Runs inside the builder image (docker/builder/Dockerfile), either
#   - as the GitLab release job (Docker executor, image mnemostxt-builder:N), or
#   - from scripts/build-release.sh for a build by hand.
# BUILD_CACHE  folder for cargo/target/gradle/npm/xwin caches (a Docker volume)
# KEY_DIR      folder with mnemostxt.jks + keystore.env (read-only mount)
set -eu
cd "$(dirname "$0")/.."
ROOT=$(pwd)
C=${BUILD_CACHE:-/cache}
KEY_DIR=${KEY_DIR:-/keys}
export HOME=$C/home CARGO_HOME=$C/cargo CARGO_TARGET_DIR=$C/target GRADLE_USER_HOME=$C/gradle \
       npm_config_cache=$C/npm XWIN_CACHE_DIR=$C/xwin
# Tauri's NSIS tools are baked into the image (docker/builder/Dockerfile)
[ -d /opt/tauri-tools ] && export XDG_CACHE_HOME=/opt/tauri-tools
mkdir -p "$HOME" "$CARGO_HOME" "$CARGO_TARGET_DIR" "$GRADLE_USER_HOME" "$npm_config_cache" "$XWIN_CACHE_DIR"
# the toolchain (rustc, cargo-xwin) stays in the image; the cache holds registries
[ -d /usr/local/rustup ] && export PATH=/usr/local/cargo/bin:$PATH RUSTUP_HOME=/usr/local/rustup
V=$(tr -d ' \n\r' < VERSION)
OUT=dist/release
mkdir -p "$OUT"
TAURI=$ROOT/frontend/node_modules/.bin/tauri

echo "▸ mnemostxt $V: $*"
sh scripts/check-version.sh
(cd frontend && npm ci --no-audit --no-fund && npm test)
rm -f "$OUT"/mnemostxt-*

for t in "$@"; do
  case "$t" in
    deb)
      echo "▸ Linux .deb"
      (cd app && "$TAURI" build --bundles deb --ci)
      cp $CARGO_TARGET_DIR/release/bundle/deb/mnemostxt_"$V"_amd64.deb "$OUT/mnemostxt-$V-amd64.deb"
      ;;
    exe)
      echo "▸ Windows setup.exe (cross-compiled)"
      (cd app && "$TAURI" build --runner cargo-xwin --target x86_64-pc-windows-msvc --bundles nsis --ci)
      cp $CARGO_TARGET_DIR/x86_64-pc-windows-msvc/release/bundle/nsis/mnemostxt_"$V"_x64-setup.exe "$OUT/mnemostxt-$V-setup.exe"
      ;;
    apk)
      echo "▸ Android .apk"
      python3 scripts/android-prepare.py --keys "$KEY_DIR"
      (cd app && "$TAURI" android build --apk --target aarch64 --ci)
      apk=$(find app/gen/android/app/build/outputs/apk -name '*release*.apk' ! -name '*unsigned*' | head -1)
      [ -n "$apk" ] || { echo "✗ no signed release APK found — is keystore.properties right?"; find app/gen/android/app/build/outputs -name '*.apk'; exit 1; }
      cp "$apk" "$OUT/mnemostxt-$V.apk"
      "$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs "$OUT/mnemostxt-$V.apk" | head -3
      rm -f app/gen/android/keystore.properties
      ;;
    *) echo "unknown target $t (deb, exe, apk)"; exit 2 ;;
  esac
done
(cd "$OUT" && sha256sum mnemostxt-* > SHA256SUMS && cat SHA256SUMS)
