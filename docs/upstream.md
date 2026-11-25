# Upstream research

Researched: 2026-11-25. All conclusions are based on the actual repository state at that time.

## openjdk/mobile: no longer a dual-platform (Android + iOS) port

| Fact | Evidence |
| --- | --- |
| master is JDK 28-dev | `make/conf/version-numbers.conf`: `DEFAULT_VERSION_FEATURE=28` ("Start of release updates for JDK 28", 2026-06-04) |
| Only master; no tags, no release branches | GitHub branches/tags are empty apart from master |
| Still active | Last push 2026-11-04; HEAD `c13417aa36f8f44755d0984b58aa735d0b41f233` |
| iOS is the only documented mobile target | README: build static image for iOS, `--openjdk-target=aarch64-macos-ios`, requires macOS + Gluon support zip (libffi, cups) + brew autoconf |
| Android support no longer exists | `src/hotspot/os/` contains only aix, bsd, linux, posix, windows; `android` appears only in build-system OS detection under `make/autoconf/` |

**Conclusion**: `openjdk/mobile` cannot serve as a shared upstream. It is "OpenJDK mainline +
iOS port patches". Those patches target JDK 28; backporting them to 21 is a large project and
upstream keeps moving.

## openjdk/jdk21u: the realistic starting point for Android

- Latest tag: `jdk-21.0.14+3` (`21.0.13.1+2` also works, matching the host boot JDK).
- Builds with the host JDK 21.0.13.1; no extra boot JDK needed.
- Requires the Android port patches maintained in this repository.

## Android port: start from upstream, maintain our own patches

This project does not import code or scripts from third-party mobile JDK ports.
Everything is derived from `jdk21u` plus the Android NDK.

Confirmed from upstream sources:

- OpenJDK 21u natively supports `--with-toolchain-type=clang`
  (`VALID_TOOLCHAINS_linux="gcc clang"`), so the NDK's
  `aarch64-linux-android26-clang` can be used directly — no compiler wrappers.
- `--openjdk-target=aarch64-linux-android` is canonicalised by the in-tree `config.sub` to
  `aarch64-unknown-linux-android`; the OS part contains `linux`, so OpenJDK treats it as Linux.
- Bionic differences are resolved by patches in this repository, driven by actual build and
  runtime failures.

### Bionic differences handled so far

| Area | Problem | Fix |
| --- | --- | --- |
| ELF macros | Bionic's `<elf.h>` has no `ELF64_ST_TYPE`; `<linux/elf.h>` defines `ELF_ST_TYPE` as a function-like macro | Guard the object-like redefinition with `#ifndef` |
| glibc checks | `_CS_GNU_LIBC_VERSION` / `_CS_GNU_LIBPTHREAD_VERSION` missing | Report `bionic - unknown` |
| `dlinfo` | Not provided by Bionic | Use `dladdr()` |
| `getloadavg` | API 29+ | Read `sysinfo().loads[]` |
| `posix_spawn` | API 28+ | `fork()` + `execve()` on Android |
| `iconv` | API 28+ | UTF-8 passthrough (Android platform encoding is UTF-8) |
| `-lpthread` / `-lrt` | Folded into libc | `LIBPTHREAD` configure variable, empty on Android |
| Heap tagging | scudo pointer tags get truncated by HotSpot | Disable heap tagging in `JNI_CreateJavaVM` |
| `__xpg_strerror_r` | glibc-only symbol | Bind Bionic's POSIX `strerror_r` |
| RPATH | Android linker ignores `DT_RPATH` | Keep new dtags (`DT_RUNPATH`) for Android |
| TLS model | NDK clang uses emulated TLS, HotSpot's aarch64 assembly uses native TLS | `-fno-emulated-tls` |
| X11 headers | Headless libawt still includes X11 headers | Host X11 headers for compilation only (no libX11 link) |
| fontconfig | Headless AWT requires fontconfig | Cross-build fontconfig, ship it inside the JRE |
| javax.sound | ALSA backend unavailable | AAudio playback backend |
| SA native lib | procfs interfaces missing | Keep the Java module, skip `libsaproc` |
| CUPS | No printing stack on Android | Exclude `CUPSfuncs.c` |
