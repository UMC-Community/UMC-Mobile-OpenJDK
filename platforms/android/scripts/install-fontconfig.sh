#!/usr/bin/env bash
# Inject the cross-built fontconfig into the JRE directories:
#   lib/libfontconfig.so(.1) + conf/fonts.conf + cache/
# Run after jlink and before packaging the AAR.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

declare -A JRES=(
  ["arm64-v8a"]="$ROOT/build/out/jre-android"
  ["x86_64"]="$ROOT/build/out/jre-android-x86_64"
)

FONTS_CONF='<?xml version="1.0"?>
<fontconfig>
  <!-- Android system font directories -->
  <dir>/system/fonts</dir>
  <dir>/product/fonts</dir>
  <dir>/vendor/fonts</dir>
</fontconfig>
'

INSTALLED=0
for abi in "${!JRES[@]}"; do
  jre="${JRES[$abi]}"
  src="$ROOT/build/deps/fontconfig/$abi/lib/libfontconfig.so"
  if [ ! -f "$src" ]; then
    echo "==> skipping $abi (fontconfig build output missing)"
    continue
  fi
  [ -d "$jre/lib" ] || continue
  cp "$src" "$jre/lib/libfontconfig.so"
  cp "$src" "$jre/lib/libfontconfig.so.1"
  mkdir -p "$jre/conf" "$jre/cache"
  printf '%s' "$FONTS_CONF" > "$jre/conf/fonts.conf"
  INSTALLED=$((INSTALLED + 1))
  echo "==> injected fontconfig into $abi: $jre"
done

[ "$INSTALLED" -gt 0 ] || { echo "error: no JRE available to inject into" >&2; exit 1; }
