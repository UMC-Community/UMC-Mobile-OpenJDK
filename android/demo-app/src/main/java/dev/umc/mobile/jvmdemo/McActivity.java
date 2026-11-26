package dev.umc.mobile.jvmdemo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import dev.umc.mobile.jvm.UmcJvm;

/**
 * Minecraft bring-up harness: installs a vanilla version through UMC Core and
 * launches it on the embedded JVM using the Android-built LWJGL/GLFW/OSMesa
 * stack. Start it with:
 *
 * <pre>
 * adb shell am force-stop dev.umc.mobile.jvmdemo
 * adb shell am start -n dev.umc.mobile.jvmdemo/.McActivity \
 *     --es version 1.21.11 --es username UMCPlayer
 * </pre>
 */
public class McActivity extends Activity {
    private static final String TAG = "UMCJVM";

    private TextView output;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        output = new TextView(this);
        output.setTextSize(12);
        output.setTextColor(Color.parseColor("#E6E6E6"));
        output.setBackgroundColor(Color.parseColor("#101418"));
        output.setPadding(32, 32, 32, 32);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#101418"));
        scroll.addView(output);
        setContentView(scroll);

        String version = getIntent().getStringExtra("version");
        if (version == null || version.isEmpty()) {
            version = "1.21.11";
        }
        String username = getIntent().getStringExtra("username");
        if (username == null || username.isEmpty()) {
            username = "UMCPlayer";
        }
        final String selectedVersion = version;
        final String selectedUser = username;

        append("Minecraft bring-up\nversion: " + selectedVersion + ", user: " + selectedUser + "\n");

        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                File jre = UmcJvm.extractJreForHost(this, getFilesDir());
                append(String.format("JRE ready in %d ms: %s\n",
                        System.currentTimeMillis() - t0, jre));

                String helper = new File(getApplicationInfo().nativeLibraryDir,
                        "libjspawnhelper.so").getAbsolutePath();
                String libDir = getApplicationInfo().nativeLibraryDir;
                String dataDir = new File(getFilesDir(), "umc-data").getAbsolutePath();

                append("installing " + selectedVersion
                        + " (first run downloads several hundred MB)...\n");
                String result = UmcCoreBridge.nativeInstallAndLaunch(
                        dataDir, selectedVersion, selectedUser, jre.getAbsolutePath(),
                        helper, libDir, getCacheDir().getAbsolutePath());
                append("launch: " + result + "\n");
                if (result == null || result.startsWith("ERROR")) {
                    return;
                }

                String instanceId = null;
                for (String part : result.split("\\s+")) {
                    if (part.startsWith("instance=")) {
                        instanceId = part.substring("instance=".length());
                    }
                }

                File console = instanceId == null ? null
                        : new File(dataDir, "instances/" + instanceId + "/logs/umc-console.log");
                append("console: " + console + "\n");

                long position = 0;
                int lastStatus = Integer.MIN_VALUE;
                long deadline = System.currentTimeMillis() + 10 * 60_000L;
                while (System.currentTimeMillis() < deadline) {
                    int status = UmcJvm.mainStatus();
                    if (status != lastStatus) {
                        append("main status: " + status + "\n");
                        lastStatus = status;
                    }
                    if (console != null) {
                        position = tail(console, position);
                    }
                    if (status == 1 || status == 2) {
                        break;
                    }
                    Thread.sleep(500);
                }
            } catch (Throwable t) {
                Log.e(TAG, "McActivity failed", t);
                append("\nFAILED: " + t + "\n");
            }
        }, "umc-minecraft").start();
    }

    /**
     * Appends new console output to the screen and returns the new file offset.
     */
    private long tail(File file, long position) {
        if (!file.isFile()) {
            return position;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() <= position) {
                return position;
            }
            raf.seek(position);
            byte[] buffer = new byte[8192];
            int read = raf.read(buffer);
            if (read <= 0) {
                return position;
            }
            append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            return raf.getFilePointer();
        } catch (Throwable t) {
            return position;
        }
    }

    private void append(String text) {
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) {
            Log.i(TAG, trimmed);
        }
        main.post(() -> output.append(text));
    }
}
