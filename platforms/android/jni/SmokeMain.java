/**
 * Exercised by the Test Demo through {@code UmcJvm.startMain}, which is the Java
 * counterpart of the libumc_jvm C API used by UMC Core on Android.
 */
public class SmokeMain {
    public static void main(String[] args) {
        System.out.println("SmokeMain: args=" + java.util.Arrays.toString(args));
        System.out.println("SmokeMain: thread=" + Thread.currentThread().getName());
        System.out.println("SmokeMain: java.version=" + System.getProperty("java.version"));
    }
}
