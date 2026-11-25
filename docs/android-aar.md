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
| `libumc_jvm.so` | `jni/<abi>/` | JNI bridge, `DT_NEEDED: libjvm.so` |
| `lib/modules`, `conf`, `legal`, `lib/*.so` | `assets/jre/<abi>/` | Runtime data, extracted on first launch |

Two ABIs are supported: `arm64-v8a` (devices) and `x86_64` (emulators). At runtime
`Build.SUPPORTED_ABIS` selects which asset tree to extract into `files/jre-<abi>`.

## Runtime flow

1. On first use `UmcJvm.extractJreForHost(...)` extracts `assets/jre/<abi>` into **app-private
   storage** (`context.getFilesDir()`). This only needs normal app permissions; no root.
   The extraction stamp is `PackageInfo.lastUpdateTime`, so app updates force a re-extract
   (otherwise stale `libjava`/`libjvm` would keep being used).
2. `UmcJvm.create(...)`:
   - the bridge sets `UMC_JAVA_HOME` because libjvm is loaded from the APK and HotSpot can no
     longer derive `java.home` from its own path;
   - `JNI_CreateJavaVM` starts HotSpot inside the host process while ART keeps running;
   - the caller uses `callStatic` / `destroy` afterwards.
3. Nothing is executed as a subprocess from app storage, nothing is written to system
   directories and `/data/local/tmp` is not used. This complies with Android 10+ W^X rules and
   ordinary user permissions.

## Test Demo behavior

The demo app is labelled **Test Demo**. On launch it extracts the JRE, creates the JVM and
runs the smoke checks (JVM info, fonts, sound, Process). If every check succeeds it plays
`assets/test.mp3` (a bundled one-second test tone); if any check fails the audio is skipped
and the failure is shown on screen.

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
