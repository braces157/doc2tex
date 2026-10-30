package dev.doc2tex;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("doc2tex.ai-layout")
record AiLayoutSettings(boolean enabled, String model, String renderer, int maxPages, int timeoutSeconds, String dockerImage) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    AiLayoutSettings {
        if (model == null || renderer == null || dockerImage == null || maxPages < 1 || maxPages > 12 || timeoutSeconds < 5 || timeoutSeconds > 300
                || (!dockerImage.isEmpty() && (dockerImage.length() > 200 || !dockerImage.matches("[A-Za-z0-9][A-Za-z0-9._:/@-]*"))))
            throw new IllegalArgumentException("AI layout requires 1–12 pages and a 5–300 second deadline.");
    }

    AiLayoutSettings(boolean enabled, String model, String renderer, int maxPages, int timeoutSeconds) {
        this(enabled, model, renderer, maxPages, timeoutSeconds, "");
    }

    static AiLayoutSettings disabled() { return new AiLayoutSettings(false, ".tools/layout-ai/inference.onnx", "", 6, 90); }

    Path modelPath() { return Path.of(model).toAbsolutePath().normalize(); }

    String rendererExecutable() {
        if (!renderer.isBlank()) return renderer;
        if (System.getProperty("os.name").startsWith("Windows")) {
            for (Path path : new Path[]{Path.of(".tools/libreoffice/program/soffice.com"),
                    Path.of(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"), "LibreOffice/program/soffice.com")})
                if (Files.isRegularFile(path)) return path.toAbsolutePath().toString();
            return "soffice.com";
        }
        Path mac = Path.of("/Applications/LibreOffice.app/Contents/MacOS/soffice");
        return Files.isRegularFile(mac) ? mac.toString() : "libreoffice";
    }
}
