# Build environment audit

Audited: 2026-11-25 on the build host (Ubuntu 22.04 / kernel 6.8 / ZFS root).

## Current state

| Item | Measured | Required for the Android line | Status |
| --- | --- | --- | --- |
| Free disk | 21 GiB | ≥ 20 GB | ✅ |
| Available memory | ~6 GiB | ≥ 6 GB (8 GB preferred) | ✅ |
| CPU | 8 cores | sufficient (`make -j2` … `-j4`) | ✅ |
| NDK | `/opt/android-ndk-r30` | r26+ (r27 recommended) | ✅ |
| clang (host) | `/usr/lib/llvm-14/bin` | required for the build compiler | ✅ |
| autoconf | installed | required by configure | ✅ |
| gcc / make / git | gcc 11.4 / make 4.3 / git 2.34 | sufficient | ✅ |
| Boot JDK | 21.0.13.1 | ≥ 20 (21 is fine for 21u) | ✅ |
| adb | platform-tools, device connected | needed for device tests | ✅ |
| macOS | none | required to build iOS | ❌ iOS line blocked |

## Disk

The initial audit found only 5.9 GB free; after cleanup the working set fits comfortably.
Approximate budget for one ABI: upstream checkout ~1 GB, NDK ~1.1 GB (already present),
build tree 4–6 GB, plus AAR/APK artifacts. Two ABIs (arm64-v8a + x86_64) roughly double the
build tree. Keep at least 8 GB of headroom for rebuilds.

## Memory

JDK builds peak at roughly 4–6 GB with `-j2` and 8 GB+ with `-j4`. Close browsers or limit
`JOBS` during builds. Swap should not be exhausted before starting.

## Missing host dependencies (Ubuntu)

```bash
sudo apt install autoconf zip unzip file libx11-dev libxext-dev libxrender-dev \
  libxrandr-dev libxtst-dev libxt-dev libcups2-dev libfontconfig1-dev libasound2-dev
```

The NDK is expected at `/opt/android-ndk-r30` (or set `ANDROID_NDK_HOME`).

## Conclusion

The host is sufficient for the Android line. iOS builds require a macOS machine with Xcode
and are out of scope for this host.
