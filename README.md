# UMC Mobile OpenJDK

Native OpenJDK Java runtime infrastructure for Android (and, later, iOS).
UMC is the first consumer, but not the only one — the goal is a runtime SDK that other
applications can ship as well.

## Status

**Phase 1: Android cross-compilation.**

| Platform | Upstream | Version | Status |
| --- | --- | --- | --- |
| Android arm64 / x86_64 | [openjdk/jdk21u](https://github.com/openjdk/jdk21u) | `jdk-21.0.14+3` | ✅ Verified on device (F2 / Android 13 / arm64): `java -version`, HelloWorld, threads, file I/O, JNI embedding, real JAR, AAR embedding, **AWT fonts (fontconfig + `/system/fonts`)**, **javax.sound (AAudio playback)**, `java.lang.Process`. ✅ Verified on emulator (API 34 / x86_64 / SELinux enforcing): same checks, no `avc` denials (2026-11-25). |
| iOS arm64 | [openjdk/mobile](https://github.com/openjdk/mobile) | 28-dev (`c13417aa`) | Deferred: must be built on a macOS host. |

## Start here

1. [docs/environment.md](docs/environment.md) — build environment audit
2. [docs/upstream.md](docs/upstream.md) — upstream research
3. [docs/decisions/0001-upstream-version-policy.md](docs/decisions/0001-upstream-version-policy.md) — version policy
4. [docs/architecture.md](docs/architecture.md) — repository layout and build pipeline
5. [docs/android-aar.md](docs/android-aar.md) — AAR: embedding OpenJDK inside an app process
6. [docs/ios-plan.md](docs/ios-plan.md) — iOS implementation plan and shared components
7. [docs/licensing.md](docs/licensing.md) — licensing and redistribution obligations

## Quick check

```bash
bash tools/preflight.sh
```
