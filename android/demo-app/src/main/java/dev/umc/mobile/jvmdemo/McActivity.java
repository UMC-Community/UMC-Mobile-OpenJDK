package dev.umc.mobile.jvmdemo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import dev.umc.mobile.jvm.UmcJvm;

/**
 * Minecraft bring-up harness: installs a vanilla version through UMC Core,
 * launches it on the embedded JVM, and presents the OSMesa framebuffer on a
 * SurfaceView.
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
    private SurfaceView surfaceView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean displayRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        output = new TextView(this);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        output.setTextColor(Color.parseColor("#E6E6E6"));
        output.setBackgroundColor(0x99000000);
        output.setPadding(24, 16, 24, 16);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setTextIsSelectable(true);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(150), Gravity.TOP);
        root.addView(output, overlay);

        output.setClickable(false);
        output.setFocusable(false);

        // Touch -> game cursor, scaled to the framebuffer size.
        root.setOnTouchListener((view, event) -> {
            int width = UmcDisplay.width();
            int height = UmcDisplay.height();
            if (width <= 0 || height <= 0 || view.getWidth() == 0 || view.getHeight() == 0) {
                return false;
            }
            double x = event.getX() * width / (double) view.getWidth();
            double y = event.getY() * height / (double) view.getHeight();
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    UmcDisplay.injectCursor(x, y);
                    UmcDisplay.injectButton(0, 1);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    UmcDisplay.injectCursor(x, y);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    UmcDisplay.injectCursor(x, y);
                    UmcDisplay.injectButton(0, 0);
                    break;
                default:
                    break;
            }
            return true;
        });

        setContentView(root);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (UmcDisplay.attach(holder.getSurface()) != 0) {
                    append("display: attach failed\n");
                    return;
                }
                displayRunning = true;
                startDisplayThread();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                displayRunning = false;
                UmcDisplay.detach();
            }
        });

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
     * Copies the OSMesa framebuffer to the Surface at roughly 60 Hz.
     */
    private void startDisplayThread() {
        new Thread(() -> {
            long frames = 0;
            long lastSwapCount = UmcDisplay.swapCount();
            long presentedSwaps = -1;
            long windowStart = System.currentTimeMillis();
            while (displayRunning) {
                long swaps = UmcDisplay.swapCount();
                if (swaps != presentedSwaps) {
                    if (UmcDisplay.present() == 1) {
                        frames++;
                    }
                    presentedSwaps = swaps;
                }
                long now = System.currentTimeMillis();
                if (now - windowStart >= 5000) {
                    Log.i(TAG, "display: " + frames + " presents, "
                            + (swaps - lastSwapCount) + " game frames in "
                            + (now - windowStart) + " ms");
                    lastSwapCount = swaps;
                    frames = 0;
                    windowStart = now;
                }
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "umc-display").start();
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void append(String text) {
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) {
            Log.i(TAG, trimmed);
        }
        main.post(() -> output.append(text));
    }
}
