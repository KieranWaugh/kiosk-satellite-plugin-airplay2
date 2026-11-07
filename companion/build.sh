#!/usr/bin/env bash
# Builds the Now Playing companion app into assets/nowplaying.apk, which the plugin bundles.
# No Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner, from the Android SDK.
#
# Signing: releases must always use the same key, or devices can't update the app. Set
#   COMPANION_KEYSTORE           path to the keystore (.jks)
#   COMPANION_KEYSTORE_PASSWORD  its password (store and key)
#   COMPANION_KEY_ALIAS          key alias (default: companion)
# For a local test build without the release key, pass --debug-key (a throwaway key is made).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
VERSION_CODE=5
VERSION_NAME=1.4
MIN_SDK=26
TARGET_SDK=30

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLAT="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

KS="${COMPANION_KEYSTORE:-}"
PASS="${COMPANION_KEYSTORE_PASSWORD:-}"
ALIAS="${COMPANION_KEY_ALIAS:-companion}"
WORK="$HERE/build"
rm -rf "$WORK" && mkdir -p "$WORK/classes" "$WORK/dex"

if [ "${1:-}" = "--debug-key" ]; then
  KS="$WORK/debug.jks"; PASS=debugdebug; ALIAS=debug
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 1000 -dname "CN=AirPlay Now Playing debug" >/dev/null 2>&1
  echo "Using a throwaway debug key: devices with a release build can't update to this APK."
elif [ -z "$KS" ] || [ -z "$PASS" ]; then
  echo "Set COMPANION_KEYSTORE and COMPANION_KEYSTORE_PASSWORD, or pass --debug-key" >&2
  exit 1
fi

"$BT/aapt2" link -o "$WORK/base.apk" -I "$PLAT" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME
"$JAVAC" --release 8 -classpath "$PLAT" -d "$WORK/classes" $(find "$HERE/src" -name '*.java')
"$BT/d8" --release --min-api $MIN_SDK --lib "$PLAT" --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')
cp "$WORK/base.apk" "$WORK/unsigned.apk"
(cd "$WORK/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
mkdir -p "$ROOT/assets"
"$BT/apksigner" sign --v4-signing-enabled false --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --ks-key-alias "$ALIAS" \
  --out "$ROOT/assets/nowplaying.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify "$ROOT/assets/nowplaying.apk"
rm -rf "$WORK"
ls -l "$ROOT/assets/nowplaying.apk"
