package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SetupDiagnosticsTest {
    @TempDir static Path storage;
    @LocalServerPort int port;
    @Autowired Tools tools;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("doc2tex.data-dir", () -> storage.resolve("jobs").toString());
        properties.add("doc2tex.pandoc", () -> storage.resolve("missing-pandoc").toString());
        properties.add("doc2tex.tectonic", () -> storage.resolve("missing-tectonic").toString());
    }

    @Test void apiSeparatesMissingToolsFontsAndFirstUseDownloadsWithoutStartingDownloads() {
        var client = RestClient.create("http://127.0.0.1:" + port);
        var status = client.get().uri("/api/system").retrieve().body(java.util.Map.class);
        assertNotNull(status); assertEquals(false, status.get("pandoc")); assertEquals(false, status.get("tectonic"));
        assertEquals(false, status.get("packaged")); assertEquals("", status.get("instanceId"));
        assertEquals(2, ((List<?>) status.get("diagnostics")).size());
        assertTrue(status.get("diagnostics").toString().contains("missing"));
        assertTrue(status.get("diagnostics").toString().contains("Install tools.cmd"));
        assertTrue(status.get("texGuidance").toString().contains("first PDF build"));
        assertInstanceOf(List.class, status.get("missingFonts"));
        assertFalse(Files.exists(storage.resolve(".tools")));
        var stop = client.post().uri("/api/system/stop").exchange((request, response) -> response.getStatusCode().value());
        assertEquals(404, stop);
    }

    @Test void partialFontsAndUnusableToolsStayDistinctFromReadyTools() throws Exception {
        Path fonts = storage.resolve("test-fonts"); Files.createDirectories(fonts);
        var properties = new AppProperties(storage.resolve("unused").toString(), "converter", "compiler", 1, 1, 60, 300);
        var status = new Tools(properties, fonts, (name, executable) -> new Tools.Diagnostic(name, name.equals("Pandoc") ? "ready" : "unusable", "Synthetic version", "Repair the compiler")).status();
        assertEquals(true, status.get("pandoc")); assertEquals(false, status.get("tectonic"));
        assertEquals(6, ((List<?>) status.get("missingFonts")).size());
        Files.writeString(fonts.resolve("NotoSerif-Regular.ttf"), "Synthetic font bytes");
        var partial = new Tools(properties, fonts, (name, executable) -> new Tools.Diagnostic(name, "ready", "", "")).status();
        assertEquals(5, ((List<?>) partial.get("missingFonts")).size());
        assertEquals("missing", Tools.inspect("Missing tool", storage.resolve("nonexistent.exe").toString()).state());
        Path broken = storage.resolve("broken-tool.exe"); Files.writeString(broken, "Synthetic damaged executable");
        assertEquals("unusable", Tools.inspect("Broken tool", broken.toString()).state());
        assertEquals("ready", Tools.inspect("Java", ProcessHandle.current().info().command().orElseThrow()).state());
    }

    @Test void offlineBundleAndSourceFailuresHaveDifferentRecoveryGuidance() {
        String offline = "error: this bundle isn't cached, and we couldn't get it from the internet. Error: error sending request for url (http://127.0.0.1:1/fixture.index.gz)";
        assertTrue(PreviewFailure.message(offline).contains("connection/proxy"));
        assertTrue(PreviewFailure.message(offline).contains("Source and ZIP export remain available"));
        assertTrue(PreviewFailure.message("error: main.tex:5: Undefined control sequence").contains("edit the source"));
        assertFalse(PreviewFailure.message("error: main.tex:5: Undefined control sequence").contains("download/cache"));
    }
}
