#!/bin/bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KS="$ROOT/../q25pininput/lockscreenpin-release.jks"
PKG_DIR="dev/pinkeys/navlock"

cd "$ROOT"
rm -rf bin obj gen
mkdir -p bin obj gen

echo "==> Compiling resources..."
"$BT/aapt2" compile --dir res -o gen/res.zip

echo "==> Linking resources..."
"$BT/aapt2" link gen/res.zip \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java gen \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name "1.0" \
  -o bin/resources.apk

echo "==> Compiling Java..."
javac -source 8 -target 8 \
  -classpath "$PLATFORM" \
  -bootclasspath "$PLATFORM" \
  -d obj \
  src/$PKG_DIR/*.java \
  gen/$PKG_DIR/R.java

echo "==> Converting to dex..."
"$BT/d8" obj/$PKG_DIR/*.class \
  --release \
  --min-api 26 \
  --output bin/

echo "==> Assembling APK..."
cp bin/resources.apk bin/navlock-unsigned.apk
cd bin
zip -j navlock-unsigned.apk classes.dex
cd ..

echo "==> Aligning..."
"$BT/zipalign" -f 4 bin/navlock-unsigned.apk bin/navlock-aligned.apk

echo "==> Signing..."
"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-pass pass:pinkeys2024 \
  --key-pass pass:pinkeys2024 \
  --ks-key-alias lockscreenpin \
  --out "$ROOT/key2tweaks.apk" \
  bin/navlock-aligned.apk

echo ""
echo "Done: $(ls -lh "$ROOT/key2tweaks.apk" | awk '{print $5, $NF}')"
