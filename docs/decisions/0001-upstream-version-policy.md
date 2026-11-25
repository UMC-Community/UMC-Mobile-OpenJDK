# ADR-0001: Upstream and version policy

- Status: **proposed**
- Date: 2026-11-25

## Context

UMC needs Java 21 (Minecraft 1.20.5+ requires Java 21). The platform reality
(see `docs/upstream.md`):

- `openjdk/jdk21u` is a stable 21 LTS line with no mobile ports.
- `openjdk/mobile` provides an iOS port but tracks JDK 28-dev; there is no 21 branch and no tags.
- Android requires a self-maintained port (Bionic patches).

A single upstream covering both Android 21 and iOS does not exist. A trade-off between
version and platform is unavoidable.

## Options

### A. Java 21 only, iOS deferred (recommended initially)

- Android: `jdk21u @ jdk-21.0.14+3` + our Android patch set.
- iOS: directory and documentation only; no code investment until macOS is available.
- Pros: single version, predictable library compatibility with UMC; the Android line can be
  completed first.
- Cons: no iOS output in the short term.

### B. Both lines on openjdk/mobile master (JDK 28-dev)

- Pros: the iOS port exists; one source tree — a true "shared upstream".
- Cons: JDK 28 is a development version with unknown UMC/Minecraft/mod compatibility;
  Android still has to be done; upstream moves daily and patches keep breaking;
  violates the Java 21 product constraint.

### C. Two upstreams in parallel: Android = 21, iOS = 28

- Pros: each platform starts from its easiest base and can progress independently.
- Cons: two JDKs, two class libraries; the "unified Runtime SDK" story weakens; the build and
  release matrix doubles.

## Decision

Adopt **A** for now. Re-evaluate **B** or **C** once the iOS goal is confirmed and a macOS
environment exists. Rationale: Android is UMC's primary platform; if an iOS phase one happens,
its goal should be a general-purpose Java runtime rather than running Minecraft, which makes
the 28-dev risk acceptable.

## Consequences

- The iOS entry in `upstream/upstream.json` stays `deferred` and is excluded from CI.
- Patch directories are per-platform (`platforms/android/patches`, `platforms/ios/patches`);
  `common/patches` holds only genuinely platform-independent changes.
