package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-corpus-${random.uuid}")
class HardDocumentTest {
    @Autowired ConversionService service;
    @Autowired JobStore store;
    @Autowired Tools tools;
    @Autowired DocumentInspector inspector;

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

    @ParameterizedTest
    @ValueSource(strings = {"research-complex.docx", "multilingual.docx", "revisions-layout.docx", "long-register.docx"})
    void preservesRequiredContentAndRecordsSourceFeatures(String filename) throws Exception {
        for (String template : List.of("article", "report")) {
            Job job = convert(filename, template);
            String source = Normalizer.normalize(service.source(job.id()).replace("\\_", "_"), Normalizer.Form.NFC);
            try (var manifestStream = getClass().getResourceAsStream("/corpus/manifest.json")) {
                var manifest = JsonMapper.builder().build().readTree(manifestStream);
                var item = java.util.stream.StreamSupport.stream(manifest.spliterator(), false)
                        .filter(node -> node.get("file").asString().equals(filename)).findFirst().orElseThrow();
                for (var marker : item.get("mustContain")) assertTrue(source.contains(marker.asString()), filename + " lost: " + marker.asString());
                if (item.has("mustNotContain")) for (var marker : item.get("mustNotContain")) assertFalse(source.contains(marker.asString()));
            }
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
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"research-complex.docx", "multilingual.docx", "revisions-layout.docx", "long-register.docx"})
    void compiledCorpusHasNoMissingGlyphs(String filename) throws Exception {
        assumeTrue(Boolean.getBoolean("doc2tex.test-pdf"), "Enable with -Ddoc2tex.test-pdf=true; first run needs TeX downloads");
        assumeTrue(tools.available(tools.tectonic()));
        assumeTrue(Files.exists(Path.of(".tools/fonts/NotoSerif-Regular.ttf")));
        Job job = convert(filename, "article");
        service.preview(job.id());
        long deadline = System.nanoTime() + 310_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = store.get(job.id());
            if (job.previewStatus().equals("READY")) {
                String log = service.compilerLog(job.id());
                assertFalse(log.contains("Missing character:"), filename + " has missing glyphs: " + log);
                assertTrue(Files.size(service.pdf(job.id())) > 1000);
                return;
            }
            assertNotEquals("FAILED", job.previewStatus(), job.previewMessage() + "\n" + service.compilerLog(job.id()));
            Thread.sleep(200);
        }
        fail("PDF compilation exceeded its deadline");
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
