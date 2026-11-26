package dev.umc.mobile.jvmdemo;

import android.view.Surface;

/**
 * Presents the OSMesa framebuffer of the embedded JVM on an Android Surface.
 * Implemented in libumc_jvm.so.
 */
public final class UmcDisplay {
    private UmcDisplay() {
    }

    static {
        System.loadLibrary("umc_jvm");
    }

    public static native int attach(Surface surface);

    public static native void detach();

    /** Returns 1 when a frame was presented, 0 before Minecraft has a frame. */
    public static native int present();

    /** Diagnostic: writes the current framebuffer as a top-down PPM. */
    public static native int dump(String path);

    public static native int width();

    public static native int height();

    public static native void injectCursor(double x, double y);

    public static native void injectButton(int button, int action);

    public static native void injectKey(int key, int scancode, int action, int mods);

    public static native void injectScroll(double xoffset, double yoffset);

    /** Number of glfwSwapBuffers calls, i.e. frames rendered by the game. */
    public static native long swapCount();
}
