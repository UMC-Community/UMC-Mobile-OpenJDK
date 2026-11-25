# Architecture

## Goal

Not "two Java runtimes compiled for UMC", but "native Java runtime infrastructure based on
OpenJDK for Android and iOS". UMC is the first consumer. Deliverables should be usable by
third parties: AAR/JNI on Android, XCFramework/static libraries with Swift bindings on iOS.

## One repository, two platform lines, per-platform upstream pins

```
UMC-Mobile-OpenJDK/
├── docs/                    architecture, environment, upstream, ADRs
├── upstream/
│   └── upstream.json        pinned repository + ref + feature version per platform
├── common/
│   ├── patches/             platform-independent patches (quilt-style series)
│   ├── build-common/        shared configure/make helpers, version generation
│   └── tests/               HelloWorld, library smoke tests
├── platforms/
│   ├── android/
│   │   ├── patches/         Bionic / NDK / missing-library patches
│   │   ├── config/          configure arguments, env.sh (NDK paths)
│   │   ├── scripts/         fetch / build / package
│   │   ├── jni/             JNI_CreateJavaVM embedding samples and AAR skeleton
│   │   └── packaging/       trimmed JRE packaging rules
│   └── ios/                 deferred: placeholder directories and docs
├── runtime/
│   ├── modules.list         jlink module lists (per profile)
│   └── profiles/
├── ci/
├── tools/                   preflight.sh, patch queue tooling
└── build/                   not tracked: upstream checkouts and build output
```

**Why multiple upstreams are allowed**: `openjdk/mobile` is an iOS-only port tracking JDK 28-dev,
while `jdk21u` is the Java 21 LTS line. They cannot be merged into a single source tree
(see `docs/upstream.md`). What is shared is tooling, patch conventions, tests and delivery
formats — not the source tree. This trade-off is recorded in ADR-0001.

## Build pipeline (same shape for every platform)

1. `fetch`: shallow-clone the pinned upstream into `build/upstream/<name>`.
2. `patch`: apply `common/patches` then `platforms/<p>/patches` according to `series`.
3. `configure`: platform-specific `config/configure.args` plus environment (NDK, boot JDK).
4. `make images`: produce the JDK image.
5. `jlink`: trim to a compact runtime using `runtime/modules.list`.
6. `package`: Android → compressed JRE image (later AAR); iOS → static frameworks.
7. `smoke`: HelloWorld / library tests; on Android via `adb push` + `app_process` or a test APK.

## Key constraints

- **Android**: target HotSpot Server VM (C1/C2) and JNI embedding; Bionic lacks
  `libpthread`/`librt` and several glibc-only interfaces.
- **iOS**: JIT is restricted by the platform; phase one must not depend on JIT.
  Building requires macOS + Xcode, which this host does not have.
- **Java version**: 21 (Minecraft requirement) unless ADR-0001 says otherwise.
- **Licensing**: OpenJDK is GPLv2 + Classpath Exception; deliverables must comply
  (see `docs/licensing.md`, to be written).
