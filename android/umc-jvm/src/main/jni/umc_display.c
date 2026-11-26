/*
 * UMC Mobile OpenJDK — present the OSMesa framebuffer on an Android Surface.
 *
 * Minecraft renders into the RGBA buffer owned by GLFW's OSMesa context. This
 * bridge finds that buffer through libglfw's umcGlfwFramebuffer() export and
 * copies it, row-flipped (OpenGL is bottom-up), into an ANativeWindow.
 */
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG "UMCJVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef const unsigned char *(*umc_framebuffer_fn)(int *width, int *height);

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *g_window = NULL;
static umc_framebuffer_fn g_framebuffer = NULL;
static int g_buffer_width = 0;
static int g_buffer_height = 0;

static void *glfw_library(void) {
    /* libglfw.so is loaded by LWJGL; Android returns the same handle here. */
    static void *handle = NULL;
    if (handle == NULL) {
        handle = dlopen("libglfw.so", RTLD_NOW | RTLD_LOCAL);
        if (handle == NULL) {
            LOGE("display: cannot open libglfw.so: %s", dlerror());
        }
    }
    return handle;
}

static void *glfw_symbol(const char *name) {
    void *handle = glfw_library();
    return handle != NULL ? dlsym(handle, name) : NULL;
}

static umc_framebuffer_fn find_framebuffer(void) {
    if (g_framebuffer == NULL) {
        g_framebuffer = (umc_framebuffer_fn) glfw_symbol("umcGlfwFramebuffer");
        if (g_framebuffer == NULL) {
            LOGE("display: umcGlfwFramebuffer not found");
        }
    }
    return g_framebuffer;
}

JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_attach(JNIEnv *env, jclass clazz, jobject surface) {
    (void) clazz;
    if (surface == NULL) {
        return -1;
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        LOGE("display: ANativeWindow_fromSurface failed");
        return -1;
    }
    pthread_mutex_lock(&g_lock);
    if (g_window != NULL) {
        ANativeWindow_release(g_window);
    }
    g_window = window;
    g_buffer_width = 0;
    g_buffer_height = 0;
    pthread_mutex_unlock(&g_lock);
    LOGI("display: surface attached");
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_detach(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g_window != NULL) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
    pthread_mutex_unlock(&g_lock);
    LOGI("display: surface detached");
}

/* Presents the latest frame. Returns 1 when a frame was drawn. */
JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_present(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    umc_framebuffer_fn framebuffer = find_framebuffer();
    if (framebuffer == NULL) {
        return 0;
    }

    int width = 0;
    int height = 0;
    const unsigned char *pixels = framebuffer(&width, &height);
    if (pixels == NULL || width <= 0 || height <= 0) {
        return 0;
    }

    pthread_mutex_lock(&g_lock);
    ANativeWindow *window = g_window;
    if (window == NULL) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }

    if (width != g_buffer_width || height != g_buffer_height) {
        if (ANativeWindow_setBuffersGeometry(window, width, height,
                                             WINDOW_FORMAT_RGBX_8888) != 0) {
            LOGE("display: setBuffersGeometry(%dx%d) failed", width, height);
            pthread_mutex_unlock(&g_lock);
            return 0;
        }
        g_buffer_width = width;
        g_buffer_height = height;
        LOGI("display: framebuffer %dx%d", width, height);
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, NULL) != 0) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }

    int copy_width = width < buffer.width ? width : buffer.width;
    int copy_height = height < buffer.height ? height : buffer.height;
    size_t source_stride = (size_t) width * 4;
    uint8_t *dst_base = (uint8_t *) buffer.bits;

    for (int y = 0; y < copy_height; y++) {
        const uint8_t *src = pixels + (size_t) (height - 1 - y) * source_stride;
        uint8_t *dst = dst_base + (size_t) y * buffer.stride * 4;
        memcpy(dst, src, (size_t) copy_width * 4);
    }

    if (ANativeWindow_unlockAndPost(window) != 0) {
        LOGE("display: unlockAndPost failed");
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    pthread_mutex_unlock(&g_lock);
    return 1;
}

/* Diagnostic: writes the current framebuffer as a top-down PPM (P6). */
JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_dump(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) {
        return -1;
    }

    int result = -1;
    umc_framebuffer_fn framebuffer = find_framebuffer();
    int width = 0;
    int height = 0;
    const unsigned char *pixels = framebuffer != NULL ? framebuffer(&width, &height) : NULL;

    if (pixels != NULL && width > 0 && height > 0) {
        FILE *file = fopen(cpath, "wb");
        if (file != NULL) {
            fprintf(file, "P6\n%d %d\n255\n", width, height);
            unsigned char *row = (unsigned char *) malloc((size_t) width * 3);
            if (row != NULL) {
                for (int y = 0; y < height; y++) {
                    const unsigned char *src = pixels + (size_t) (height - 1 - y) * width * 4;
                    for (int x = 0; x < width; x++) {
                        row[x * 3 + 0] = src[x * 4 + 0];
                        row[x * 3 + 1] = src[x * 4 + 1];
                        row[x * 3 + 2] = src[x * 4 + 2];
                    }
                    if (fwrite(row, 1, (size_t) width * 3, file) != (size_t) width * 3) {
                        break;
                    }
                }
                free(row);
            }
            fclose(file);
            const unsigned char *center =
                pixels + ((size_t) (height / 2) * width + width / 2) * 4;
            size_t nonzero = 0;
            for (size_t i = 0; i < (size_t) width * height; i++) {
                if ((pixels[i * 4] | pixels[i * 4 + 1] | pixels[i * 4 + 2]) != 0) {
                    nonzero++;
                }
            }
            LOGI("display: dumped %dx%d, center=%02x%02x%02x%02x, nonzero=%zu/%d",
                 width, height, center[0], center[1], center[2], center[3],
                 nonzero, width * height);
            result = 0;
        }
    }

    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_width(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_buffer_width;
}

JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_height(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_buffer_height;
}

typedef void (*inject_cursor_fn)(double x, double y);
typedef void (*inject_button_fn)(int button, int action, int mods);
typedef void (*inject_key_fn)(int key, int scancode, int action, int mods);
typedef void (*inject_scroll_fn)(double xoffset, double yoffset);

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_injectCursor(JNIEnv *env, jclass clazz,
                                                    jdouble x, jdouble y) {
    (void) env;
    (void) clazz;
    inject_cursor_fn inject = (inject_cursor_fn) glfw_symbol("umcGlfwInjectCursorPos");
    if (inject != NULL) {
        inject(x, y);
    }
}

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_injectButton(JNIEnv *env, jclass clazz,
                                                    jint button, jint action) {
    (void) env;
    (void) clazz;
    inject_button_fn inject = (inject_button_fn) glfw_symbol("umcGlfwInjectMouseButton");
    if (inject != NULL) {
        inject(button, action, 0);
    }
}

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_injectKey(JNIEnv *env, jclass clazz,
                                                 jint key, jint scancode,
                                                 jint action, jint mods) {
    (void) env;
    (void) clazz;
    inject_key_fn inject = (inject_key_fn) glfw_symbol("umcGlfwInjectKey");
    if (inject != NULL) {
        inject(key, scancode, action, mods);
    }
}

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_injectScroll(JNIEnv *env, jclass clazz,
                                                    jdouble x, jdouble y) {
    (void) env;
    (void) clazz;
    inject_scroll_fn inject = (inject_scroll_fn) glfw_symbol("umcGlfwInjectScroll");
    if (inject != NULL) {
        inject(x, y);
    }
}

typedef unsigned long (*swap_count_fn)(void);

JNIEXPORT jlong JNICALL
Java_dev_umc_mobile_jvmdemo_UmcDisplay_swapCount(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    swap_count_fn count = (swap_count_fn) glfw_symbol("umcGlfwSwapCount");
    return count != NULL ? (jlong) count() : 0;
}
