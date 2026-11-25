package dev.umc.mobile.jvm;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Starts the OpenJDK HotSpot runtime inside an Android process.
 *
 * <p>The JRE is shipped as APK assets; the first call to {@link #extractJre} unpacks it into
 * app-private storage, which is then used as {@code java.home}. Nothing is executed from
 * writable storage, which keeps the design compatible with Android 10+ W^X restrictions.</p>
 */
public final class UmcJvm {
    private UmcJvm() {
    }

    static {
        // libumc_jvm.so depends on libjvm.so; both live under lib/<abi> in the APK.
        System.loadLibrary("umc_jvm");
    }

    /**
     * Starts the JVM. Call from a background thread: creation blocks for seconds.
     *
     * @return 0 on success, a JNI error code otherwise
     */
    public static native int create(String javaHome, String classPath, int maxHeapMb,
                                    String[] extraOptions, String spawnHelperPath);

    /**
     * Convenience overload that wires the {@code libjspawnhelper.so} shipped in the APK native
     * library directory, which enables {@code java.lang.Process} (app data is not executable,
     * APK native libraries are).
     */
    public static int create(Context context, String javaHome, String classPath, int maxHeapMb,
                             String[] extraOptions) {
        String helper = context.getApplicationInfo().nativeLibraryDir + "/libjspawnhelper.so";
        return create(javaHome, classPath, maxHeapMb, extraOptions, helper);
    }

    /**
     * Redirects the JVM's stdout/stderr to a file (useful when startup fails).
     */
    public static native void redirectOutput(String path);

    /**
     * Calls a no-argument static method returning {@code String}.
     *
     * @return the returned value, or {@code null} on failure
     */
    public static native String callStatic(String className, String methodName, String signature);

    public static native void destroy();

    /**
     * The ABI used by this process (matches {@code assets/jre/<abi>} and {@code jniLibs/<abi>}).
     */
    public static String hostAbi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) {
                return abi;
            }
        }
        return Build.SUPPORTED_ABIS[0];
    }

    /**
     * Extracts the JRE matching the current ABI.
     */
    public static File extractJreForHost(Context context, File destRoot) throws IOException {
        String abi = hostAbi();
        return extractJre(context, "jre/" + abi, new File(destRoot, "jre-" + abi));
    }

    /**
     * Extracts the JRE from assets into {@code dest} (idempotent).
     */
    public static File extractJre(Context context, String assetRoot, File dest) throws IOException {
        File stamp = new File(dest, ".extracted");
        String tag = updateTag(context);
        if (stamp.exists() && tag.equals(readSmall(stamp))) {
            return dest;
        }
        deleteRecursive(dest);
        copyAssetDir(context.getAssets(), assetRoot, dest);
        try (OutputStream os = new FileOutputStream(stamp)) {
            os.write(tag.getBytes(StandardCharsets.UTF_8));
        }
        return dest;
    }

    /**
     * Package install/update time, used as the extraction stamp so app updates force a fresh
     * extraction instead of keeping an older libjava/libjvm.
     */
    private static String updateTag(Context context) {
        try {
            return String.valueOf(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String readSmall(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Extracts a single asset file.
     */
    public static File extractAsset(Context context, String assetName, File dest) throws IOException {
        ensureParent(dest);
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        return dest;
    }

    private static void copyAssetDir(AssetManager am, String path, File out) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            ensureParent(out);
            try (InputStream in = am.open(path); OutputStream os = new FileOutputStream(out)) {
                copy(in, os);
            }
            return;
        }
        if (!out.exists() && !out.mkdirs()) {
            throw new IOException("mkdirs failed: " + out);
        }
        for (String child : children) {
            copyAssetDir(am, path + "/" + child, new File(out, child));
        }
    }

    private static void ensureParent(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
