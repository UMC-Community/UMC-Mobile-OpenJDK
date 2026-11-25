# Licensing

The JDK and the patches in this repository are derived from OpenJDK, licensed under
**GPLv2 + Classpath Exception** (see `LICENSE`). The port patch and any code that links into
the JVM inherit that license.

Bundled third-party components in the produced runtime:

| Component | Version | License |
| --- | --- | --- |
| fontconfig | 2.14.2 | MIT-style (fontconfig) |
| FreeType | 2.13.2 | FreeType License (FTL) or GPLv2 |
| Expat | 2.6.2 | MIT |
| AAudio | system | Android platform library, linked at runtime, not redistributed |

When distributing an AAR, APK or JRE image:

- include the license texts of the bundled components above;
- for GPLv2 code, provide the corresponding source (this repository plus the pinned upstream)
  as required by GPLv2;
- note that the AAR contains GPLv2 + Classpath Exception code, so applications embedding it
  must comply with those terms.
