#!/bin/sh
# Creates the Android signing key once, outside the repo:
#   $KEYS_HOME/.mnemostxt-keys/mnemostxt.jks   (mode 600, folder 700)
#   $KEYS_HOME/.mnemostxt-keys/keystore.env    (passwords + alias)
# BACK THIS FOLDER UP: Android only installs updates signed with the same key.
set -eu
KEY_DIR=${MNEMOSTXT_KEY_DIR:-${KEYS_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/keys}/.mnemostxt-keys}
[ -f "$KEY_DIR/mnemostxt.jks" ] && { echo "✗ $KEY_DIR/mnemostxt.jks already exists — not overwriting"; exit 1; }
mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"
PW=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
cat > "$KEY_DIR/keystore.env" <<ENV
MNEMOSTXT_STORE_PASSWORD=$PW
MNEMOSTXT_KEY_PASSWORD=$PW
MNEMOSTXT_KEY_ALIAS=mnemostxt
ENV
chmod 600 "$KEY_DIR/keystore.env"
docker run --rm --user "$(id -u):$(id -g)" -v "$KEY_DIR:/keys" mirror.gcr.io/library/eclipse-temurin:21-jre \
  keytool -genkeypair -keystore /keys/mnemostxt.jks -storetype PKCS12 -alias mnemostxt -keyalg RSA -keysize 4096 \
  -validity 10000 -storepass "$PW" -keypass "$PW" -dname "CN=mnemostxt, O=nih.ar"
chmod 600 "$KEY_DIR/mnemostxt.jks"
echo "✓ key created in $KEY_DIR — back it up (e.g. to your password manager or an offline disk)"
