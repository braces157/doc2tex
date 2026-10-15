package dev.doc2tex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
class Tools {
    private final String pandoc;
    private final String tectonic;

    Tools(AppProperties properties) {
        pandoc = resolve(properties.pandoc(), "pandoc");
        tectonic = resolve(properties.tectonic(), "tectonic");
    }

    String pandoc() { return pandoc; }
    String tectonic() { return tectonic; }

    Map<String, Object> status() {
        return Map.of("pandoc", available(pandoc), "tectonic", available(tectonic),
                "maxUploadBytes", 20 * 1024 * 1024, "templates", new String[]{"article", "report"});
    }

    private String resolve(String configured, String tool) {
        if (configured != null && !configured.isBlank()) return configured;
        String executable = tool + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
        Path local = Path.of(".tools", tool, executable).toAbsolutePath();
        return Files.isRegularFile(local) ? local.toString() : executable;
    }

    boolean available(String executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
