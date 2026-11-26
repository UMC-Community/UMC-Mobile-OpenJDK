/**
 * Loads LWJGL's core native library and exercises its JNI memory functions.
 * Built into smoke.jar; the demo adds the official LWJGL core jar to the
 * class path and liblwjgl.so (Android build) to the native library path.
 */
public class LwjglProbe {
    public static String run() {
        java.nio.ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memAlloc(16);
        long p = org.lwjgl.system.MemoryUtil.memAddress(buffer);
        if (p == 0) {
            return "FAILED: memAlloc returned NULL";
        }
        org.lwjgl.system.MemoryUtil.memPutInt(p, 0x12345678);
        int value = org.lwjgl.system.MemoryUtil.memGetInt(p);
        org.lwjgl.system.MemoryUtil.memFree(buffer);
        if (value != 0x12345678) {
            return "FAILED: memGetInt=" + Integer.toHexString(value);
        }
        return "lwjgl " + org.lwjgl.Version.getVersion() + ", mem ok";
    }
}
