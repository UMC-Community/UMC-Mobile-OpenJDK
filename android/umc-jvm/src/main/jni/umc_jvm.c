/*
 * UMC Mobile OpenJDK — JNI bridge and C API.
 *
 * Runs a HotSpot JVM inside the host Android process (ART keeps running).
 * libjvm.so is packaged as a normal APK native library; the rest of the JRE
 * (modules, conf, native libs) is extracted from assets by UmcJvm.java.
 *
 * The C API (umc_jvm.h) is the entry point for native embedders such as UMC
 * Core (Rust). The JNI functions below keep the existing Java API working.
 */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "umc_jvm.h"

#define TAG "UMCJVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_OPTS 64

/* Older NDK jni.h versions do not define the 1.8 constant. */
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

static JavaVM *g_vm = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_main_status = UMC_JVM_MAIN_NOT_STARTED;

/* JNI FindClass expects a binary name with '/' separators. */
static char *jni_class_name(const char *name) {
    char *out = strdup(name);
    if (out != NULL) {
        for (char *p = out; *p != '\0'; p++) {
            if (*p == '.') {
                *p = '/';
            }
        }
    }
    return out;
}

static char *dup_utf(JNIEnv *env, jstring s) {    if (s == NULL) {
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

/*
 * Redirects the JVM's stdout/stderr to a file. Android app processes discard
 * stdout/stderr by default, while JVM startup failures are reported on stderr.
 */
void umc_jvm_redirect_output(const char *path) {
    if (path == NULL || path[0] == '\0') {
        return;
    }
    if (freopen(path, "a", stdout) == NULL) {
        LOGE("cannot redirect stdout to %s", path);
    }
    if (freopen(path, "a", stderr) == NULL) {
        LOGE("cannot redirect stderr to %s", path);
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    /* Child processes forked by the JVM may not inherit fd 2 reliably;
       let them reopen this path directly when reporting exec failures. */
    setenv("UMC_EXEC_LOG", path, 1);
    LOGI("stdout/stderr redirected to %s", path);
}

int umc_jvm_create(const char *java_home, const char *class_path, int max_heap_mb,
                   const char *const *options, int option_count,
                   const char *spawn_helper_path) {
    pthread_mutex_lock(&g_lock);
    if (g_vm != NULL) {
        pthread_mutex_unlock(&g_lock);
        LOGE("JVM already created");
        return UMC_JVM_ERROR;
    }
    if (java_home == NULL || class_path == NULL) {
        pthread_mutex_unlock(&g_lock);
        LOGE("invalid java.home / classpath");
        return UMC_JVM_ERROR;
    }

    int n = 3 + (option_count > 0 ? option_count : 0);
    if (n > MAX_OPTS) {
        n = MAX_OPTS;
    }
    JavaVMOption *opts = (JavaVMOption *) calloc((size_t) n, sizeof(JavaVMOption));
    char **bufs = (char **) calloc((size_t) n, sizeof(char *));
    jint rc = JNI_ERR;
    if (opts == NULL || bufs == NULL) {
        free(opts);
        free(bufs);
        pthread_mutex_unlock(&g_lock);
        return UMC_JVM_ERROR;
    }

    int i = 0;
    bufs[i] = (char *) malloc(strlen(java_home) + 16);
    if (bufs[i] == NULL) {
        goto fail;
    }
    sprintf(bufs[i], "-Djava.home=%s", java_home);
    opts[i].optionString = bufs[i];
    i++;

    /* Minecraft's class path is far larger than any fixed buffer; allocate it. */
    bufs[i] = (char *) malloc(strlen(class_path) + 32);
    if (bufs[i] == NULL) {
        goto fail;
    }
    sprintf(bufs[i], "-Djava.class.path=%s", class_path);
    opts[i].optionString = bufs[i];
    i++;

    bufs[i] = (char *) malloc(64);
    if (bufs[i] == NULL) {
        goto fail;
    }
    snprintf(bufs[i], 64, "-Xmx%dM", max_heap_mb);
    opts[i].optionString = bufs[i];
    i++;

    for (int k = 0; k < option_count && i < n; k++) {
        bufs[i] = strdup(options[k] != NULL ? options[k] : "");
        if (bufs[i] == NULL) {
            goto fail;
        }
        opts[i].optionString = bufs[i];
        i++;
    }

    /* libjvm.so is loaded from the APK, so HotSpot cannot derive java.home
     * from its own path; hand it over explicitly. */
    setenv("UMC_JAVA_HOME", java_home, 1);

    /* AWT on Android has no system fontconfig; a cross-built libfontconfig is
     * shipped inside the JRE together with this configuration file. */
    {
        char fc_conf[4096];
        char fc_cache[4096];
        snprintf(fc_conf, sizeof(fc_conf), "%s/conf/fonts.conf", java_home);
        snprintf(fc_cache, sizeof(fc_cache), "%s/cache", java_home);
        setenv("FONTCONFIG_FILE", fc_conf, 1);
        setenv("XDG_CACHE_HOME", fc_cache, 1);
        (void) mkdir(fc_cache, 0700);
        LOGI("fontconfig: %s", fc_conf);
    }

    /* java.lang.Process execs jspawnhelper, which cannot live in app-private
     * storage (execve is forbidden there). The embedder ships it as an APK
     * native library and passes its path here. */
    if (spawn_helper_path != NULL && spawn_helper_path[0] != '\0') {
        setenv("UMC_JSPAWNHELPER", spawn_helper_path, 1);
        LOGI("jspawnhelper: %s", spawn_helper_path);
    }

    JavaVMInitArgs args;
    memset(&args, 0, sizeof(args));
    args.version = JNI_VERSION_1_8;
    args.nOptions = i;
    args.options = opts;
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv *venv = NULL;
    LOGI("creating JVM: home=%s heap=%dMB options=%d classpath=%zu bytes",
         java_home, max_heap_mb, i, strlen(class_path));
    rc = JNI_CreateJavaVM(&g_vm, &venv, &args);
    LOGI("JNI_CreateJavaVM rc=%d", (int) rc);
    if (rc != JNI_OK) {
        g_vm = NULL;
        LOGE("JVM creation failed (rc=%d)", (int) rc);
    }

fail:
    for (int k = 0; k < i; k++) {
        free(bufs[k]);
    }
    free(bufs);
    free(opts);
    pthread_mutex_unlock(&g_lock);
    return rc == JNI_OK ? UMC_JVM_OK : UMC_JVM_ERROR;
}

int umc_jvm_is_created(void) {
    return g_vm != NULL ? 1 : 0;
}

char *umc_jvm_call_static(const char *class_name, const char *method,
                          const char *signature) {
    if (g_vm == NULL || class_name == NULL || method == NULL || signature == NULL) {
        return NULL;
    }

    JNIEnv *venv = attach_env();
    if (venv == NULL) {
        return NULL;
    }

    char *result = NULL;
    char *binary = jni_class_name(class_name);
    jclass cls = binary != NULL ? (*venv)->FindClass(venv, binary) : NULL;
    free(binary);
    if (cls == NULL) {
        LOGE("class not found: %s", class_name);
        (*venv)->ExceptionClear(venv);
    } else {
        jmethodID mid = (*venv)->GetStaticMethodID(venv, cls, method, signature);
        if (mid == NULL) {
            LOGE("method not found: %s.%s%s", class_name, method, signature);
            (*venv)->ExceptionClear(venv);
        } else {
            jobject res = (*venv)->CallStaticObjectMethod(venv, cls, mid);
            if ((*venv)->ExceptionCheck(venv)) {
                LOGE("java exception while calling %s.%s", class_name, method);
                (*venv)->ExceptionDescribe(venv);
                (*venv)->ExceptionClear(venv);
            } else if (res != NULL) {
                const char *utf = (*venv)->GetStringUTFChars(venv, (jstring) res, NULL);
                if (utf != NULL) {
                    result = strdup(utf);
                    (*venv)->ReleaseStringUTFChars(venv, (jstring) res, utf);
                }
            }
        }
    }
    return result;
}

void umc_jvm_free(char *value) {
    free(value);
}

typedef struct {
    char *main_class;
    char **args;
    int arg_count;
} MainCall;

static void main_call_free(MainCall *call) {
    if (call == NULL) {
        return;
    }
    free(call->main_class);
    for (int i = 0; i < call->arg_count; i++) {
        free(call->args[i]);
    }
    free(call->args);
    free(call);
}

static void *main_thread(void *opaque) {
    MainCall *call = (MainCall *) opaque;
    JNIEnv *env = attach_env();

    if (env == NULL) {
        LOGE("cannot attach main thread to the JVM");
        atomic_store(&g_main_status, UMC_JVM_MAIN_FAILED);
        main_call_free(call);
        return NULL;
    }

    char *binary_name = jni_class_name(call->main_class);
    if (binary_name == NULL) {
        atomic_store(&g_main_status, UMC_JVM_MAIN_FAILED);
        goto done;
    }
    jclass cls = (*env)->FindClass(env, binary_name);
    if (cls == NULL) {
        LOGE("main class not found: %s", call->main_class);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        free(binary_name);
        atomic_store(&g_main_status, UMC_JVM_MAIN_FAILED);
        goto done;
    }
    free(binary_name);

    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) {
        LOGE("main method not found in %s", call->main_class);
        (*env)->ExceptionClear(env);
        atomic_store(&g_main_status, UMC_JVM_MAIN_FAILED);
        goto done;
    }

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray argv = (*env)->NewObjectArray(env, call->arg_count, string_class, NULL);
    for (int i = 0; i < call->arg_count; i++) {
        jstring arg = (*env)->NewStringUTF(env, call->args[i]);
        (*env)->SetObjectArrayElement(env, argv, i, arg);
        (*env)->DeleteLocalRef(env, arg);
    }

    LOGI("starting main: %s (%d args)", call->main_class, call->arg_count);
    (*env)->CallStaticVoidMethod(env, cls, mid, argv);
    if ((*env)->ExceptionCheck(env)) {
        LOGE("exception from main");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        atomic_store(&g_main_status, UMC_JVM_MAIN_FAILED);
    } else {
        LOGI("main returned");
        atomic_store(&g_main_status, UMC_JVM_MAIN_FINISHED);
    }

done:
    (*g_vm)->DetachCurrentThread(g_vm);
    main_call_free(call);
    return NULL;
}

int umc_jvm_start_main(const char *main_class, const char *const *args, int arg_count) {
    if (g_vm == NULL || main_class == NULL) {
        return UMC_JVM_ERROR;
    }
    if (atomic_load(&g_main_status) == UMC_JVM_MAIN_RUNNING) {
        LOGE("main already running");
        return UMC_JVM_ERROR;
    }

    MainCall *call = (MainCall *) calloc(1, sizeof(MainCall));
    if (call == NULL) {
        return UMC_JVM_ERROR;
    }
    call->main_class = strdup(main_class);
    call->arg_count = arg_count > 0 ? arg_count : 0;
    call->args = (char **) calloc((size_t) call->arg_count, sizeof(char *));
    for (int i = 0; i < call->arg_count; i++) {
        call->args[i] = strdup(args[i] != NULL ? args[i] : "");
    }

    atomic_store(&g_main_status, UMC_JVM_MAIN_RUNNING);
    pthread_t thread;
    int rc = pthread_create(&thread, NULL, main_thread, call);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        atomic_store(&g_main_status, UMC_JVM_MAIN_FAILED);
        main_call_free(call);
        return UMC_JVM_ERROR;
    }
    pthread_detach(thread);
    return UMC_JVM_OK;
}

int umc_jvm_main_status(void) {
    return atomic_load(&g_main_status);
}

void umc_jvm_destroy(void) {
    pthread_mutex_lock(&g_lock);
    if (g_vm == NULL) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    JNIEnv *venv = attach_env();
    if (venv != NULL) {
        LOGI("destroying JVM");
        (*g_vm)->DestroyJavaVM(g_vm);
    }
    g_vm = NULL;
    atomic_store(&g_main_status, UMC_JVM_MAIN_NOT_STARTED);
    pthread_mutex_unlock(&g_lock);
    LOGI("JVM destroyed");
}

/* ------------------------------------------------------------------------- */
/* JNI surface (existing Java API)                                            */
/* ------------------------------------------------------------------------- */

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_redirectOutput(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = dup_utf(env, path);
    if (p == NULL) {
        return;
    }
    umc_jvm_redirect_output(p);
    free(p);
}

JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_create(JNIEnv *env, jclass clazz,
        jstring javaHome, jstring classPath, jint maxHeapMb, jobjectArray extraOptions,
        jstring spawnHelperPath) {
    (void) clazz;
    char *home = dup_utf(env, javaHome);
    char *cp = dup_utf(env, classPath);
    char *helper = dup_utf(env, spawnHelperPath);

    int extra_count = extraOptions != NULL ? (*env)->GetArrayLength(env, extraOptions) : 0;
    char **options = (char **) calloc((size_t) extra_count, sizeof(char *));
    for (int k = 0; k < extra_count; k++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, extraOptions, k);
        options[k] = dup_utf(env, js);
        (*env)->DeleteLocalRef(env, js);
    }

    int rc = umc_jvm_create(home, cp, (int) maxHeapMb,
                            (const char *const *) options, extra_count, helper);

    for (int k = 0; k < extra_count; k++) {
        free(options[k]);
    }
    free(options);
    free(home);
    free(cp);
    free(helper);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_callStatic(JNIEnv *env, jclass clazz,
        jstring className, jstring methodName, jstring signature) {
    (void) clazz;
    char *cn = dup_utf(env, className);
    char *mn = dup_utf(env, methodName);
    char *sg = dup_utf(env, signature);

    char *value = umc_jvm_call_static(cn, mn, sg);
    jstring out = value != NULL ? (*env)->NewStringUTF(env, value) : NULL;

    umc_jvm_free(value);
    free(cn);
    free(mn);
    free(sg);
    return out;
}

JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_startMain(JNIEnv *env, jclass clazz,
        jstring mainClass, jobjectArray args) {
    (void) clazz;
    char *mc = dup_utf(env, mainClass);
    if (mc == NULL) {
        return UMC_JVM_ERROR;
    }

    int argc = args != NULL ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = (char **) calloc((size_t) argc, sizeof(char *));
    for (int k = 0; k < argc; k++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, args, k);
        argv[k] = dup_utf(env, js);
        (*env)->DeleteLocalRef(env, js);
    }

    int rc = umc_jvm_start_main(mc, (const char *const *) argv, argc);

    for (int k = 0; k < argc; k++) {
        free(argv[k]);
    }
    free(argv);
    free(mc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_mainStatus(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return umc_jvm_main_status();
}

JNIEXPORT void JNICALL
Java_dev_umc_mobile_jvm_UmcJvm_destroy(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    umc_jvm_destroy();
}
