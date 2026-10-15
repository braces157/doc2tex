package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-corpus-${random.uuid}")
class HardDocumentTest {
    @Autowired ConversionService service;
    @Autowired JobStore store;
    @Autowired Tools tools;
    @Autowired DocumentInspector inspector;

    private static JsonNode manifest() throws IOException {
        try (var stream = HardDocumentTest.class.getResourceAsStream("/corpus/manifest.json")) {
            assertNotNull(stream, "The corpus manifest must be present");
            return JsonMapper.builder().build().readTree(stream);
        }
    }

    static Stream<Arguments> fixtureTemplates() throws IOException {
        return StreamSupport.stream(manifest().spliterator(), false).flatMap(item ->
                Stream.of("article", "report").map(template -> Arguments.of(item.get("file").asString(), template)));
    }

    private JsonNode fixture(String filename) throws IOException {
        return StreamSupport.stream(manifest().spliterator(), false)
                .filter(item -> item.get("file").asString().equals(filename)).findFirst().orElseThrow();
    }

    private Job convert(String filename, String template) throws Exception {
        assumeTrue(tools.available(tools.pandoc()), "Pandoc is required for the corpus tests");
        try (var input = getClass().getResourceAsStream("/corpus/" + filename)) {
            assertNotNull(input, filename);
            Job job = service.submit(new MockMultipartFile("file", filename,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", input), template);
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline) {
                job = store.get(job.id());
                if (job.status().equals("COMPLETED")) return job;
                assertNotEquals("FAILED", job.status(), job.message());
                Thread.sleep(50);
            }
            fail("Conversion exceeded 30 seconds: " + filename); return job;
        }
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("fixtureTemplates")
    void preservesRequiredContentAndRecordsSourceFeatures(String filename, String template) throws Exception {
        Job job = convert(filename, template);
        String source = Normalizer.normalize(service.source(job.id()).replace("\\_", "_"), Normalizer.Form.NFC);
        var item = fixture(filename);
        for (var marker : item.get("mustContain")) assertTrue(source.contains(marker.asString()), filename + " lost: " + marker.asString());
        if (item.has("mustNotContain")) for (var marker : item.get("mustNotContain")) assertFalse(source.contains(marker.asString()));
        DocumentInspector.Review review = service.review(job.id());
        assertTrue(review.findings().stream().noneMatch(finding -> finding.code().equals("inspection_limit")));
        if (filename.equals("research-complex.docx")) {
            assertEquals(3, review.elements().get("equations"));
            assertEquals(1, review.elements().get("footnotes"));
            assertEquals(1, review.elements().get("images"));
            assertTrue(source.contains("\\frac{a + b}{c}"), source);
            assertTrue(source.contains("\\sqrt{x}"), source);
            assertTrue(source.contains("matrix"), source);
            assertTrue(source.contains("\\footnote"), source);
            assertTrue(review.findings().stream().anyMatch(finding -> finding.code().equals("merged")));
            assertTrue(review.findings().stream().anyMatch(finding -> finding.code().equals("captions")));
        }
        if (filename.equals("revisions-layout.docx")) {
            for (String code : List.of("revisions", "headers", "charts", "textboxes", "comments"))
                assertTrue(review.findings().stream().anyMatch(finding -> finding.code().equals(code)), "Missing review flag " + code);
        }
        if (filename.equals("multilingual.docx")) {
            assertTrue(review.scripts().containsAll(List.of("thai", "han", "arabic", "cyrillic", "greek")));
            if (Files.exists(Path.of(".tools/fonts/NotoSerif-Regular.ttf"))) {
                assertTrue(source.contains("NotoSansThai-Regular.ttf"));
                assertTrue(source.contains("\\DocThai{"));
                assertTrue(source.contains("\\textarabic{"));
            }
        }
        assertArchiveAssets(job, review.elements().get("images"));
    }

    private void assertArchiveAssets(Job job, int imageCount) throws Exception {
        var bytes = new ByteArrayOutputStream();
        service.writeArchive(job.id(), bytes);
        Map<String, byte[]> entries = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNull(entries.put(entry.getName(), zip.readAllBytes()), "Duplicate ZIP entry: " + entry.getName());
            }
        }
        Path output = store.directory(job.id()).resolve("output");
        assertArrayEquals(Files.readAllBytes(output.resolve("main.tex")), entries.get("main.tex"), "ZIP source differs from saved source");
        assertTrue(entries.containsKey("README.txt"));
        assertTrue(entries.containsKey("conversion-review.json"));
        var mapper = JsonMapper.builder().build();
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(service.review(job.id()))),
                mapper.readTree(entries.get("conversion-review.json")), "ZIP review differs from the job review");
        try (var files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = output.relativize(file).toString().replace('\\', '/');
                if (name.startsWith("media/") || name.startsWith("fonts/")) {
                    assertArrayEquals(Files.readAllBytes(file), entries.get(name), "ZIP omitted or changed asset: " + name);
                }
            }
        }
        String source = new String(entries.get("main.tex"), StandardCharsets.UTF_8);
        var images = Pattern.compile("\\\\includegraphics(?:\\[[^\\]]*\\])?\\{([^}]+)}").matcher(source);
        int references = 0;
        while (images.find()) {
            String path = images.group(1);
            assertTrue(entries.containsKey(path), "Missing referenced image in ZIP: " + path);
            assertTrue(entries.get(path).length > 0, "Empty image: " + path);
            references++;
        }
        if (imageCount > 0) assertTrue(references > 0, "Source images disappeared from LaTeX");
        var fonts = Pattern.compile("Noto[\\w-]+\\.(?:ttf|otf)").matcher(source);
        while (fonts.find()) {
            assertTrue(entries.containsKey("fonts/" + fonts.group()), "Missing referenced font: " + fonts.group());
            assertTrue(entries.containsKey("fonts/OFL-Noto.txt"), "Font license missing from ZIP");
            if (fonts.group().contains("CJK")) assertTrue(entries.containsKey("fonts/OFL-Noto-CJK.txt"));
        }
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("fixtureTemplates")
    void compiledCorpusPreservesPdfContentWithoutMissingGlyphs(String filename, String template) throws Exception {
        assumeTrue(Boolean.getBoolean("doc2tex.test-pdf"), "Enable with -Ddoc2tex.test-pdf=true; first run needs TeX downloads");
        assumeTrue(tools.available(tools.tectonic()));
        assumeTrue(Files.exists(Path.of(".tools/fonts/NotoSerif-Regular.ttf")));
        Job job = convert(filename, template);
        service.preview(job.id());
        long deadline = System.nanoTime() + 310_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = store.get(job.id());
            if (List.of("READY", "FAILED").contains(job.previewStatus())) {
                // The HTTP log is truncated; scan the entire compiler output in regression checks.
                Path logFile = store.directory(job.id()).resolve("preview.log");
                String log = Files.isRegularFile(logFile) ? Files.readString(logFile) : service.compilerLog(job.id());
                Path evidence = Path.of(System.getProperty("doc2tex.build-directory", "target"), "fidelity-evidence",
                        template, filename.replaceFirst("\\.docx$", ""));
                Files.createDirectories(evidence);
                Files.writeString(evidence.resolve("compiler.log"), log);
                assertEquals("READY", job.previewStatus(), job.previewMessage() + "\n" + log);
                Files.copy(service.pdf(job.id()), evidence.resolve("main.pdf"), StandardCopyOption.REPLACE_EXISTING);
                var expected = fixture(filename);
                try (var pdf = Loader.loadPDF(service.pdf(job.id()).toFile())) {
                    var stripper = new PDFTextStripper();
                    String text = stripper.getText(pdf);
                    Files.writeString(evidence.resolve("pdf-text.txt"), text);
                    assertTrue(pdf.getNumberOfPages() >= expected.get("pdfMinPages").asInt(), "PDF lost expected pages");
                    assertTrue(expected.get("pdfMustContain").size() > 0, "PDF checkpoints must not be empty");
                    for (var marker : expected.get("pdfMustContain")) {
                        assertTrue(compact(text).contains(compact(marker.asString())), filename + " (" + template + ") PDF lost: " + marker.asString());
                    }
                    for (var marker : expected.get("pdfMustNotContain")) {
                        assertFalse(compact(text).contains(compact(marker.asString())), "PDF retained deleted text: " + marker.asString());
                    }
                    if (expected.has("pdfLastPageMustContain")) {
                        stripper.setStartPage(pdf.getNumberOfPages());
                        stripper.setEndPage(pdf.getNumberOfPages());
                        String lastPage = compact(stripper.getText(pdf));
                        for (var marker : expected.get("pdfLastPageMustContain")) {
                            assertTrue(lastPage.contains(compact(marker.asString())), "Final-page checkpoint missing: " + marker.asString());
                        }
                    }
                }
                assertFalse(log.contains("Missing character:"), filename + " has missing glyphs: " + log);
                assertFalse(log.contains("does not contain the requested"), filename + " has unavailable glyphs: " + log);
                assertTrue(Files.size(service.pdf(job.id())) > 1000);
                return;
            }
            Thread.sleep(200);
        }
        fail("PDF compilation exceeded its deadline");
    }

    private String compact(String text) {
        // Kerning and line wrapping can insert spaces inside checkpoint words.
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[\\s\\p{Z}]+", "");
    }

    @Test void sourceInspectionRejectsXmlEntitiesWithoutResolvingThem() throws Exception {
        Path zip = Files.createTempFile("doc2tex-xxe-", ".docx");
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            output.write("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///C:/Windows/win.ini'>]><x>&secret;</x>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var review = inspector.review(zip, null);
        assertTrue(review.findings().stream().anyMatch(finding -> finding.code().equals("inspection_limit")));
        Files.deleteIfExists(zip);
    }

    @Test void compilerWarningsExposeMissingTextInsteadOfSilentlyPassing() throws Exception {
        Path log = Files.createTempFile("doc2tex-glyphs-", ".log");
        Files.writeString(log, "Missing character: There is no Thai glyph in font Latin Modern!\nOverfull \\hbox (12pt too wide)");
        var review = inspector.review(Path.of("src/test/resources/corpus/multilingual.docx"), log);
        assertTrue(review.compilerWarnings().stream().anyMatch(message -> message.contains("missing characters")));
        assertTrue(review.compilerWarnings().stream().anyMatch(message -> message.contains("exceeds")));
        Files.deleteIfExists(log);
    }
}
