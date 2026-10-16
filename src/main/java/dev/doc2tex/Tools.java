package dev.doc2tex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
class Tools {
    private final String pandoc;
    private final String tectonic;
    private final Path fonts;
    private final Probe probe;

    record Diagnostic(String name, String state, String version, String guidance) {}
    interface Probe { Diagnostic check(String name, String executable); }

    @org.springframework.beans.factory.annotation.Autowired
    Tools(AppProperties properties) {
        this(properties, Path.of(System.getenv().getOrDefault("DOC2TEX_FONT_DIR", ".tools/fonts")), Tools::inspect);
    }

    Tools(AppProperties properties, Path fonts, Probe probe) {
        pandoc = resolve(properties.pandoc(), "pandoc");
        tectonic = resolve(properties.tectonic(), "tectonic");
        this.fonts = fonts; this.probe = probe;
    }

    String pandoc() { return pandoc; }
    String tectonic() { return tectonic; }

    Map<String, Object> status() {
        Diagnostic converter = probe.check("Pandoc", pandoc), compiler = probe.check("Tectonic", tectonic);
        List<String> missing = List.of("NotoSerif-Regular.ttf", "NotoSansThai-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "NotoSansCJKsc-Regular.otf", "OFL-Noto.txt", "OFL-Noto-CJK.txt")
                .stream().filter(name -> !Files.isRegularFile(fonts.resolve(name)) || !Files.isReadable(fonts.resolve(name))).toList();
        return Map.of("pandoc", converter.state().equals("ready"), "tectonic", compiler.state().equals("ready"),
                "maxUploadBytes", 20 * 1024 * 1024, "templates", new String[]{"article", "report"},
                "diagnostics", List.of(converter, compiler), "missingFonts", missing,
                "texGuidance", "The first PDF build may download TeX packages. Connect to the internet before building; conversion and ZIP export do not need these packages. A populated cache is not proof that every document will compile offline.",
                "packaged", PackagedRuntime.packaged(), "instanceId", PackagedRuntime.id());
    }

    private String resolve(String configured, String tool) {
        if (configured != null && !configured.isBlank()) return configured;
        String executable = tool + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
        Path local = Path.of(".tools", tool, executable).toAbsolutePath();
        return Files.isRegularFile(local) ? local.toString() : executable;
    }

    boolean available(String executable) { return probe.check("Tool", executable).state().equals("ready"); }

    static Diagnostic inspect(String name, String executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "--version").redirectErrorStream(true)
                    .start();
            Process running = process;
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var input = running.getInputStream()) { return new String(input.readNBytes(4096), StandardCharsets.UTF_8); }
                catch (IOException ignored) { return ""; }
            });
            if (!process.waitFor(3, TimeUnit.SECONDS)) return new Diagnostic(name, "unusable", "", name + " did not respond. Close the app, repair/install tools, and relaunch.");
            if (process.exitValue() != 0) return new Diagnostic(name, "unusable", "", name + " exited with an error. Close the app and repair its tool installation.");
            String version = output.get(1, TimeUnit.SECONDS).lines().findFirst().orElse("").replaceAll("[\\p{Cntrl}]", "");
            return new Diagnostic(name, "ready", version.substring(0, Math.min(version.length(), 200)), name + " is ready.");
        } catch (IOException exception) {
            boolean present;
            try { present = Files.isRegularFile(Path.of(executable)); } catch (RuntimeException ignored) { present = false; }
            return new Diagnostic(name, present ? "unusable" : "missing", "", name + " could not be started. Close the app and run Install tools.cmd in the Windows package, or scripts/setup-tools.ps1 -Repair for a development checkout. Then relaunch.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); return new Diagnostic(name, "unusable", "", "Tool checking was interrupted. Retry setup diagnostics.");
        } catch (Exception exception) {
            return new Diagnostic(name, "unusable", "", name + " could not be checked. Retry diagnostics or repair its tool installation.");
        } finally {
            if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        }
    }
}
