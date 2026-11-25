/*
 * UMC Mobile OpenJDK — JNI bridge
 *
 * Runs a HotSpot JVM inside the host Android process (ART keeps running).
 * libjvm.so is packaged as a normal APK native library; the rest of the JRE
 * (modules, conf, native libs) is extracted from assets by UmcJvm.java.
 */
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define TAG "UMCJVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_OPTS 64

/* Older NDK jni.h versions do not define the 1.8 constant. */
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

static JavaVM *g_vm = NULL;

static char *dup_utf(JNIEnv *env, jstring s) {
    if (s == NULL) {
        return NULL;
    }
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (c == NULL) {
        return NULL;
    }
    char *r = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

/*
 * Android app processes discard stdout/stderr by default, while JVM startup failures are
 * reported on stderr only. Redirect both to a file in app-private storage before creating
 * the JVM.
 */
JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_redirectOutput(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = dup_utf(env, path);
    if (p == NULL) {
        return;
    }
    if (freopen(p, "a", stdout) == NULL) {
        LOGE("cannot redirect stdout to %s", p);
    }
    if (freopen(p, "a", stderr) == NULL) {
        LOGE("cannot redirect stderr to %s", p);
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    /* Child processes forked by the JVM may not inherit fd 2 reliably;
       let them reopen this path directly when reporting exec failures. */
    setenv("UMC_EXEC_LOG", p, 1);
    LOGI("stdout/stderr redirected to %s", p);
    free(p);
}

static JNIEnv *attach_env(void) {
    JNIEnv *env = NULL;
    if (g_vm == NULL) {
        return NULL;
    }
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
        return NULL;
    }
    return env;
}

JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_create(JNIEnv *env, jclass clazz,
        jstring javaHome, jstring classPath, jint maxHeapMb, jobjectArray extraOptions,
        jstring spawnHelperPath) {
    (void) clazz;
    if (g_vm != NULL) {
        LOGE("JVM already created");
        return -1;
    }

    char *home = dup_utf(env, javaHome);
    char *cp = dup_utf(env, classPath);
    if (home == NULL || cp == NULL) {
        free(home);
        free(cp);
        LOGE("invalid java.home / classpath");
        return -1;
    }

    int extraCount = extraOptions != NULL ? (*env)->GetArrayLength(env, extraOptions) : 0;
    int n = 3 + extraCount;
    if (n > MAX_OPTS) {
        n = MAX_OPTS;
    }

    JavaVMOption *opts = (JavaVMOption *) calloc((size_t) n, sizeof(JavaVMOption));
    char **bufs = (char **) calloc((size_t) n, sizeof(char *));
    if (opts == NULL || bufs == NULL) {
        free(home);
        free(cp);
        free(opts);
        free(bufs);
        return -1;
    }

    int i = 0;
    bufs[i] = (char *) malloc(4096);
    snprintf(bufs[i], 4096, "-Djava.home=%s", home);
    opts[i].optionString = bufs[i];
    i++;

    bufs[i] = (char *) malloc(4096);
    snprintf(bufs[i], 4096, "-Djava.class.path=%s", cp);
    opts[i].optionString = bufs[i];
    i++;

    bufs[i] = (char *) malloc(64);
    snprintf(bufs[i], 64, "-Xmx%dM", (int) maxHeapMb);
    opts[i].optionString = bufs[i];
    i++;

    for (int k = 0; k < extraCount && i < n; k++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, extraOptions, k);
        bufs[i] = dup_utf(env, js);
        (*env)->DeleteLocalRef(env, js);
        if (bufs[i] == NULL) {
            bufs[i] = strdup("");
        }
        opts[i].optionString = bufs[i];
        i++;
    }

    JavaVMInitArgs args;
    memset(&args, 0, sizeof(args));
    args.version = JNI_VERSION_1_8;
    args.nOptions = i;
    args.options = opts;
    args.ignoreUnrecognized = JNI_FALSE;

    /* libjvm.so is loaded from the APK, so HotSpot cannot derive java.home
     * from its own path; hand it over explicitly. */
    setenv("UMC_JAVA_HOME", home, 1);

    /* AWT on Android has no system fontconfig; a cross-built libfontconfig is
     * shipped inside the JRE together with this configuration file. */
    {
        char fc_conf[4096];
        char fc_cache[4096];
        snprintf(fc_conf, sizeof(fc_conf), "%s/conf/fonts.conf", home);
        snprintf(fc_cache, sizeof(fc_cache), "%s/cache", home);
        setenv("FONTCONFIG_FILE", fc_conf, 1);
        setenv("XDG_CACHE_HOME", fc_cache, 1);
        (void) mkdir(fc_cache, 0700);
        LOGI("fontconfig: %s", fc_conf);
    }

    /* java.lang.Process execs jspawnhelper, which cannot live in app-private
     * storage (execve is forbidden there). The embedder ships it as an APK
     * native library and passes its path here. */
    if (spawnHelperPath != NULL) {
        char *helper = dup_utf(env, spawnHelperPath);
        if (helper != NULL) {
            setenv("UMC_JSPAWNHELPER", helper, 1);
            LOGI("jspawnhelper: %s", helper);
            free(helper);
        }
    }

    JNIEnv *venv = NULL;
    LOGI("creating JVM: home=%s cp=%s heap=%dMB options=%d", home, cp, (int) maxHeapMb, i);
    jint rc = JNI_CreateJavaVM(&g_vm, &venv, &args);
    LOGI("JNI_CreateJavaVM rc=%d", (int) rc);
    if (rc != JNI_OK) {
        g_vm = NULL;
        LOGE("JVM creation failed (rc=%d)", (int) rc);
    }

    for (int k = 0; k < i; k++) {
        free(bufs[k]);
    }
    free(bufs);
    free(opts);
    free(home);
    free(cp);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_callStatic(JNIEnv *env, jclass clazz,
        jstring className, jstring methodName, jstring signature) {
    (void) clazz;
    if (g_vm == NULL) {
        LOGE("JVM not created");
        return NULL;
    }

    char *cn = dup_utf(env, className);
    char *mn = dup_utf(env, methodName);
    char *sg = dup_utf(env, signature);
    jstring out = NULL;

    JNIEnv *venv = attach_env();
    if (venv != NULL && cn != NULL && mn != NULL && sg != NULL) {
        jclass cls = (*venv)->FindClass(venv, cn);
        if (cls == NULL) {
            LOGE("class not found: %s", cn);
            (*venv)->ExceptionClear(venv);
        } else {
            jmethodID mid = (*venv)->GetStaticMethodID(venv, cls, mn, sg);
            if (mid == NULL) {
                LOGE("method not found: %s.%s%s", cn, mn, sg);
                (*venv)->ExceptionClear(venv);
            } else {
                jobject res = (*venv)->CallStaticObjectMethod(venv, cls, mid);
                if ((*venv)->ExceptionCheck(venv)) {
                    LOGE("java exception while calling %s.%s", cn, mn);
                    (*venv)->ExceptionDescribe(venv);
                    (*venv)->ExceptionClear(venv);
                } else if (res != NULL) {
                    const char *utf = (*venv)->GetStringUTFChars(venv, (jstring) res, NULL);
                    if (utf != NULL) {
                        out = (*env)->NewStringUTF(env, utf);
                        (*venv)->ReleaseStringUTFChars(venv, (jstring) res, utf);
                    }
                }
            }
        }
    }

    free(cn);
    free(mn);
    free(sg);
    return out;
}

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_destroy(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_vm == NULL) {
        return;
    }
    JNIEnv *venv = attach_env();
    if (venv != NULL) {
        LOGI("destroying JVM");
        (*g_vm)->DestroyJavaVM(g_vm);
    }
    g_vm = NULL;
    LOGI("JVM destroyed");
}
