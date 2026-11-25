public class HelloWorld {
    public static void main(String[] args) throws Exception {
        System.out.println("Hello from OpenJDK on Android!");
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("java.vm.name  = " + System.getProperty("java.vm.name"));
        System.out.println("os.name      = " + System.getProperty("os.name"));
        System.out.println("os.arch      = " + System.getProperty("os.arch"));
        System.out.println("file.encoding= " + System.getProperty("file.encoding"));
        System.out.println("java.io.tmpdir = " + System.getProperty("java.io.tmpdir"));

        // Simple arithmetic to ensure the interpreter/compiler executes bytecode.
        long sum = 0;
        for (int i = 1; i <= 1_000_000; i++) {
            sum += i;
        }
        System.out.println("sum(1..1e6)  = " + sum);

        // Threads
        Thread t = new Thread(() -> System.out.println("thread ok: " + Thread.currentThread().getName()));
        t.start();
        t.join();

        // File I/O
        java.nio.file.Path p = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "umc-smoke.txt");
        java.nio.file.Files.writeString(p, "umc mobile openjdk\n");
        System.out.println("file io      = " + java.nio.file.Files.readString(p).trim());
        java.nio.file.Files.delete(p);

        System.out.println("SMOKE TEST PASSED");
    }
}
