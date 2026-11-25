#!/usr/bin/env bash
# Cross-compile fontconfig for Android with freetype and expat linked statically.
# Output: build/deps/fontconfig/<abi>/lib/libfontconfig.so
#
# Usage: bash platforms/android/scripts/build-fontconfig.sh <arm64-v8a|x86_64>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ABI="${1:-arm64-v8a}"

case "$ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android ;;
  x86_64)    TRIPLE=x86_64-linux-android ;;
  *) echo "unsupported ABI: $ABI" >&2; exit 1 ;;
esac

API=26
NDK="${ANDROID_NDK_HOME:-/opt/android-ndk-r30}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

CC="$TC/bin/${TRIPLE}${API}-clang"
CXX="$TC/bin/${TRIPLE}${API}-clang++"
AR="$TC/bin/llvm-ar"
RANLIB="$TC/bin/llvm-ranlib"
STRIP="$TC/bin/llvm-strip"

SRC="$ROOT/build/deps/fonts-src"
PREFIX="$ROOT/build/deps/fontconfig/$ABI"
WORK="$ROOT/build/deps/fonts-work/$ABI"

mkdir -p "$SRC" "$PREFIX" "$WORK"

fetch() {
  local url="$1" file="$2"
  if [ ! -f "$SRC/$file" ]; then
    echo "==> downloading $file"
    curl -fsSL --retry 3 -o "$SRC/$file" "$url"
  fi
}

fetch "https://download.savannah.gnu.org/releases/freetype/freetype-2.13.2.tar.xz" freetype-2.13.2.tar.xz
fetch "https://github.com/libexpat/libexpat/releases/download/R_2_6_2/expat-2.6.2.tar.gz" expat-2.6.2.tar.gz
fetch "https://www.freedesktop.org/software/fontconfig/release/fontconfig-2.14.2.tar.xz" fontconfig-2.14.2.tar.xz

extract() {
  local file="$1"
  local dir="$WORK/${file%%.tar.*}"
  if [ ! -d "$dir" ]; then
    tar xf "$SRC/$file" -C "$WORK"
  fi
  echo "$dir"
}

COMMON_ENV=(
  "CC=$CC" "CXX=$CXX" "AR=$AR" "RANLIB=$RANLIB" "STRIP=$STRIP"
  "CFLAGS=-O2 -fPIC" "CXXFLAGS=-O2 -fPIC"
)

# fontconfig's build needs gperf (a host tool). Without sudo, build it locally.
GPERF_PREFIX="$ROOT/build/deps/tools/gperf"
if ! command -v gperf >/dev/null 2>&1; then
  if [ ! -x "$GPERF_PREFIX/bin/gperf" ]; then
    fetch "https://ftp.gnu.org/gnu/gperf/gperf-3.1.tar.gz" gperf-3.1.tar.gz
    GPERF_DIR="$WORK/gperf-3.1"
    [ -d "$GPERF_DIR" ] || tar xf "$SRC/gperf-3.1.tar.gz" -C "$WORK"
    echo "==> building gperf (host)"
    ( cd "$GPERF_DIR" && ./configure --prefix="$GPERF_PREFIX" >/dev/null \
        && make -j4 >/dev/null && make install >/dev/null )
  fi
fi
export PATH="$GPERF_PREFIX/bin:$PATH"

echo "==> building expat ($ABI)"
EXPAT_DIR="$(extract expat-2.6.2.tar.gz)"
# Always configure: freetype ships a top-level Makefile in its tarball, so a
# presence check would wrongly skip configure.
( cd "$EXPAT_DIR" && env "${COMMON_ENV[@]}" ./configure \
    --host="$TRIPLE" --prefix="$PREFIX" --disable-shared --enable-static --without-docbook ) >/dev/null
( cd "$EXPAT_DIR" && make -j4 >/dev/null && make install >/dev/null )

echo "==> building freetype ($ABI)"
FT_DIR="$(extract freetype-2.13.2.tar.xz)"
( cd "$FT_DIR" && env "${COMMON_ENV[@]}" ./configure \
    --host="$TRIPLE" --prefix="$PREFIX" \
    --with-zlib=no --with-png=no --with-harfbuzz=no --with-brotli=no \
    --disable-shared --enable-static ) >/dev/null
( cd "$FT_DIR" && make -j4 >/dev/null && make install >/dev/null )

echo "==> building fontconfig ($ABI)"
FC_DIR="$(extract fontconfig-2.14.2.tar.xz)"
( cd "$FC_DIR" && env "${COMMON_ENV[@]}" \
    FREETYPE_CFLAGS="-I$PREFIX/include/freetype2" \
    FREETYPE_LIBS="$PREFIX/lib/libfreetype.a" \
    EXPAT_CFLAGS="-I$PREFIX/include" \
    EXPAT_LIBS="$PREFIX/lib/libexpat.a" \
    LDFLAGS="-L$PREFIX/lib" \
    ./configure --host="$TRIPLE" --prefix="$PREFIX" \
    --disable-docs --disable-nls --enable-shared --disable-static \
    --with-cache-dir=/tmp/fontconfig-cache ) >/dev/null
( cd "$FC_DIR" && make -j4 >/dev/null && make install >/dev/null )

echo
echo "==> output:"
ls -l "$PREFIX"/lib/libfontconfig.so*
