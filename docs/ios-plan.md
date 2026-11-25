# iOS plan

Status: **deferred**. Building requires a macOS host with Xcode; this repository's Linux
build host cannot produce iOS binaries. This document records the intended architecture and
what can be shared with the Android line.

## Constraints that shape the design

- **No JIT.** iOS forbids writable+executable memory for ordinary apps. `MAP_JIT` and the
  corresponding entitlement do not apply to a general-purpose JVM shipped in an App Store app.
  The iOS line therefore starts with an interpreter-only VM (Zero) and never depends on a JIT.
- **No `fork`/`exec`.** `java.lang.Process` cannot be implemented the way it is on Android
  (there is no `jspawnhelper` execution path). The Process API will be unsupported.
- **No dynamic loading of unsigned code.** All JVM native libraries must be part of the signed
  app bundle (static libraries or embedded frameworks). Extracting `.so` files from assets and
  `dlopen`ing them is not a valid iOS design.
- **App Store policy.** Apple prohibits apps from downloading and executing code (guideline
  2.5.2). Running downloaded Minecraft jars is a policy risk; sideloading/TestFlight or
  non-App-Store distribution must be decided before investing in a Minecraft-capable iOS build.
- **No AWT/X11 windowing.** iOS has no AWT peer; `java.awt` can only be used headless (image
  manipulation, font metrics). Minecraft rendering via LWJGL has no iOS backend; a Metal or
  OpenGL ES implementation would be a separate large project.

## Upstream choice

The only maintained iOS port is `openjdk/mobile`, which tracks JDK mainline (currently
28-dev) and has no Java 21 branch. Per ADR-0001 the iOS line uses its own pinned upstream:

| Platform | Upstream | Version |
| --- | --- | --- |
| iOS arm64 | `openjdk/mobile` (pinned commit) | 28-dev |

Backporting the mobile patches to `jdk21u` is technically possible but expensive and would
have to be redone for every upstream change. Phase one accepts the version split.

## Build approach (on macOS)

1. Install Xcode, Homebrew `autoconf`, and a JDK 24 for macOS (or build one first).
2. Download the Gluon mobile support zip (prebuilt `libffi` and `cups` for iOS).
3. Clone `openjdk/mobile` at the pinned commit.
4. Configure with `--openjdk-target=aarch64-macos-ios` and the static-image options documented
   in the repository's `building.md`; the exact `--with-jvm-variants` and static flags must be
   taken from that document on the build Mac.
5. Produce:
   - static JVM and JDK libraries (`libjvm.a`, `libjava.a`, ...);
   - the runtime image data (`lib/modules`, `conf`, `legal`), bundled as app resources.

## Integration architecture

```
Swift / ObjC app
   └── UMCMobileJVM (Objective-C++ wrapper)
        └── JNI_CreateJavaVM / CallStaticObjectMethod / DestroyJavaVM
             └── statically linked HotSpot (Zero) + JDK libraries
                  └── java.home = <app bundle>/jre (read-only resources)
```

- The wrapper exposes the same shape as the Android `UmcJvm` API:
  `create(javaHome, classPath, maxHeapMb, options)`, `callStatic(className, method, sig)`,
  `destroy()`.
- `java.home` points inside the signed app bundle; the modules image and `conf` are read-only
  resources, which iOS permits.
- No asset extraction, no native library loading from writable storage.
- The UMC Core (Rust) is compiled for `aarch64-apple-ios` and linked into the same app; it calls
  the JVM through the C wrapper, mirroring the Android Rust → JNI path.

## Platform equivalences

| Concern | Android | iOS |
| --- | --- | --- |
| VM | HotSpot Server (C1/C2) | Zero interpreter, no JIT |
| Native libraries | APK `jniLibs` + `dlopen` from JRE dir | statically linked into the app |
| Runtime data | assets extracted to app-private storage | bundled read-only resources |
| Bridge | JNI bridge inside an AAR | Objective-C++ wrapper inside an XCFramework |
| Process API | `jspawnhelper` as a native library | unsupported (no fork/exec) |
| Audio | AAudio (`libjsound`) | CoreAudio / AVAudioEngine backend to be written |
| Fonts | fontconfig + `/system/fonts` | CoreText integration to be written (or headless-only) |
| Graphics | headless AWT; LWJGL via OpenGL ES | headless AWT; no LWJGL backend |
| Version | Java 21 | JDK 28-dev (mobile pin) |

## What can be shared

Shared (platform-independent):

- Repository layout, patch-queue conventions, version manifest and ADR process.
- `common/tests` Java sources (HelloWorld, feature probes) compiled against each platform's JDK.
- The JNI bridge *design* and API surface (`create` / `callStatic` / `destroy`), with platform
  files for the Android-only parts (`UMC_JAVA_HOME`, heap-tagging `mallopt`, `jspawnhelper`,
  fontconfig environment).
- Runtime profile concept (`runtime/modules.list`) and packaging/versioning conventions.
- Documentation structure and CI patterns.
- UMC Core itself (Rust) and the Rust → C ABI boundary.

Not shared:

- OpenJDK source tree and patches (different upstream and versions).
- VM implementation and native runtime (HotSpot vs Zero, Bionic vs Darwin).
- Platform integration code (AAR/JNI vs XCFramework/ObjC++).
- Audio, font and graphics backends.
- Build flags and toolchains.

## Phases

1. **Bring-up on macOS**: build the iOS static image and run a HelloWorld from a minimal Swift
   app; verify `JNI_CreateJavaVM`, `callStatic`, `destroy`, threads and file I/O.
2. **Runtime completeness**: confirm module set, memory settings, CoreText/CoreAudio needs.
3. **UMC Core integration**: Rust core for iOS calling the JVM wrapper.
4. **Optional Minecraft work**: graphics backend, dynamic code policy and App Store strategy —
   only after phases 1–3 and an explicit product decision.

## Open questions

- Whether the iOS line will ever run the full Minecraft client or only a general Java runtime.
- Distribution channel (App Store vs sideload/TestFlight) and its effect on downloadable code.
- Whether to backport the iOS port to Java 21 later.
