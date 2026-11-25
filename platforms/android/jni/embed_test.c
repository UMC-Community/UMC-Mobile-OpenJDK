/*
 * UMC Mobile OpenJDK — JNI embedding smoke test
 *
 * Starts a JVM inside the host process through JNI_CreateJavaVM and calls
 * HelloFromJni.run().
 *
 * Usage:
 *   umc-jni-test <java.home> <classpath>
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <java.home> <classpath>\n", argv[0]);
        return 2;
    }

    char opt_home[512];
    char opt_cp[1024];
    char opt_tmp[512];
    snprintf(opt_home, sizeof(opt_home), "-Djava.home=%s", argv[1]);
    snprintf(opt_cp, sizeof(opt_cp), "-Djava.class.path=%s", argv[2]);
    snprintf(opt_tmp, sizeof(opt_tmp), "-Djava.io.tmpdir=%s", "/data/local/tmp");

    JavaVMOption options[4];
    options[0].optionString = opt_home;
    options[1].optionString = opt_cp;
    options[2].optionString = opt_tmp;
    options[3].optionString = "-Xmx256m";

    JavaVMInitArgs vm_args;
    memset(&vm_args, 0, sizeof(vm_args));
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = 4;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint rc = JNI_CreateJavaVM(&vm, (void **) &env, &vm_args);
    if (rc != JNI_OK) {
        fprintf(stderr, "JNI_CreateJavaVM failed: %d\n", rc);
        return 1;
    }
    printf("[host] JVM created in-process\n");

    jclass cls = (*env)->FindClass(env, "HelloFromJni");
    if (cls == NULL) {
        fprintf(stderr, "[host] class HelloFromJni not found\n");
        (*vm)->DestroyJavaVM(vm);
        return 1;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "run", "()Ljava/lang/String;");
    if (mid == NULL) {
        fprintf(stderr, "[host] method run() not found\n");
        (*vm)->DestroyJavaVM(vm);
        return 1;
    }

    jstring result = (jstring) (*env)->CallStaticObjectMethod(env, cls, mid);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*vm)->DestroyJavaVM(vm);
        return 1;
    }

    const char *str = (*env)->GetStringUTFChars(env, result, NULL);
    printf("[host] java says: %s\n", str);
    (*env)->ReleaseStringUTFChars(env, result, str);

    printf("[host] destroying JVM\n");
    (*vm)->DestroyJavaVM(vm);
    printf("[host] SMOKE TEST PASSED\n");
    return 0;
}
