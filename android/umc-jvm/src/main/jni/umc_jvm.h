/*
 * UMC Mobile OpenJDK — C API for native embedders (e.g. UMC Core in Rust).
 *
 * The JVM is created in-process. Java's main class is started on a detached
 * native thread, so the caller returns immediately and can poll
 * umc_jvm_main_status().
 */
#ifndef UMC_JVM_H
#define UMC_JVM_H

#ifdef __cplusplus
extern "C" {
#endif

/* Return codes */
#define UMC_JVM_OK 0
#define UMC_JVM_ERROR (-1)

/* Main status values */
#define UMC_JVM_MAIN_NOT_STARTED (-1)
#define UMC_JVM_MAIN_RUNNING 0
#define UMC_JVM_MAIN_FINISHED 1
#define UMC_JVM_MAIN_FAILED 2

/*
 * Creates the JVM.
 *
 * java_home          JRE root (the directory containing lib/modules)
 * class_path         ':'-separated class path
 * max_heap_mb        -Xmx value in MiB
 * options            extra JVM options (may be NULL)
 * option_count       number of extra options
 * spawn_helper_path  jspawnhelper shipped as an APK native library (may be NULL)
 *
 * Returns UMC_JVM_OK on success.
 */
int umc_jvm_create(const char *java_home, const char *class_path, int max_heap_mb,
                   const char *const *options, int option_count,
                   const char *spawn_helper_path);

/*
 * Starts main(String[]) of main_class on a detached thread.
 * Returns UMC_JVM_OK if the thread was started.
 */
int umc_jvm_start_main(const char *main_class, const char *const *args, int arg_count);

/* UMC_JVM_MAIN_NOT_STARTED / RUNNING / FINISHED / FAILED */
int umc_jvm_main_status(void);

/*
 * Calls a no-argument static method returning String.
 * Returns a heap-allocated string (release with umc_jvm_free) or NULL.
 */
char *umc_jvm_call_static(const char *class_name, const char *method,
                          const char *signature);

/* Releases a string returned by this API. */
void umc_jvm_free(char *value);

/* Redirects the JVM's stdout/stderr to a file (useful for diagnostics). */
void umc_jvm_redirect_output(const char *path);

/* Returns 1 when a JVM has been created. */
int umc_jvm_is_created(void);

/* Destroys the JVM. Blocks until the VM shuts down. */
void umc_jvm_destroy(void);

#ifdef __cplusplus
}
#endif

#endif /* UMC_JVM_H */
