#!/usr/bin/env bash
# Split the built JREs into per-ABI artifacts for the AAR:
#   - <abi>/libjvm.so, <abi>/libjspawnhelper.so -> android/umc-jvm/src/main/jniLibs/<abi>/
#   - the rest of the JRE                          -> android/umc-jvm/src/main/assets/jre/<abi>/
# Also regenerates the demo app's smoke.jar.
#
# Supported ABIs: arm64-v8a (devices) and x86_64 (emulators).
# A missing JRE is skipped; at least one is required.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MOD="$ROOT/android/umc-jvm"
DEMO="$ROOT/android/demo-app"

declare -A JRE_DIRS=(
  ["arm64-v8a"]="$ROOT/build/out/jre-android"
  ["x86_64"]="$ROOT/build/out/jre-android-x86_64"
)

echo "==> cleaning previous packaging output"
rm -rf "$MOD/src/main/assets/jre" "$MOD/src/main/jniLibs"
mkdir -p "$MOD/src/main/assets/jre" "$MOD/src/main/jniLibs"

PACKAGED=0
for abi in "${!JRE_DIRS[@]}"; do
  jre="${JRE_DIRS[$abi]}"
  if [ ! -f "$jre/lib/server/libjvm.so" ]; then
    echo "==> skipping $abi ($jre does not exist)"
    continue
  fi
  echo "==> packaging $abi"
  mkdir -p "$MOD/src/main/assets/jre/$abi" "$MOD/src/main/jniLibs/$abi"
  ( cd "$jre" && tar cf - --exclude=./bin --exclude=./lib/server . ) \
    | ( cd "$MOD/src/main/assets/jre/$abi" && tar xf - )
  cp "$jre/lib/server/libjvm.so" "$MOD/src/main/jniLibs/$abi/libjvm.so"
  # jspawnhelper must be executable from the APK native library directory
  # (apk_data_file allows exec); app-private storage does not on Android 10+.
  if [ -f "$jre/lib/jspawnhelper" ]; then
    cp "$jre/lib/jspawnhelper" "$MOD/src/main/jniLibs/$abi/libjspawnhelper.so"
    chmod 755 "$MOD/src/main/jniLibs/$abi/libjspawnhelper.so"
  else
    echo "warning: $jre/lib/jspawnhelper is missing; java.lang.Process will not work" >&2
  fi
  PACKAGED=$((PACKAGED + 1))
done

if [ "$PACKAGED" -eq 0 ]; then
  echo "error: no JRE found to package (build the Android JDK and jlink first)" >&2
  exit 1
fi

echo "==> generating smoke.jar"
SMOKE_CLASSES="$ROOT/build/out/smoke-classes"
rm -rf "$SMOKE_CLASSES"
mkdir -p "$SMOKE_CLASSES" "$DEMO/src/main/assets"
javac -d "$SMOKE_CLASSES" "$ROOT/platforms/android/jni/HelloFromJni.java"
( cd "$SMOKE_CLASSES" && jar cf "$DEMO/src/main/assets/smoke.jar" . )

mb() { du -sb "$1" | awk '{printf "%.1f MB", $1/1048576}'; }
echo
echo "assets/jre : $(mb "$MOD/src/main/assets/jre")"
echo "jniLibs    : $(mb "$MOD/src/main/jniLibs")"
echo "smoke.jar  : $(mb "$DEMO/src/main/assets/smoke.jar")"
