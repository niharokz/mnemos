#!/bin/sh
# Regenerates every icon from assets/logo/*.svg (needs rsvg-convert and npm ci in frontend/).
set -eu
cd "$(dirname "$0")/.."
L=assets/logo
rsvg-convert -w 1024 -h 1024 $L/mnemostxt.svg -o $L/mnemostxt-1024.png
rsvg-convert -w 512 -h 512 $L/mnemostxt.svg -o $L/mnemostxt-512.png
rsvg-convert -w 192 -h 192 $L/mnemostxt.svg -o $L/mnemostxt-192.png
cp $L/mnemostxt.svg frontend/public/icon.svg; cp $L/mnemostxt.svg server/web/icon.svg
cp $L/mnemostxt-192.png frontend/public/icon-192.png; cp $L/mnemostxt-512.png frontend/public/icon-512.png
(cd app && ../frontend/node_modules/.bin/tauri icon ../$L/mnemostxt-1024.png -o icons && rm -rf icons/ios)
# Android adaptive icon: our own foreground on the dark tile colour
sed -i 's/#FFFFFF/#0A0D11/I' app/icons/android/values/ic_launcher_background.xml
for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  rsvg-convert -w "${d#*:}" -h "${d#*:}" $L/android-foreground.svg -o "app/icons/android/mipmap-${d%%:*}/ic_launcher_foreground.png"
done
echo "✓ icons regenerated"
