/**
 * Exercises the Android graphics stack inside the JVM:
 * GLFW (null platform + OSMesa) creates the context, LWJGL loads libOSMesa as
 * its OpenGL provider, and a clear + readback verifies the GL pipeline.
 */
public class GfxProbe {
    public static String run() {
        long window = 0;
        try {
            org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();
            if (!org.lwjgl.glfw.GLFW.glfwInit()) {
                return "FAILED: glfwInit";
            }

            org.lwjgl.glfw.GLFW.glfwWindowHint(
                    org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(
                    org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            org.lwjgl.glfw.GLFW.glfwWindowHint(
                    org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            org.lwjgl.glfw.GLFW.glfwWindowHint(
                    org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE,
                    org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);

            window = org.lwjgl.glfw.GLFW.glfwCreateWindow(320, 240, "umc-gfx-probe", 0, 0);
            if (window == 0) {
                return "FAILED: glfwCreateWindow";
            }
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
            org.lwjgl.opengl.GL.createCapabilities();

            String version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);

            org.lwjgl.opengl.GL11.glViewport(0, 0, 320, 240);
            org.lwjgl.opengl.GL11.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
            org.lwjgl.opengl.GL11.glFinish();

            java.nio.ByteBuffer pixel = org.lwjgl.BufferUtils.createByteBuffer(4);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, 1, 1,
                    org.lwjgl.opengl.GL11.GL_RGBA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixel);
            int r = pixel.get(0) & 0xff;
            int g = pixel.get(1) & 0xff;
            int b = pixel.get(2) & 0xff;
            if (r < 250 || g > 5 || b > 5) {
                return "FAILED: pixel=" + r + "," + g + "," + b;
            }
            return "gl " + version + " / " + renderer + ", pixel ok";
        } catch (Throwable t) {
            return "FAILED: " + t;
        } finally {
            if (window != 0) {
                org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);
            }
        }
    }
}
