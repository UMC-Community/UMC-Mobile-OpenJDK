# Android graphics stack (desktop OpenGL for Minecraft)

Minecraft 1.17+ requires a **desktop OpenGL 3.2 core** context. Android devices only expose
OpenGL ES and Vulkan, so the runtime provides desktop GL through Mesa's OSMesa and a GLFW null
platform, keeping the game and LWJGL unmodified at the Java level.

## Components

| Component | Version | Build | Role |
| --- | --- | --- | --- |
| Mesa | 23.3.6 | meson, NDK r30, `-Dgallium-drivers=swrast -Dllvm=disabled -Dosmesa=true` | `libOSMesa.so`: desktop GL 3.3 core via softpipe |
| libffi | 3.4.4 | autotools, `--disable-exec-static-tramp` | callbacks for LWJGL and JNA |
| LWJGL core | 3.3.3 | NDK clang, sources from `lwjgl3-3.3.3` | `liblwjgl.so`, 1798 JNI exports |
| LWJGL opengl module | 3.3.3 | generated JNI sources | `liblwjgl_opengl.so` |
| LWJGL stb/tinyfd | 3.3.3 | generated JNI sources | `liblwjgl_stb.so`, `liblwjgl_tinyfd.so` |
| GLFW | 3.4 | CMake, null platform only | `libglfw.so` with OSMesa contexts |
| FreeType | 2.13.2 | already built for the JRE | `libfreetype.so` for `lwjgl-freetype` |
| OpenAL Soft | 1.23.1 | CMake, OpenSL backend | `libopenal.so` for `lwjgl-openal` |
| JNA | 5.17.0 | sources + NDK | `libjnidispatch.so` for oshi (Minecraft hardware info) |

All libraries are built for `aarch64-linux-android26` and packaged in the demo APK's
`jniLibs/arm64-v8a`.

## GLFW null platform patches

Upstream GLFW 3.4's null backend is close to what Android needs; four small patches make it
usable:

1. `platform.c`: when no other backend is compiled in, select the null platform instead of
   failing. Minecraft calls `glfwInit()` without a platform hint.
2. `osmesa_context.c`: add `libOSMesa.so` to the loader candidates (upstream only probes
   `libOSMesa.so.8`/`.so.6`), and ignore `GLFW_OPENGL_FORWARD_COMPAT` (OSMesa has no such
   flag; rejecting it stops Minecraft at `glfwCreateWindow`).
3. `null_window.c`: fix the inverted height comparisons in `applySizeLimits`; otherwise
   `glfwSetWindowSizeLimits` reports a bogus framebuffer size and fires callbacks during
   `RenderSystem.initRenderer`, crashing with a `Window resize` NPE.
4. `umc_bridge.c`: export input injection (`umcGlfwInjectKey/Char/MouseButton/CursorPos/Scroll/
   Resize/Focus`) and framebuffer access (`umcGlfwFramebuffer`, `umcGlfwDesktopSize`).

## JVM configuration

The embedded launch adds:

```text
-Dorg.lwjgl.librarypath=<nativeLibraryDir>
-Dorg.lwjgl.glfw.libname=libglfw.so
-Dorg.lwjgl.opengl.libname=libOSMesa.so
-Dorg.lwjgl.util.NoChecks=true
-Djna.boot.library.path=<nativeLibraryDir>
-Duser.dir=<instance game directory>
```

`liborg.lwjgl.opengl.libname` matters: LWJGL falls back to `OSMesaGetProcAddress` when the loaded
OpenGL library has no `glXGetProcAddress`, so `GL.createCapabilities()` works directly against
OSMesa without a GLX shim.

`-Duser.dir` matters because the JVM inherits the Android process working directory (`/`);
Minecraft's relative paths (`logs/latest.log`) must resolve inside the instance's game directory.

## Verified

- `GL_VERSION=3.3 (Core Profile) Mesa 23.3.6`, `GL_RENDERER=softpipe`, GLSL 3.30.
- LWJGL memory JNI: `lwjgl 3.3.3+5, mem ok`.
- LWJGL + GLFW + OSMesa: `gl 3.3 (Core Profile) Mesa 23.3.6 / softpipe, pixel ok`.
- Minecraft 1.21.11 (offline, embedded JVM) reaches the running client:
  `Setting user`, `Backend library: LWJGL 3.3.3+5`, resource atlases created,
  `OpenAL initialized on device OpenSL`, `Sound engine started`. The process then keeps
  rendering the main menu into the OSMesa buffer.

## Still missing

- Framebuffer presentation: OSMesa renders into an RGBA buffer exposed by
  `umcGlfwFramebuffer`; it must be copied (row-flipped) to a `SurfaceView`.
- Input: Android touch/key events must be translated to GLFW key codes and injected.
- The embedded launch changes the process working directory to the instance game directory,
  which lets Minecraft's Log4j `logs/latest.log` appender work; earlier output was console-only.
- Performance: softpipe is a CPU rasterizer. llvmpipe (needs LLVM) or Zink (device Vulkan 1.1)
  are the follow-up options.
