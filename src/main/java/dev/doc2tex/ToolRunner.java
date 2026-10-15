package dev.doc2tex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
class ToolRunner {
    record Result(int exitCode, String log) {}

    Result run(List<String> command, Path directory, Path log, int timeoutSeconds) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("The operation exceeded its " + timeoutSeconds + " second time limit.");
            }
            return new Result(process.exitValue(), readLog(log));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private String readLog(Path log) throws IOException {
        try (InputStream input = Files.newInputStream(log)) {
            return new String(input.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
    }
}
