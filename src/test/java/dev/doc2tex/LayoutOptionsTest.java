package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

class LayoutOptionsTest {
    @TempDir Path directory;
    private AppProperties properties() { return new AppProperties(directory.toString(), "", "", 1, 2, 30, 300); }
    private ConversionService service(JobStore store) { return new ConversionService(store, new Tools(properties()), new ToolRunner(), new DocxValidator(), properties(), new DocumentInspector(), new FontSupport()); }
    private Job await(JobStore store, Job job, boolean pdf) throws Exception {
        long deadline = System.nanoTime() + (pdf ? 300L : 30L) * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = store.get(job.id()); String state = pdf ? job.previewStatus() : job.status();
            if (List.of("READY", "COMPLETED", "FAILED").contains(state)) { assertNotEquals("FAILED", state, job.toString()); return job; }
            Thread.sleep(50);
        }
        fail("Timed out waiting for synthetic layout job"); return job;
    }
    private LayoutOptions options(String paper, int font, String margin) { return new LayoutOptions(paper, font, new BigDecimal(margin), "LAYOUT_TITLE A&B_#% \\input{R08_SENTINEL} ภาษาไทย中文", "AUTHOR_METADATA Jane Doe", "2026-10-16"); }

    @Test void optionsValidateAndLegacyMetadataMigratesWithoutRewritingSource() throws Exception {
        var defaults = LayoutOptions.defaults(); assertEquals(defaults, LayoutOptions.parse(null));
        for (String json : List.of("null", "{}", "[]", "{\"paper\":\"a4;input\"}", "x".repeat(8193))) assertThrows(ApiException.class, () -> LayoutOptions.parse(json));
        for (String margin : List.of("9.9", "50.1", "25.45")) assertThrows(ApiException.class, () -> options("a4", 11, margin));
        assertThrows(ApiException.class, () -> options("legal", 11, "25")); assertThrows(ApiException.class, () -> options("a4", 14, "25"));
        assertThrows(ApiException.class, () -> new LayoutOptions("a4", 11, BigDecimal.TEN, "bad\ntext", "", ""));
        assertThrows(ApiException.class, () -> new LayoutOptions("a4", 11, BigDecimal.TEN, "x".repeat(201), "", ""));
        var store = new JobStore(properties()); store.load();
        Job legacy = new Job(UUID.randomUUID().toString(), "legacy.docx", "article", "COMPLETED", "NOT_REQUESTED", "", "", Instant.now(), Instant.now(), 100, 0);
        store.save(legacy); Path project = store.directory(legacy.id()); Files.createDirectories(project.resolve("output"));
        String source = "\\documentclass{article}\n\\begin{document}\nLEGACY_EDITED_BODY\n\\end{document}";
        Files.writeString(project.resolve("output/main.tex"), source); store.versions().initialize(legacy, project, false);
        var metadata = new Properties(); Path file = project.resolve("job.properties");
        try (var input = Files.newInputStream(file)) { metadata.load(input); }
        metadata.keySet().removeIf(key -> key.toString().startsWith("layout.")); try (var output = Files.newOutputStream(file)) { metadata.store(output, "Synthetic legacy job"); }
        var restarted = new JobStore(properties()); restarted.load(); assertEquals(defaults, restarted.get(legacy.id()).layout());
        assertEquals(source, new String(restarted.versions().current(restarted.get(legacy.id()), project), StandardCharsets.UTF_8));
        assertEquals(1, restarted.versions().list(restarted.get(legacy.id()), project).items().size());
    }

    @Test void separateLayoutConversionPreservesParentEditsVersionsOriginalAndSettingsAcrossRestart() throws Exception {
        var tools = new Tools(properties()); assumeTrue(tools.available(tools.pandoc()));
        var store = new JobStore(properties()); store.load(); var service = service(store);
        try {
            Job parent = await(store, service.submit(new MockMultipartFile("file", "layout.docx", "application/octet-stream", SampleDocument.create()), "article"), false);
            String baseline = service.source(parent.id()), edited = baseline + "\n% KEEP_PARENT_EDITS";
            parent = service.saveSource(parent.id(), edited, 0); byte[] original = Files.readAllBytes(service.original(parent.id()));
            Job savedParent = parent; var layout = options("a4", 12, "30");
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.deriveLayout(savedParent.id(), 0, "report", layout)).status);
            Job derived = await(store, service.deriveLayout(parent.id(), 1, "report", layout), false);
            assertNotEquals(parent.id(), derived.id()); assertEquals(0, derived.revision()); assertEquals(layout, derived.layout());
            assertTrue(service.review(derived.id()).findings().stream().anyMatch(finding -> finding.code().equals("metadata-fonts")));
            assertFalse(service.source(derived.id()).contains("KEEP_PARENT_EDITS"));
            assertEquals(edited, service.source(parent.id())); assertEquals(baseline, service.versionSource(parent.id(), 0));
            assertArrayEquals(original, Files.readAllBytes(service.original(parent.id()))); assertArrayEquals(original, Files.readAllBytes(service.original(derived.id())));
            derived = service.saveSource(derived.id(), service.source(derived.id()) + "\n% local copy edit", 0);
            derived = service.restore(derived.id(), 0, 1); derived = store.rename(derived.id(), "Layout draft");
            assertEquals(layout, derived.layout()); var restarted = new JobStore(properties()); restarted.load(); assertEquals(layout, restarted.get(derived.id()).layout());
            assertEquals(edited, new String(restarted.versions().current(restarted.get(parent.id()), restarted.directory(parent.id())), StandardCharsets.UTF_8));
            var archive = new ByteArrayOutputStream(); service.writeArchive(derived.id(), archive); boolean settings = false, readme = false, thai = false, cjk = false;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    byte[] bytes = zip.readAllBytes();
                    if (entry.getName().equals("layout-settings.json")) { settings = true; assertEquals(layout, JsonMapper.builder().build().readValue(bytes, LayoutOptions.class)); }
                    if (entry.getName().equals("README.txt")) { readme = true; String text = new String(bytes, StandardCharsets.UTF_8); assertTrue(text.contains("A4, 12 pt, 30 mm")); assertTrue(text.contains("XeLaTeX")); }
                    if (entry.getName().equals("fonts/NotoSansThai-Regular.ttf")) thai = true;
                    if (entry.getName().equals("fonts/NotoSansCJKsc-Regular.otf")) cjk = true;
                }
            }
            assertTrue(settings && readme && thai && cjk, "Layout export must carry settings, compiler instructions and metadata-only fonts");
        } finally { service.stop(); }
    }

    @ParameterizedTest @CsvSource({"article,a4,11,20", "report,letter,12,30", "article,letter,10,25.4", "report,a4,11,15"})
    void selectedLayoutAndEscapedUnicodeMetadataProduceRealPdf(String template, String paper, int font, String margin) throws Exception {
        var tools = new Tools(properties()); assumeTrue(Boolean.getBoolean("doc2tex.test-pdf")); assumeTrue(tools.available(tools.pandoc()) && tools.available(tools.tectonic()));
        var store = new JobStore(properties()); store.load(); var service = service(store);
        try {
            var layout = options(paper, font, margin);
            Job job = await(store, service.submit(new MockMultipartFile("file", "layout.docx", "application/octet-stream", SampleDocument.create()), template, layout), false);
            String source = service.source(job.id()); assertTrue(source.contains(font + "pt")); assertTrue(source.contains(paper + "paper")); assertTrue(source.contains("margin=" + layout.marginMm().toPlainString() + "mm"));
            assertFalse(source.contains("\\input{R08_SENTINEL}")); assertTrue(source.contains("\\textbackslash")); assertTrue(source.contains("\\&B\\_\\#\\%"));
            job = await(store, service.preview(job.id()), true); String log = Files.readString(store.directory(job.id()).resolve("preview.log"));
            assertFalse(log.toLowerCase(java.util.Locale.ROOT).contains("missing character"), log);
            try (var pdf = Loader.loadPDF(service.pdf(job.id()).toFile())) {
                float width = paper.equals("a4") ? 595.28f : 612f, height = paper.equals("a4") ? 841.89f : 792f;
                for (var page : pdf.getPages()) { assertEquals(width, page.getMediaBox().getWidth(), 1); assertEquals(height, page.getMediaBox().getHeight(), 1); }
                var bodyPositions = new ArrayList<TextPosition>();
                var stripper = new PDFTextStripper() {
                    @Override protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
                        if (text.startsWith("This")) bodyPositions.addAll(positions); super.writeString(text, positions);
                    }
                };
                String text = stripper.getText(pdf); assertTrue(text.contains("LAYOUT_TITLE"), text); assertTrue(text.contains("AUTHOR_METADATA"), text);
                assertFalse(bodyPositions.isEmpty(), text); assertEquals(layout.marginMm().floatValue() * 72 / 25.4f, bodyPositions.getFirst().getXDirAdj(), 1.5);
                assertEquals(font, bodyPositions.getFirst().getFontSize(), 0.3);
                Path evidence = Path.of(System.getProperty("doc2tex.build-directory", "target"), "fidelity-evidence", "layouts", template + "-" + paper + "-" + font);
                Files.createDirectories(evidence); Files.writeString(evidence.resolve("main.tex"), source); Files.writeString(evidence.resolve("pdf-text.txt"), text);
                Files.copy(service.pdf(job.id()), evidence.resolve("main.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { service.stop(); }
    }
}
