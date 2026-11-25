# Android cross-compilation environment. Source this file before configure.
# Overridable: ANDROID_NDK_HOME / ANDROID_API / TARGET_JDK
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export UMC_ROOT="$ROOT"

# Host clang (required as the build compiler): if it is not on PATH, use the
# highest installed LLVM version from /usr/lib/llvm-*.
if ! command -v clang >/dev/null 2>&1; then
  HOST_CLANG_BIN="$(ls -d /usr/lib/llvm-*/bin 2>/dev/null | sort -V | tail -1)"
  if [ -n "$HOST_CLANG_BIN" ]; then
    export PATH="$HOST_CLANG_BIN:$PATH"
  fi
fi

export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-ndk-r30}"
export TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export ANDROID_INCLUDE="$TOOLCHAIN/sysroot/usr/include"
export ANDROID_API="${ANDROID_API:-26}"
export TARGET_JDK="${TARGET_JDK:-aarch64}"
# TARGET does not include the API suffix: configure uses aarch64-linux-android,
# while the actual compiler is aarch64-linux-android${ANDROID_API}-clang.
export TARGET="${TARGET_JDK}-linux-android"

# Use the NDK clang directly (OpenJDK 21 supports --with-toolchain-type=clang).
export CC="$TOOLCHAIN/bin/${TARGET}${ANDROID_API}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}${ANDROID_API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export AS="$TOOLCHAIN/bin/llvm-as"
export LD="$TOOLCHAIN/bin/ld"
export NM="$TOOLCHAIN/bin/llvm-nm"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"

export BUILD_BASE="$ROOT/build"
export DEPS_DIR="$BUILD_BASE/deps"
export SRC_DIR="$BUILD_BASE/upstream/jdk21u"
export DUMMY_LIBS="$DEPS_DIR/dummy_libs"
