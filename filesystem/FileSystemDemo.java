package filesystem;

import filesystem.exception.*;

public class FileSystemDemo {

    public static void main(String[] args) {
        FileSystem fs = new FileSystem();

        // ─── Scenario 1: mkdir + touch + write + read ───
        System.out.println("=== Scenario 1: Basic file creation ===");
        fs.mkdir("/home");
        fs.mkdir("/home/alice");
        fs.write("/home/alice/hello.txt", "Hello, world!");
        System.out.println("ls /home/alice: " + fs.ls("/home/alice"));
        System.out.println("read /home/alice/hello.txt: " + fs.read("/home/alice/hello.txt"));
        System.out.println();

        // ─── Scenario 2: mkdirs (recursive create) ───
        System.out.println("=== Scenario 2: mkdirs creates intermediate dirs ===");
        fs.mkdirs("/var/log/apps/myservice");
        fs.write("/var/log/apps/myservice/access.log", "200 GET /\n");
        fs.append("/var/log/apps/myservice/access.log", "404 GET /missing\n");
        System.out.println("read access.log:");
        System.out.println(fs.read("/var/log/apps/myservice/access.log"));

        // ─── Scenario 3: ls on nested dirs ───
        System.out.println("=== Scenario 3: Nested ls ===");
        System.out.println("ls /: " + fs.ls("/"));
        System.out.println("ls /var: " + fs.ls("/var"));
        System.out.println("ls /var/log: " + fs.ls("/var/log"));
        System.out.println();

        // ─── Scenario 4: mv (rename and move) ───
        System.out.println("=== Scenario 4: Move and rename ===");
        fs.mkdir("/home/bob");
        fs.mv("/home/alice/hello.txt", "/home/bob/greeting.txt");
        System.out.println("ls /home/alice: " + fs.ls("/home/alice"));
        System.out.println("ls /home/bob: " + fs.ls("/home/bob"));
        System.out.println("read /home/bob/greeting.txt: "
            + fs.read("/home/bob/greeting.txt"));
        System.out.println();

        // ─── Scenario 5: rm + rmdir ───
        System.out.println("=== Scenario 5: Delete ===");
        fs.rm("/home/bob/greeting.txt");
        System.out.println("After rm, ls /home/bob: " + fs.ls("/home/bob"));
        fs.rmdir("/home/bob");
        System.out.println("After rmdir, ls /home: " + fs.ls("/home"));
        System.out.println();

        // ─── Scenario 6: Error handling ───
        System.out.println("=== Scenario 6: Error cases ===");
        testError(() -> fs.read("/nonexistent.txt"), "read non-existent file");
        testError(() -> fs.read("/home"), "read a directory");
        testError(() -> fs.mkdir("/home/alice"), "mkdir on existing dir");
        testError(() -> fs.rm("/home/alice"), "rm on a directory");
        testError(() -> fs.mkdir("/a/b/c"), "mkdir with missing parent");
        testError(() -> fs.rmdir("/var"), "rmdir on non-empty directory");
        System.out.println();

        // ─── Scenario 7: rmRecursive ───
        System.out.println("=== Scenario 7: rmRecursive ===");
        fs.rmRecursive("/var");
        System.out.println("After rmRecursive /var, ls /: " + fs.ls("/"));
        System.out.println();

        // ─── Scenario 8: Verify tree integrity ───
        System.out.println("=== Scenario 8: File size rollup ===");
        fs.mkdir("/tmp");
        fs.write("/tmp/a.txt", "12345");      // 5 bytes
        fs.write("/tmp/b.txt", "abcdefghij"); // 10 bytes
        fs.mkdirs("/tmp/sub");
        fs.write("/tmp/sub/c.txt", "xyz");    // 3 bytes
        System.out.println("Files created, total: 18 bytes");
        System.out.println("ls /tmp: " + fs.ls("/tmp"));
        System.out.println("ls /tmp/sub: " + fs.ls("/tmp/sub"));
    }

    private static void testError(Runnable operation, String description) {
        try {
            operation.run();
            System.out.println("  [FAIL] " + description + " — no exception thrown");
        } catch (FileSystemException e) {
            System.out.println("  [OK]   " + description + " → " + e.getMessage());
        }
    }
}
