# Android build

Targets: `aarch64-linux-android26` and `x86_64-linux-android26`, NDK clang,
HotSpot Server VM, headless JDK.

**Why API 26 (Android 8.0)**: Bionic only provides some of the interfaces the JDK needs
in newer releases (`dlvsym`/`getifaddrs` from 24, `shmget` from 26). Targeting 26 keeps the
port patch small, and devices that can run Minecraft are effectively Android 8.0+ anyway.
Supporting older releases would require compatibility shims for those interfaces.

## Prerequisites

- NDK at `/opt/android-ndk-r30` (override with `ANDROID_NDK_HOME`)
- Host clang at `/usr/lib/llvm-14/bin` (build compiler; `env.sh` adds it to `PATH` if needed)
- `autoconf`, `make`, `zip/unzip/file`
- Host X11 headers (headless libawt still includes X11 headers, but does **not** link libX11).
  `configure.sh` copies `/usr/include/X11` into `build/deps/x11-include`:
  `sudo apt install libx11-dev libxrender-dev libxrandr-dev libxext-dev libxi-dev libxtst-dev`
- Host fontconfig headers (`fontconfig/fontconfig.h`) for `fontpath.c` compilation; the
  runtime loads libfontconfig via `dlopen` and falls back gracefully if absent.
  `sudo apt install libfontconfig1-dev`
- Boot JDK: `/usr/lib/jvm/java-21-openjdk-amd64`

Run `bash tools/preflight.sh` first.

## Steps

```bash
# 1. Fetch the pinned upstream (shallow clone into build/upstream/jdk21u)
bash platforms/android/scripts/fetch-upstream.sh

# 2. Apply this repository's Android patches
bash platforms/android/scripts/apply-patches.sh

# 3. Configure (arm64 is the default; use TARGET_JDK=x86_64 for the emulator ABI)
bash platforms/android/scripts/configure.sh

# 4. Build (lower JOBS when memory is tight)
JOBS=4 bash platforms/android/scripts/build.sh images

# 5. Cross-build fontconfig and inject it into the JRE (AWT fonts)
bash platforms/android/scripts/build-fontconfig.sh arm64-v8a
bash platforms/android/scripts/build-fontconfig.sh x86_64
bash platforms/android/scripts/install-fontconfig.sh
```

The x86_64 build uses a separate configuration:

```bash
TARGET_JDK=x86_64 bash platforms/android/scripts/configure.sh
make CONF=linux-x86_64-server-release images JOBS=4
```

## Outputs

- Full JDK image: `build/upstream/jdk21u/build/linux-aarch64-server-release/images/jdk/`
  (~230 MB; the x86_64 build has its own `images/` directory)
- Trimmed runtime (jlink): `build/out/jre-android` (arm64) and
  `build/out/jre-android-x86_64` (x86_64)

`bin/java` is an aarch64/x86_64 PIE with interpreter `/system/bin/linker64`;
`lib/server/libjvm.so` only needs `libm.so`, `libdl.so` and `libc.so`.

Trimmed runtime modules:

```
java.base java.datatransfer java.xml java.prefs java.desktop java.instrument
java.logging java.management java.security.sasl java.naming java.net.http
jdk.crypto.ec jdk.unsupported jdk.zipfs
```

Note: `jlink --strip-debug` fails on the host because the host `objcopy` does not understand
aarch64. The repository does not use that option when producing the JRE.

## Patch overview

All port changes live in a single patch, `platforms/android/patches/0001-android-port.patch`,
based on `jdk-21.0.14+3`. It will be split into logical patches once the port stabilises.

### Android target detection and desktop dependencies

OpenJDK classifies `aarch64-linux-android` as Linux and therefore requires ALSA, X11,
fontconfig and CUPS, none of which exist in the NDK sysroot.

- `LIB_DETERMINE_DEPENDENCIES` detects `android` via `$CC -dumpmachine` and sets
  `ANDROID_TARGET` (also exported to make through `spec.gmk.in`);
- X11 / fontconfig / CUPS / ALSA are disabled for Android;
- `java.desktop` builds `libjsound` with the AAudio backend instead of ALSA.

### Bionic header and interface differences

| Location | Problem | Fix |
| --- | --- | --- |
| `hotspot/utilities/elfFile.hpp` | No `ELF64_ST_TYPE` in Bionic; `<linux/elf.h>` defines `ELF_ST_TYPE` as a function-like macro | Guard the object-like redefinition with `#ifndef` |
| `os_linux.cpp` `libpthread_init` | Depends on `_CS_GNU_LIBC_VERSION` with an `#error` otherwise | Report `bionic - unknown` on Android |
| `os_linux.cpp` `dll_path` | Bionic has no `dlinfo(RTLD_DI_LINKMAP)` | Use `dladdr()` |
| `os_linux.cpp` `loadavg` | `getloadavg()` needs API 29 | Derive from `sysinfo().loads[]` |
| `os_posix.cpp` `fork_and_exec` | `posix_spawn()` needs API 28 | `fork()` + `execve("/system/bin/sh")` |
| `java.base` `ProcessImpl_md.c` | `posix_spawn()` for `jspawnhelper` | `fork()` + `execve(UMC_JSPAWNHELPER or helperpath)` |
| `java.base` `jni_util_md.c` | Binds glibc's `__xpg_strerror_r` | Bind Bionic's POSIX `strerror_r` symbol |
| `java.instrument` `EncodingSupport_md.c` | `iconv_open()` needs API 28 | UTF-8 passthrough (Android platform encoding is UTF-8) |
| `jdk.jdwp.agent` `utf_util.c` | Same iconv issue | UTF-8 passthrough |
| HotSpot `jni.cpp` | scudo heap pointer tagging is truncated by HotSpot; JAR loading aborts | `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, NONE)` in `JNI_CreateJavaVM` |
| `java.desktop` libawt | Includes X11 headers (also headless) | Compile against host X11 headers only; do not link libX11 |
| `java.desktop` libfontmanager | `AWTFreeFont` lives in `libawt_headless.so`; Bionic does not resolve symbols globally | Link `-lawt_headless` explicitly on Android |
| `jdk.hotspot.agent` | `libsaproc` depends on glibc procfs | Keep the Java module, skip the native library |
| `java.desktop` libawt | CUPS printing | Exclude `CUPSfuncs.c` on Android |

### TLS model

NDK clang defaults to **emulated TLS** for Android targets, while HotSpot's assembly
`src/hotspot/os_cpu/linux_aarch64/threadLS_linux_aarch64.S` references native TLS symbols
(`Thread::_thr_current`). Mixing the two causes `libjvm.so` to fail loading with:

```
cannot locate symbol "_ZN6Thread12_thr_currentE"
```

The build therefore uses `-fno-emulated-tls` globally. Native TLS in `dlopen`ed libraries is
supported on arm64 Android from API 23.

### Library names

Bionic folds pthread and librt into libc; there is no `-lpthread` or `-lrt`:

- configure defines `LIBPTHREAD`, empty on Android;
- `java.base`, `jdk.jdwp.agent` and `jdk.sctp` use `$(LIBPTHREAD)`;
- `BASIC_JVM_LIBS` no longer appends `-lpthread` / `-lrt`.

`ld.lld` from the NDK also rejects mapfile entries for undefined symbols (local class vtables,
for example), where GNU ld only warns, so linking uses `-Wl,--undefined-version`.

### RPATH vs RUNPATH

The Android linker **ignores `DT_RPATH`** and only honours `DT_RUNPATH`. OpenJDK deliberately
passes `-Wl,--disable-new-dtags` on clang/Linux (JDK-8326891) to prevent `LD_LIBRARY_PATH`
from overriding internal JDK libraries. `flags-cflags.m4` keeps the new dtags for Android
targets; otherwise `bin/java` fails with:

```
library "libjli.so" not found: needed by main executable
```

### fontconfig, javax.sound and Process

See `docs/android-aar.md`; all three are shipped inside the JRE/AAR.

## Device verification (2026-11-25)

Device: F2, Android 13 (API 33), arm64-v8a. Runtime extracted to `/data/local/tmp/jre-android`.

```
$ ./jre-android/bin/java -version
openjdk version "21.0.14-umc" 2027-01-19
OpenJDK Runtime Environment (build 21.0.14-umc)
OpenJDK 64-Bit Server VM (build 21.0.14-umc, mixed mode)

$ ./jre-android/bin/java -Djava.io.tmpdir=/data/local/tmp -cp /data/local/tmp/umc-tests HelloWorld
Hello from OpenJDK on Android!
java.version = 21.0.14-umc
java.vm.name  = OpenJDK 64-Bit Server VM
os.name      = Linux
os.arch      = aarch64
file.encoding= UTF-8
sum(1..1e6)  = 500000500000
thread ok: Thread-0
file io      = umc mobile openjdk
SMOKE TEST PASSED
```

Covered: class loading, Server VM, threads, file I/O, UTF-8 encoding.

### Version string

By default configure generates `adhoc.<username>.<basedir>` (leaks build-host information and
is not reproducible). `configure.sh` pins `--with-version-string=21.0.14-umc`; override with
`VERSION_STRING`.

## Known trade-offs

- **headless**: no GUI; `java.awt` is only available in headless mode. Minecraft rendering is
  done by LWJGL/OpenGL and does not need the AWT windowing system.
- **javax.sound**: output-only AAudio backend; capture, ports and MIDI are not implemented.
- **jdk.hotspot.agent**: the Java module is kept (the module graph needs it), but the native
  `libsaproc` is not built. The Serviceability Agent needs glibc procfs and is not useful on Android.
- **Printing (CUPS)**: no print stack on Android; `CUPSfuncs.c` is excluded from headless AWT.
- **Process API**: works through `jspawnhelper` shipped as an APK native library; see
  `docs/android-aar.md`.
