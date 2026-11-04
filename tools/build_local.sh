#!/usr/bin/env bash
# Offline APK build without Gradle/Maven (used where Google Maven is unreachable).
# Needs: JDK, android.jar (API 35), aapt, dalvik-exchange (dx), zipalign, apksigner.
# usage: [RESJAR=android-33.jar] [TEST=1] [NATIVE_DIR=dir-with-lib/] tools/build_local.sh <android-35.jar> <out_dir> <keystore> <storepass>
#   TEST=1 builds the debug-variant identity: package com.mojahed.hdrstudio.test, versionName suffix -test.
#   NATIVE_DIR: a directory holding lib/arm64-v8a/libhdrstudio_ncnn.so, added to the APK as is.
set -euo pipefail
JAR=$1; OUT=$2; KS=$3; PASS=$4
# old Debian aapt cannot parse the API 34+ resource table; link resources against API 33 (attrs used exist there)
RESJAR=${RESJAR:-$(dirname "$JAR")/android-33.jar}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
rm -rf "$OUT/classes" "$OUT/gen" && mkdir -p "$OUT/classes" "$OUT/gen"
# values mirrored from app/build.gradle.kts
APP_ID=com.mojahed.hdrstudio${TEST:+.test}; VCODE=$(grep -oP 'versionCode = \K[0-9]+' "$ROOT/app/build.gradle.kts"); VNAME=$(grep -oP 'versionName = "\K[^"]+' "$ROOT/app/build.gradle.kts")${TEST:+-test}
MIN=$(grep -oP 'minSdk = \K[0-9]+' "$ROOT/app/build.gradle.kts"); TGT=$(grep -oP 'targetSdk = \K[0-9]+' "$ROOT/app/build.gradle.kts")
sed -e "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$APP_ID\" android:versionCode=\"$VCODE\" android:versionName=\"$VNAME\">\n    <uses-sdk android:minSdkVersion=\"$MIN\" android:targetSdkVersion=\"$TGT\" />|" \
    -e 's|android:name="\.|android:name="com.mojahed.hdrstudio.|g' \
    "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
# relative component names resolve against the namespace (Gradle does this); the .test package must not change them
MANIFEST="$OUT/AndroidManifest.xml"
# 1. resources + manifest (generates R.java, not referenced by code but kept consistent)
aapt package -f -m -J "$OUT/gen" -M "$MANIFEST" -S "$ROOT/app/src/main/res" -I "$RESJAR"
# 2. compile (Java 8 bytecode; no lambdas in the code base because dx cannot desugar them)
javac -J-Xmx1g -source 8 -target 8 -encoding UTF-8 -Xlint:-options -bootclasspath "$JAR" -d "$OUT/classes" \
  $(find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java') 2>&1 | grep -v '^Picked up' || true
test -f "$OUT/classes/com/mojahed/hdrstudio/ui/MainActivity.class"
# 3. dex
dalvik-exchange --dex --min-sdk-version=$MIN --output="$OUT/classes.dex" "$OUT/classes"
# 4. package (manifest, resources, assets) + dex
aapt package -f -M "$MANIFEST" -S "$ROOT/app/src/main/res" -A "$ROOT/app/src/main/assets" \
  -I "$RESJAR" -0 bin -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null)
if [ -n "${NATIVE_DIR:-}" ]; then (cd "$NATIVE_DIR" && aapt add -f "$OUT/unsigned.apk" $(find lib -name '*.so' | LC_ALL=C sort) >/dev/null); fi
# 4b. every component the manifest names must be a class in classes.dex (a launch crash otherwise)
for c in $(aapt dump xmltree "$OUT/unsigned.apk" AndroidManifest.xml | grep -B2 -A0 'android:name' | grep -oP 'android:name\(0x01010003\)="\K[^"]+' | grep -E '^com\.mojahed\.'); do
  test -f "$OUT/classes/$(echo "$c" | tr . /).class" || { echo "manifest component $c is not in classes.dex" >&2; exit 1; }
done
if aapt dump xmltree "$OUT/unsigned.apk" AndroidManifest.xml | grep -q 'android:name(0x01010003)="\.'; then echo "relative component name left in manifest" >&2; exit 1; fi
# 5. align + sign (v2/v3 signature)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --v2-signing-enabled true --v3-signing-enabled true --ks "$KS" --ks-pass "pass:$PASS" --out "$OUT/HDR-Studio-local.apk" "$OUT/aligned.apk"
apksigner verify --verbose "$OUT/HDR-Studio-local.apk" | head -5
ls -la "$OUT/HDR-Studio-local.apk"
# 6. build record: which sources produced which binaries (reproduce with the same JDK/dx/aapt)
{
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "javac: $(javac -version 2>&1 | grep -v 'Picked up')"
  echo "sources_sha256: $(cd "$ROOT" && find app/src/main -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -d' ' -f1)  (sha256 of 'sha256sum' over app/src/main, sorted paths)"
  echo "classes.dex_sha256: $(sha256sum "$OUT/classes.dex" | cut -d' ' -f1)"
  echo "package: $APP_ID $VNAME ($VCODE)"
  if [ -n "${NATIVE_DIR:-}" ]; then (cd "$NATIVE_DIR" && find lib -name '*.so' | LC_ALL=C sort | xargs sha256sum | sed 's/^/native: /'); fi
  echo "apk_sha256: $(sha256sum "$OUT/HDR-Studio-local.apk" | cut -d' ' -f1)"
  echo "signing: local test key (NOT the CI release key) - uninstall before installing a CI build"
} | tee "$OUT/BUILD_INFO.txt"
