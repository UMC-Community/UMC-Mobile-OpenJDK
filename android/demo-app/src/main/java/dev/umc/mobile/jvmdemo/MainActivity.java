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
                append(String.format("extracted JRE in %d ms\n  %s\n", System.currentTimeMillis() - t0, jre));

                String[] opts = {
                        "-Djava.io.tmpdir=" + getCacheDir().getAbsolutePath(),
                        "-Xrs",
                        "-XX:-UsePerfData"
                };

                UmcJvm.redirectOutput(new File(getFilesDir(), "jvm.log").getAbsolutePath());

                t0 = System.currentTimeMillis();
                int rc = UmcJvm.create(this, jre.getAbsolutePath(), jar.getAbsolutePath(), 256, opts);
                append(String.format("JNI_CreateJavaVM rc=%d in %d ms\n", rc, System.currentTimeMillis() - t0));
                if (rc != 0) {
                    append("\nFAILED: JVM creation rc=" + rc + "\n");
                    success = false;
                } else {
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

                    UmcJvm.destroy();
                    append("\nJVM destroyed\n");
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
            player.setOnCompletionListener(mp -> append("audio: playback finished"));
            player.prepare();
            player.start();
            append("audio: playing test.mp3 ✅");
            Log.i(TAG, "audio playing test.mp3");
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
