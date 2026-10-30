package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolsProbeTest {
    @Test void exitedToolDoesNotWaitForAChildThatKeepsItsOutputOpen() throws Exception {
        String executable = ProcessHandle.current().info().command().orElseThrow();
        String version = "synthetic-tool-" + java.util.UUID.randomUUID();
        var result = Tools.inspect("Synthetic tool", executable, List.of("-cp", System.getProperty("java.class.path"), ToolsProbeTest.class.getName(), "spawn", version));
        assertEquals("ready", result.state(), result.toString());
        assertEquals(version, result.version());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (captureExists(version) && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(captureExists(version), "The diagnostic capture is deleted after the inherited output handle closes");
    }

    private static boolean captureExists(String version) throws Exception {
        try (var paths = java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
            for (var path : paths.filter(path -> path.getFileName().toString().startsWith("doc2tex-tool-probe-")).toList()) {
                try (var input = java.nio.file.Files.newInputStream(path)) {
                    if (new String(input.readNBytes(512), java.nio.charset.StandardCharsets.UTF_8).contains(version)) return true;
                } catch (java.io.IOException ignored) { }
            }
            return false;
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && arguments[0].equals("spawn")) {
            new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
                    ToolsProbeTest.class.getName(), "child").inheritIO().start();
            System.out.println(arguments[1]);
        } else Thread.sleep(2500);
    }
}
