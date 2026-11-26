package dev.umc.mobile.jvmdemo;

/**
 * Minimal JNI surface over UMC Core (Rust). The native library is built from
 * `UMC/crates/umc-android` and packaged as `libumc_android.so` for arm64-v8a.
 */
public final class UmcCoreBridge {
    private UmcCoreBridge() {
    }

    static {
        System.loadLibrary("umc_android");
    }

    /** UMC Core version string. */
    public static native String nativeVersion();

    /**
     * Lists available Minecraft versions as a JSON array.
     *
     * @param dataDir app-private directory used by UMC for metadata and instances
     */
    public static native String nativeVersions(String dataDir);

    /** Diagnostic: resolve a host and connect to each address. */
    public static native String nativeTcpProbe(String host, int port);

    /** Diagnostic: HTTPS GET through the same reqwest stack, with the error chain. */
    public static native String nativeHttpGet(String url);
}
