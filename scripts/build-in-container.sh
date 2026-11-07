#!/bin/sh
# Runs inside the builder image (see scripts/build-release.sh).
set -eu
cd /src
V=$(tr -d ' \n\r' < VERSION)
OUT=dist/release
mkdir -p "$OUT" /cache/home /cache/cargo /cache/target /cache/gradle /cache/npm /cache/xwin
# the image's toolchain lives in /usr/local/cargo; the cache holds the registry
[ -d /cache/cargo/bin ] || cp -r /usr/local/cargo/bin /cache/cargo/ 2>/dev/null || true
export PATH=/usr/local/cargo/bin:$PATH RUSTUP_HOME=/usr/local/rustup
TAURI=/src/frontend/node_modules/.bin/tauri

echo "▸ mnemostxt $V: $*"
sh scripts/check-version.sh
(cd frontend && npm ci --no-audit --no-fund && npm test)
rm -f "$OUT"/mnemostxt-*

for t in "$@"; do
  case "$t" in
    deb)
      echo "▸ Linux .deb"
      (cd app && "$TAURI" build --bundles deb --ci)
      cp /cache/target/release/bundle/deb/mnemostxt_"$V"_amd64.deb "$OUT/mnemostxt-$V-amd64.deb"
      ;;
    exe)
      echo "▸ Windows setup.exe (cross-compiled)"
      (cd app && "$TAURI" build --runner cargo-xwin --target x86_64-pc-windows-msvc --bundles nsis --ci)
      cp /cache/target/x86_64-pc-windows-msvc/release/bundle/nsis/mnemostxt_"$V"_x64-setup.exe "$OUT/mnemostxt-$V-setup.exe"
      ;;
    apk)
      echo "▸ Android .apk"
      python3 scripts/android-prepare.py --keys /keys
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
