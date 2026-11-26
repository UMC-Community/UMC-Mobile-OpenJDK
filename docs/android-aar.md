# Android AAR: embedding OpenJDK inside an app process

`android/` is a standalone Gradle project:

```
android/
├── umc-jvm/      library module (produces the AAR)
│   ├── src/main/jni/umc_jvm.c         JNI bridge: create / callStatic / destroy
│   ├── src/main/java/.../UmcJvm.java  public Java API + asset extraction
│   ├── src/main/jniLibs/arm64-v8a/    libjvm.so and libjspawnhelper.so (generated)
│   └── src/main/assets/jre/<abi>/     the rest of the JRE (generated)
└── demo-app/     "Test Demo": minimal verification app (logcat tag UMCJVM)
```

## AAR contents

| Content | Location | Notes |
| --- | --- | --- |
| `libjvm.so` | `jni/<abi>/` | HotSpot, loaded as a normal APK native library |
| `libjspawnhelper.so` | `jni/<abi>/` | `jspawnhelper` renamed so it is unpacked into `nativeLibraryDir` |
| `libumc_jvm.so` | `jni/<abi>/` | JNI bridge and C API, `DT_NEEDED: libjvm.so` |
| `lib/modules`, `conf`, `legal`, `lib/*.so` | `assets/jre/<abi>/` | Runtime data, extracted on first launch |

Two ABIs are supported: `arm64-v8a` (devices) and `x86_64` (emulators). At runtime
`Build.SUPPORTED_ABIS` selects which asset tree to extract into `files/jre-<abi>`.

## Runtime flow

1. On first use `UmcJvm.extractJreForHost(...)` extracts `assets/jre/<abi>` into **app-private
   storage** (`context.getFilesDir()`). This only needs normal app permissions; no root.
   The extraction stamp is `PackageInfo.lastUpdateTime`, so app updates force a re-extract
   (otherwise stale `libjava`/`libjvm` would keep being used).
2. `UmcJvm.create(...)` / `umc_jvm_create(...)`:
   - the bridge sets `UMC_JAVA_HOME` because libjvm is loaded from the APK and HotSpot can no
     longer derive `java.home` from its own path;
   - `JNI_CreateJavaVM` starts HotSpot inside the host process while ART keeps running;
   - the caller uses `callStatic` / `startMain` / `destroy` afterwards.
3. Nothing is executed as a subprocess from app storage, nothing is written to system
   directories and `/data/local/tmp` is not used. This complies with Android 10+ W^X rules and
   ordinary user permissions.

## Native C API (`umc_jvm.h`)

`libumc_jvm.so` exports a stable C surface for native embedders (UMC Core is the first one):

| Function | Purpose |
| --- | --- |
| `umc_jvm_create(java_home, class_path, max_heap_mb, options, count, spawn_helper)` | create the JVM in-process |
| `umc_jvm_start_main(main_class, args, count)` | run `main(String[])` on a detached native thread and return immediately |
| `umc_jvm_main_status()` | `-1` not started, `0` running, `1` finished, `2` failed |
| `umc_jvm_call_static(class, method, signature)` | call a static method returning `String`; release with `umc_jvm_free` |
| `umc_jvm_redirect_output(path)` | append the JVM's stdout/stderr to a file |
| `umc_jvm_is_created()` / `umc_jvm_destroy()` | lifecycle helpers |

The Java API in `UmcJvm.java` is a thin wrapper over the same entry points, so both paths share
one implementation. `JNI_CreateJavaVM` supports one VM per process, so the demo prefers the
UMC Core backend and only falls back to the Java wrapper when `libumc_android.so` is absent
(for example on an ABI without UMC Core).

## UMC Core launch abstraction

UMC Core now builds a single `JavaLaunchSpec` (java path, classpath, main class, JVM options,
game arguments, working directory, console log) and hands it to a backend:

| Backend | Platform | Mechanism |
| --- | --- | --- |
| `Process` | desktop | spawns the `java` executable; `GameProcess.pid` is the child pid |
| `Embedded` | Android | calls the `libumc_jvm` C API; `GameProcess.kind = embedded`, `pid = 0` |

Implementation notes:

- `crates/umc-core/src/launcher.rs` selects the backend from launch configuration; the embedded
  backend exists when the app sets `UMC_EMBEDDED_JAVA_HOME` (or `UMC_JAVA_HOME`) to the extracted
  JRE and `UMC_JSPAWNHELPER` to the packaged spawn helper. `StartEmbeddedJvm`-style hosts can
  instead call `umc_core::start_embedded_jvm(...)` directly.
- `crates/umc-android/build.rs` links `libumc_jvm.so` from `UMC_JVM_LIB_DIR`.
- Process state for embedded launches comes from `umc_jvm_main_status()`: finished maps to
  `exited` (code 0), failed to `failed` (code -1), and "not started" means the JVM disappeared
  with its host process. Stopping an embedded JVM is not supported yet because it shares the
  app process; a shutdown hook is required first.

### Building UMC Core for Android

```bash
export UMC_JVM_LIB_DIR=<AAR>/jni/arm64-v8a     # contains libumc_jvm.so (and libjvm.so)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=<NDK>/aarch64-linux-android26-clang
export CC_aarch64_linux_android=<NDK>/aarch64-linux-android26-clang
export AR_aarch64_linux_android=<NDK>/llvm-ar
cargo build --target aarch64-linux-android --release -p umc-android
```

`libumc_android.so` then records `DT_NEEDED: libumc_jvm.so`; the app must package both
libraries in `jniLibs` for the same ABI. At runtime the Android linker resolves
`libumc_jvm.so` from `nativeLibraryDir`.

## UMC Core integration (initial)

UMC Core runs in the app process as a JNI cdylib built from
`UMC/crates/umc-android`. That crate depends on `umc-sdk`, `jni`, Tokio and
`reqwest` (rustls + webpki roots).

Build it from the UMC workspace with the NDK as linker:

```bash
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=<NDK>/aarch64-linux-android26-clang
export CC_aarch64_linux_android=<NDK>/aarch64-linux-android26-clang
export AR_aarch64_linux_android=<NDK>/llvm-ar
cargo build --target aarch64-linux-android --release -p umc-android
cp target/aarch64-linux-android/release/libumc_android.so \
   android/demo-app/src/main/jniLibs/arm64-v8a/
```

The demo calls `UmcCoreBridge.nativeVersion()` and `nativeVersions(dataDir)`.
It also calls `nativeEmbeddedLaunch(...)`, which starts the JVM through UMC Core's embedded
backend (Rust → `libumc_jvm` C API) instead of spawning a process. Verified on the F2 tablet:

```text
umc core: version 0.1.1
umc tcp : dns=2 addr(s); [2603:1061:14:189::1]:443=ok; 150.171.110.133:443=ok
umc https: status 200 OK
net(art) : https status 200
umc versions: 926 available, latest 26.4-pre-1
```

## Verification results 3: embedded launch through UMC Core (arm64 device)

```text
UMCJVM: creating JVM: home=.../jre-arm64-v8a heap=256MB options=6
UMCJVM: JNI_CreateJavaVM rc=0
UMCJVM: starting main: SmokeMain (0 args)
UMCJVM: main returned
UMCJVM: embedded launch (umc-core): status=1
UMCJVM: java says: Hello from JNI, java.version=21.0.14-umc, ...
UMCJVM: fonts:   families=192, stringWidth=124, ascent=19
UMCJVM: sound:   mixers=1, lineSupported=true, wrote=176400
UMCJVM: process: rc=0, out=hello-from-process
UMCJVM: main:    rc=0, status=1
UMCJVM: JVM destroyed
UMCJVM: DONE ✅
```

`jvm.log` shows the arguments reaching `main` on the detached thread:

```text
SmokeMain: args=[]
SmokeMain: thread=Thread-0
SmokeMain: java.version=21.0.14-umc
SmokeMain: args=[one, two]
SmokeMain: thread=Thread-1
```

Both Android targets (`aarch64-linux-android`, `x86_64-linux-android`) build with
`DT_NEEDED: libumc_jvm.so`; the desktop (`Process`) backend keeps its existing tests.

## Test Demo behavior

The demo app is labelled **Test Demo**. On launch it extracts the JRE, creates the JVM and
runs the smoke checks (JVM info, fonts, sound, Process). If every check succeeds it plays
`assets/test.mp3`, a bundled one-second **Boeing 737 windshear aural warning**. If the user
hears that alarm, audio output is verified. If any check fails, the audio is skipped and the
failure is shown on screen.

Note: the in-JVM `javax.sound` probe also emits a short 880 Hz tone before the alarm plays;
that tone verifies the AAudio backend, while the alarm is the user-facing audio check.

## AWT fonts: fontconfig shipped inside the JRE

Headless AWT's font manager (`sun.awt.FcFontManager`) depends on fontconfig, which Android
does not provide.

- `platforms/android/scripts/build-fontconfig.sh` cross-builds fontconfig 2.14.2 with freetype
  2.13.2 and expat 2.6.2 linked statically (it also builds a host `gperf` when missing).
- `install-fontconfig.sh` injects `lib/libfontconfig.so(.1)` and `conf/fonts.conf`
  (`<dir>/system/fonts</dir>`) into the JRE.
- The JNI bridge sets `FONTCONFIG_FILE` and `XDG_CACHE_HOME` (app-private cache directory).
- `libfontmanager.so` must link `-lawt_headless` on Android: `AWTFreeFont` lives in
  libawt_headless and Bionic does not resolve symbols globally.

Measured: `families=192, stringWidth=128, ascent=19` from `/system/fonts`.

## javax.sound: AAudio backend

`libjsound` is enabled on Android with Ports/MIDI compiled out and the ALSA implementation
excluded. `src/java.desktop/android/native/libjsound/PLATFORM_API_AndroidOS_AAudio_PCM.c`
provides:

- one mixer and one output device (16-bit PCM, mono/stereo, 44.1/48 kHz);
- `DAUDIO_Write` blocking on an AAudio stream (`libaaudio.so`, API 26+);
- capture, ports and MIDI are not supported yet.

Measured: `mixers=1, lineSupported=true, wrote=176400` (one second of 44.1 kHz stereo).

## java.lang.Process support

Android 10+ forbids `execve` in app data directories, so `jspawnhelper` cannot run from the
extracted JRE. Instead:

- `jspawnhelper` is packaged as **`lib/<abi>/libjspawnhelper.so`**;
- the app enables `jniLibs.useLegacyPackaging = true`, so libraries are unpacked into
  `nativeLibraryDir` (`apk_data_file`, execution allowed);
- the JNI bridge passes the path via `UMC_JSPAWNHELPER`, and the Android branch of
  `ProcessImpl_md.c` prefers it.

Measured on Android 14 / SELinux enforcing:

```text
process:
  rc=0, out=hello-from-process
```

## Verification results 2: Android 14 x86_64 emulator (SELinux enforcing)

The same multi-ABI APK:

```text
UMCJVM: host ABI: x86_64
UMCJVM: extracted in 1244ms -> .../files/jre-x86_64
UMCJVM: JNI_CreateJavaVM rc=0 in 250ms
UMCJVM: java says: Hello from JNI, java.version=21.0.14-umc, vm=OpenJDK 64-Bit Server VM, maxMemory=256MB, processors=4
UMCJVM: fonts:   families=192, stringWidth=128, ascent=19
UMCJVM: sound:   mixers=1, lineSupported=true, wrote=176400
UMCJVM: process: rc=0, out=hello-from-process
UMCJVM: JVM destroyed
UMCJVM: audio: all checks passed, playing test.mp3
UMCJVM: audio: playing test.mp3 ✅
UMCJVM: audio: playback finished
```

- `getenforce` = **Enforcing**
- the app process stays alive and `jvm.log` is empty
- **no `avc: denied` entries**

Conclusion: Android 14's "safer dynamic code loading" does not affect this design — we use
native `dlopen`, not ART's DEX/JAR loading, and files live in app-private storage. The
`/proc/stat` and cgroup denials seen on the phone are `dontaudit` rules that only appear in
permissive mode; HotSpot tolerates the failures.

## Verification results 1: F2 / Android 13 / arm64

```text
UMCJVM: extracting JRE from assets...
UMCJVM: extracted in 19ms -> /data/user/0/dev.umc.mobile.jvmdemo/files/jre
UMCJVM: creating JVM: home=... heap=256MB options=6
UMCJVM: JNI_CreateJavaVM rc=0 in 358ms
UMCJVM: java says: Hello from JNI, java.version=21.0.14-umc, vm=OpenJDK 64-Bit Server VM, maxMemory=256MB, processors=4
UMCJVM: JVM destroyed
UMCJVM: DONE
```

Artifacts: AAR 81 MB, demo APK 86 MB (two ABIs). The host process survives after
`DestroyJavaVM`; ART and HotSpot coexist.

## Note: do not use root/permissive devices as an acceptance baseline

The debugging device is rooted and runs SELinux in `permissive` mode, which allows accesses
that a stock device would deny (for example `/proc/stat`). Acceptance must happen on a
**non-root device** or a stock emulator, and `avc: denied` lines in `adb logcat` are the
signal to inspect whether HotSpot's system calls are portable.

The emulator used above (API 34, `google_apis`, x86_64) runs with SELinux enforcing and
produces no denials.

## Build

```bash
# 1. Build and jlink the JRE (see docs/android-build.md)
# 2. Prepare assets and native libraries
bash platforms/android/scripts/package-aar-assets.sh
# 3. Build the AAR and the demo APK
cd android && gradle assembleDebug

# Artifacts
android/umc-jvm/build/outputs/aar/umc-jvm-debug.aar
android/demo-app/build/outputs/apk/debug/demo-app-debug.apk
```

Verification:

```bash
adb install -r android/demo-app/build/outputs/apk/debug/demo-app-debug.apk
adb shell am start -n dev.umc.mobile.jvmdemo/.MainActivity
adb logcat -s UMCJVM
# JVM stderr is redirected into the app-private jvm.log:
adb shell run-as dev.umc.mobile.jvmdemo cat files/jvm.log
```
