#!/usr/bin/env bash
# UMC Mobile OpenJDK — build environment preflight
# Usage: bash tools/preflight.sh
# Thresholds can be overridden with UMC_DISK_GIB / UMC_RAM_MIB / UMC_BOOT_JDK_MIN
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISK_GIB="${UMC_DISK_GIB:-20}"
RAM_MIB="${UMC_RAM_MIB:-6144}"
BOOT_JDK_MIN="${UMC_BOOT_JDK_MIN:-20}"

if [ -t 1 ]; then
  OK=$'\033[32m'; WARN=$'\033[33m'; FAILC=$'\033[31m'; DIM=$'\033[2m'; OFF=$'\033[0m'
else
  OK=; WARN=; FAILC=; DIM=; OFF=
fi
ok()   { printf '  %sok%s   %s\n'   "$OK"    "$OFF" "$*"; }
warn() { printf '  %swarn%s %s\n'   "$WARN"  "$OFF" "$*"; WARNS=$((WARNS+1)); }
fail() { printf '  %sfail%s %s\n'   "$FAILC" "$OFF" "$*"; FAILS=$((FAILS+1)); }
info() { printf '  %s%s%s\n'        "$DIM"   "$*"  "$OFF"; }
have() { command -v "$1" >/dev/null 2>&1; }

WARNS=0
FAILS=0

echo "== Host =="
OS="$(uname -s)"; ARCH="$(uname -m)"
info "$OS $ARCH, kernel $(uname -r), $(nproc) cores"

echo "== Disk ($ROOT) =="
AVAIL_KB="$(df -Pk "$ROOT" 2>/dev/null | awk 'NR==2 {print $4}')"
if [ -n "${AVAIL_KB:-}" ]; then
  AVAIL_GIB=$((AVAIL_KB / 1024 / 1024))
  if [ "$AVAIL_GIB" -ge "$DISK_GIB" ]; then
    ok "available ${AVAIL_GIB} GiB (threshold ${DISK_GIB} GiB)"
  else
    fail "available ${AVAIL_GIB} GiB < threshold ${DISK_GIB} GiB (sources + NDK + build tree)"
  fi
else
  warn "cannot determine free disk space"
fi

echo "== Memory =="
MemAvail_MIB="$(awk '/^MemAvailable:/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)"
if [ "$MemAvail_MIB" -ge "$RAM_MIB" ]; then
  ok "available ${MemAvail_MIB} MiB (threshold ${RAM_MIB} MiB)"
else
  fail "available ${MemAvail_MIB} MiB < threshold ${RAM_MIB} MiB; the build may OOM. Close browsers or lower JOBS"
fi
SWAP_TOTAL="$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
SWAP_FREE="$(awk '/^SwapFree:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
if [ "$SWAP_TOTAL" -gt 0 ] && [ "$SWAP_FREE" -lt $((SWAP_TOTAL / 10)) ]; then
  warn "swap almost exhausted (${SWAP_FREE} / ${SWAP_TOTAL} KiB free)"
fi

echo "== Host toolchain =="
for t in git make gcc g++ ar ld zip unzip file; do
  if have "$t"; then ok "$t"; else fail "missing $t"; fi
done
for t in autoconf clang; do
  if have "$t"; then ok "$t"; else warn "missing $t (autoconf is required; clang may come from the NDK)"; fi
done

echo "== Boot JDK =="
if have java; then
  JV="$(java -version 2>&1 | awk -F'"' 'NR==1 {print $2}')"
  JMAJ="${JV%%.*}"
  if [ -n "$JMAJ" ] && [ "$JMAJ" -ge "$BOOT_JDK_MIN" ] 2>/dev/null; then
    ok "java $JV (requires >= $BOOT_JDK_MIN)"
  else
    fail "java ${JV:-unknown} is lower than $BOOT_JDK_MIN and cannot be used as a boot JDK"
  fi
else
  fail "java not found"
fi

echo "== Android =="
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
info "ANDROID_HOME=${SDK}"
NDK_DIR=""
# Lookup order: ANDROID_NDK_HOME > $SDK/ndk/* > /opt/android-ndk-*
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -f "$ANDROID_NDK_HOME/source.properties" ]; then
  NDK_DIR="$ANDROID_NDK_HOME"
elif [ -d "$SDK/ndk" ]; then
  NDK_DIR="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
else
  NDK_DIR="$(ls -d /opt/android-ndk-* 2>/dev/null | sort -V | tail -1)"
fi
if [ -n "$NDK_DIR" ] && [ -f "$NDK_DIR/source.properties" ]; then
  NDK_VERSION="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK_DIR/source.properties" | head -1)"
  ok "NDK ${NDK_VERSION:-unknown version} ($NDK_DIR)"
else
  fail "NDK not found (set ANDROID_NDK_HOME, or install under $SDK/ndk/* or /opt/android-ndk-*)"
fi
if have adb; then
  DEVICES="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l)"
  if [ "$DEVICES" -gt 0 ]; then ok "adb sees $DEVICES device(s)"; else warn "adb sees no devices (needed for on-device tests)"; fi
else
  warn "adb not found"
fi

echo "== iOS =="
if [ "$OS" = "Darwin" ]; then
  if have xcodebuild; then ok "Xcode available"; else fail "xcodebuild missing on macOS"; fi
else
  info "not macOS: the iOS line cannot be built on this host (requires Xcode + the iOS SDK)"
fi

echo
if [ "$FAILS" -gt 0 ]; then
  printf '%spreflight failed: %d blocker(s), %d warning(s)%s\n' "$FAILC" "$FAILS" "$WARNS" "$OFF"
  exit 1
fi
printf '%spreflight passed: 0 blockers, %d warning(s)%s\n' "$OK" "$WARNS" "$OFF"
