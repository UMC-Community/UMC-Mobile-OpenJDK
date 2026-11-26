package dev.umc.mobile.jvmdemo;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import dev.umc.mobile.jvm.UmcJvm;

/**
 * Test Demo: extracts the JRE from assets, creates a JVM in-process and runs the smoke
 * checks. If every check passes, it plays assets/test.mp3; if anything fails, playback is
 * skipped and the failure is shown on screen.
 */
public class MainActivity extends Activity {
    private static final String TAG = "UMCJVM";

    private TextView output;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        output = new TextView(this);
        output.setTextSize(13);
        output.setTextColor(Color.parseColor("#E6E6E6"));
        output.setBackgroundColor(Color.parseColor("#101418"));
        output.setPadding(32, 32, 32, 32);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#101418"));
        scroll.addView(output);
        setContentView(scroll);

        append("UMC Mobile OpenJDK — test demo\n");
        append("host ABI: " + UmcJvm.hostAbi() + "\n");
        append("------------------------------\n");

        new Thread(() -> {
            boolean success = true;
            try {
                long t0 = System.currentTimeMillis();
                File jre = UmcJvm.extractJreForHost(this, getFilesDir());
                File jar = UmcJvm.extractAsset(this, "smoke.jar", new File(getFilesDir(), "smoke.jar"));
                File lwjglJar = UmcJvm.extractAsset(this, "lwjgl-core.jar",
                        new File(getFilesDir(), "lwjgl-core.jar"));
                File lwjglGlfwJar = UmcJvm.extractAsset(this, "lwjgl-glfw.jar",
                        new File(getFilesDir(), "lwjgl-glfw.jar"));
                File lwjglOpenglJar = UmcJvm.extractAsset(this, "lwjgl-opengl.jar",
                        new File(getFilesDir(), "lwjgl-opengl.jar"));
                String classPath = jar.getAbsolutePath() + ":" + lwjglJar.getAbsolutePath()
                        + ":" + lwjglGlfwJar.getAbsolutePath() + ":" + lwjglOpenglJar.getAbsolutePath();
                append(String.format("extracted JRE in %d ms\n  %s\n", System.currentTimeMillis() - t0, jre));

                String[] opts = {
                        "-Djava.io.tmpdir=" + getCacheDir().getAbsolutePath(),
                        "-Xrs",
                        "-XX:-UsePerfData",
                        "-Djava.library.path=" + getApplicationInfo().nativeLibraryDir,
                        "-Dorg.lwjgl.librarypath=" + getApplicationInfo().nativeLibraryDir,
                        "-Dorg.lwjgl.glfw.libname=libglfw.so",
                        "-Dorg.lwjgl.opengl.libname=libOSMesa.so",
                        "-Dorg.lwjgl.util.Debug=true",
                        "-Dorg.lwjgl.util.DebugLoader=true",
                        "-Dorg.lwjgl.util.NoChecks=true"
                };

                // Preferred path: UMC Core's embedded launch backend, which calls
                // the libumc_jvm C API from Rust. Falls back to the Java API when
                // libumc_android.so is not packaged for this ABI.
                boolean created = false;
                try {
                    String helper = new File(getApplicationInfo().nativeLibraryDir,
                            "libjspawnhelper.so").getAbsolutePath();
                    String launch = UmcCoreBridge.nativeEmbeddedLaunch(
                            jre.getAbsolutePath(), helper, classPath, "SmokeMain",
                            new File(getFilesDir(), "jvm.log").getAbsolutePath(), opts);
                    append("embedded launch (umc-core): " + launch + "\n");
                    created = launch.startsWith("status=1");
                    success &= created;
                    if (!created) {
                        append("\nFAILED: embedded JVM launch\n");
                    }
                } catch (Throwable t) {
                    append("embedded launch (umc-core) unavailable: " + t + "\n");
                }

                if (!created) {
                    UmcJvm.redirectOutput(new File(getFilesDir(), "jvm.log").getAbsolutePath());
                    t0 = System.currentTimeMillis();
                    int rc = UmcJvm.create(this, jre.getAbsolutePath(), classPath, 256, opts);
                    append(String.format("JNI_CreateJavaVM rc=%d in %d ms\n",
                            rc, System.currentTimeMillis() - t0));
                    if (rc != 0) {
                        append("\nFAILED: JVM creation rc=" + rc + "\n");
                        success = false;
                    } else {
                        created = true;
                    }
                }

                if (created) {
                    String result = UmcJvm.callStatic("HelloFromJni", "run", "()Ljava/lang/String;");
                    append("java says:\n  " + result + "\n");
                    success &= isGood(result);

                    String fonts = UmcJvm.callStatic("HelloFromJni", "runFonts", "()Ljava/lang/String;");
                    append("fonts:\n  " + fonts + "\n");
                    success &= isGood(fonts);

                    String sound = UmcJvm.callStatic("HelloFromJni", "runSound", "()Ljava/lang/String;");
                    append("sound:\n  " + sound + "\n");
                    success &= isGood(sound);

                    String proc = UmcJvm.callStatic("HelloFromJni", "runProcess", "()Ljava/lang/String;");
                    append("process:\n  " + proc + "\n");
                    success &= isGood(proc);

                    // LWJGL core natives built for Android (liblwjgl.so).
                    String lwjgl = UmcJvm.callStatic("LwjglProbe", "run", "()Ljava/lang/String;");
                    append("lwjgl:\n  " + lwjgl + "\n");
                    success &= lwjgl != null && lwjgl.startsWith("lwjgl 3.3.3");

                    // GLFW (null + OSMesa) and OpenGL through libOSMesa.
                    String gfx = UmcJvm.callStatic("GfxProbe", "run", "()Ljava/lang/String;");
                    append("gfx:\n  " + gfx + "\n");
                    success &= gfx != null && gfx.startsWith("gl 3.3");

                    // Exercises the C API path used by UMC Core on Android:
                    // start main() on a detached thread and poll its status.
                    String main = runMainCheck();
                    append("main:\n  " + main + "\n");
                    success &= main != null && main.startsWith("rc=0, status=1");

                    UmcJvm.destroy();
                    append("\nJVM destroyed\n");

                    // UMC Core (Rust) embedded in the same process, via JNI.
                    try {
                        append("umc core: version " + UmcCoreBridge.nativeVersion());
                        append("umc tcp : " + UmcCoreBridge.nativeTcpProbe(
                                "piston-meta.mojang.com", 443));
                        append("umc https: " + UmcCoreBridge.nativeHttpGet(
                                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
                        append("net(art) : " + artHttpGet(
                                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
                        String json = UmcCoreBridge.nativeVersions(
                                new File(getFilesDir(), "umc-data").getAbsolutePath());
                        if (json == null || json.startsWith("ERROR")) {
                            append("umc versions: " + json);
                        } else {
                            org.json.JSONArray arr = new org.json.JSONArray(json);
                            String latest = arr.length() > 0
                                    ? arr.getJSONObject(0).optString("id") : "-";
                            append("umc versions: " + arr.length() + " available, latest " + latest);
                        }
                    } catch (Throwable t) {
                        append("umc core: unavailable (" + t + ")");
                    }
                }
            } catch (Throwable t) {
                success = false;
                Log.e(TAG, "FAILED", t);
                append("\nFAILED: " + t + "\n");
            }

            final boolean allGood = success;
            if (allGood) {
                append("\nDONE ✅");
                append("audio: all checks passed, playing test.mp3");
                append("audio: if you hear the Boeing 737 windshear alarm,");
                append("audio: your audio output is working ✅");
                main.post(this::playSuccessAudio);
            } else {
                append("\nDONE ❌");
                append("audio: skipped because a check failed");
            }
        }, "umc-jvm-smoke").start();
    }

    private static boolean isGood(String value) {
        return value != null && !value.contains("FAILED");
    }

    /**
     * Starts SmokeMain through the C API surface and waits for it to finish.
     */
    private static String runMainCheck() {
        int rc = UmcJvm.startMain("SmokeMain", new String[]{"one", "two"});
        if (rc != 0) {
            return "rc=" + rc + " (start failed)";
        }
        long deadline = System.currentTimeMillis() + 5000;
        int status = UmcJvm.mainStatus();
        while (status == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            status = UmcJvm.mainStatus();
        }
        return "rc=0, status=" + status;
    }

    /**
     * ART-side HTTPS probe used as a network baseline for the Rust-side probes.
     */
    private static String artHttpGet(String url) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            c.disconnect();
            return "https status " + code;
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }

    /**
     * Plays assets/test.mp3 only after every smoke check succeeded.
     */
    private void playSuccessAudio() {
        try {
            File out = new File(getCacheDir(), "test.mp3");
            try (InputStream in = getAssets().open("test.mp3");
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }

            if (player != null) {
                player.release();
                player = null;
            }
            player = new MediaPlayer();
            player.setDataSource(out.getAbsolutePath());
            player.setOnCompletionListener(mp ->
                    append("audio: alarm finished — audio passed if you heard it"));
            player.prepare();
            player.start();
            append("audio: playing windshear alarm ...");
            Log.i(TAG, "audio: Boeing 737 windshear alarm playing");
        } catch (Throwable t) {
            append("audio: playback failed: " + t);
            Log.e(TAG, "audio playback failed", t);
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private void append(String line) {
        Log.i(TAG, line.trim());
        main.post(() -> output.append(line + "\n"));
    }
}
