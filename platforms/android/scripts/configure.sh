#!/usr/bin/env bash
# Wrapper around configure for the Android cross build.
# Requirements: tools/preflight.sh passes; host clang (BuildC) and NDK clang (target).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/platforms/android/config/env.sh"

BOOT_JDK="${BOOT_JDK:-/usr/lib/jvm/java-21-openjdk-amd64}"
# Without an explicit version string OpenJDK generates
# "adhoc.<username>.<dirname>", which is neither professional nor reproducible.
VERSION_STRING="${VERSION_STRING:-21.0.14-umc}"
# On Linux OpenJDK looks up BuildC as cc/gcc by default. When the target uses
# clang, BuildC must be clang too, so point it at the host LLVM installation.
HOST_LLVM="${HOST_LLVM:-/usr/lib/llvm-14/bin}"

# Headless libawt still includes X11 headers, but does not link libX11.
# Borrow the host's X11 headers so the AWT sources compile.
X11_INCLUDE="$DEPS_DIR/x11-include"
if [ -d /usr/include/X11 ]; then
  mkdir -p "$X11_INCLUDE/X11"
  cp -r /usr/include/X11/. "$X11_INCLUDE/X11/"
else
  echo "error: X11 headers not found (sudo apt install libx11-dev libxrender-dev libxrandr-dev libxext-dev libxi-dev libxtst-dev)" >&2
  exit 1
fi
if [ ! -f "$X11_INCLUDE/X11/extensions/Xrender.h" ]; then
  echo "error: X11 extension headers not found (sudo apt install libxrender-dev libxrandr-dev libxext-dev libxi-dev libxtst-dev)" >&2
  exit 1
fi

# fontpath.c needs fontconfig headers at compile time; the runtime loads
# libfontconfig via dlopen and degrades to the default font path when absent.
FONTCONFIG_INCLUDE="$DEPS_DIR/fontconfig-include"
if [ -f /usr/include/fontconfig/fontconfig.h ]; then
  mkdir -p "$FONTCONFIG_INCLUDE/fontconfig"
  cp -r /usr/include/fontconfig/. "$FONTCONFIG_INCLUDE/fontconfig/"
else
  echo "error: fontconfig headers not found (sudo apt install libfontconfig1-dev)" >&2
  exit 1
fi

# NDK clang defaults to emulated TLS for Android targets, while HotSpot's
# assembly (threadLS_linux_aarch64.S) references native TLS symbols. Mixing the
# two makes libjvm.so fail with "cannot locate symbol Thread::_thr_current".
# Use native TLS everywhere (arm64 Android supports dynamic TLS in dlopen'ed
# libraries since API 23).
EXTRA_CFLAGS="-DANDROID -O3 -fno-emulated-tls -I$X11_INCLUDE -I$FONTCONFIG_INCLUDE"

cd "$SRC_DIR"

# Note: AR/NM/STRIP/OBJCOPY/OBJDUMP/RANLIB must be passed as configure
# command-line variables; autoconf ignores the environment values.
bash ./configure \
  --openjdk-target="$TARGET" \
  --with-toolchain-type=clang \
  --with-boot-jdk="$BOOT_JDK" \
  --with-version-string="$VERSION_STRING" \
  --disable-warnings-as-errors \
  --enable-headless-only=yes \
  --with-jvm-variants=server \
  --with-jvm-features=-dtrace,-zero,-vm-structs,-epsilongc \
  --with-debug-level=release \
  --with-native-debug-symbols=none \
  --with-freetype=bundled \
  --with-extra-cflags="$EXTRA_CFLAGS" \
  --with-extra-cxxflags="$EXTRA_CFLAGS" \
  --with-extra-ldflags="-Wl,--undefined-version -Wl,--enable-new-dtags" \
  AR="$AR" \
  NM="$NM" \
  STRIP="$STRIP" \
  OBJCOPY="$OBJCOPY" \
  OBJDUMP="$OBJDUMP" \
  RANLIB="$RANLIB" \
  BUILD_CC="$HOST_LLVM/clang" \
  BUILD_CXX="$HOST_LLVM/clang++" \
  BUILD_NM="$HOST_LLVM/llvm-nm" \
  BUILD_AR="$HOST_LLVM/llvm-ar" \
  BUILD_OBJCOPY="$HOST_LLVM/llvm-objcopy" \
  BUILD_STRIP="$HOST_LLVM/llvm-strip" \
  "$@"
