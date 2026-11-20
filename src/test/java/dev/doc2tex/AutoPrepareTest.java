package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-autoprepare-${random.uuid}")
class AutoPrepareTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @TempDir Path temporary;
    @LocalServerPort int port;
    @Autowired Tools tools;
    @Autowired JobStore store;
    @Autowired ConversionService service;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory()).build();
    }

    private Job upload(String filename, byte[] bytes, String template, boolean autoPrepare) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        parts.add("template", template);
        parts.add("autoPrepare", Boolean.toString(autoPrepare));
        var response = client().post().uri("/api/jobs").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts).retrieve().toEntity(Job.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        return response.getBody();
    }

    private Job awaitCompletedAndPreview(Job job) throws Exception {
        long deadline = System.nanoTime() + 180_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = client().get().uri("/api/jobs/" + job.id()).retrieve().body(Job.class);
            assertNotNull(job);
            if ("FAILED".equals(job.status())) return job;
            if ("COMPLETED".equals(job.status())
                    && ("READY".equals(job.previewStatus()) || "FAILED".equals(job.previewStatus()))) {
                return job;
            }
            Thread.sleep(100);
        }
        fail("Conversion and auto-preview timed out");
        return job;
    }

    @Test void autoPolishFixesDuplicateCaptionsIdempotentlyWithoutTouchingProseOrVerbatim() {
        String source = """
                \\documentclass{article}
                \\begin{document}
                Ordinary prose: see Figure 1 and Table 2.
                \\begin{figure}
                \\caption{Figure 1: Sensor overview}
                \\end{figure}
                \\begin{verbatim}
                \\begin{figure}
                \\caption{Figure 9 Literal sample}
                \\end{figure}
                \\end{verbatim}
                \\end{document}
                """;
        var first = AutoPolish.apply(source, null, List.of());
        assertTrue(first.changed());
        assertTrue(first.source().contains("\\caption{Sensor overview}"));
        assertTrue(first.source().contains("Ordinary prose: see Figure 1 and Table 2."));
        assertTrue(first.source().contains("\\caption{Figure 9 Literal sample}"));
        assertEquals(1, first.report().fixes().size());
        assertEquals("duplicate-captions", first.report().fixes().getFirst().code());

        var second = AutoPolish.apply(first.source(), null, List.of());
        assertFalse(second.changed());
        assertEquals(first.source(), second.source());
        assertTrue(second.report().fixes().isEmpty());
    }

    @Test void autoPolishConvertsReferencedBmpToPngAndSkipsVectorImages() throws Exception {
        Path output = temporary.resolve("polish-images");
        Path media = output.resolve("media").resolve("media");
        Files.createDirectories(media);

        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, Color.BLUE.getRGB());
        Path bmp = media.resolve("diagram.bmp");
        assertTrue(ImageIO.write(img, "bmp", bmp.toFile()));
        Files.writeString(media.resolve("vector.emf"), "synthetic-emf");

        String source = """
                \\documentclass{article}
                \\begin{document}
                \\includegraphics[width=2in]{media/media/diagram.bmp}
                \\includegraphics{media/media/diagram.bmp}
                % \\includegraphics{media/media/diagram.bmp}
                \\includegraphics{media/media/vector.emf}
                \\end{document}
                """;
        var result = AutoPolish.apply(source, output, List.of());
        assertTrue(result.changed());
        assertTrue(Files.isRegularFile(media.resolve("diagram.png")));
        assertTrue(Files.exists(bmp), "Commented reference retains original BMP file safely");
        assertTrue(result.source().contains("\\includegraphics[width=2in]{media/media/diagram.png}"));
        assertTrue(result.source().contains("\\includegraphics{media/media/diagram.png}"));
        assertTrue(result.source().contains("% \\includegraphics{media/media/diagram.bmp}"));
        assertEquals(1, result.report().fixes().size());
        assertEquals("image-conversion", result.report().fixes().getFirst().code());
        assertEquals(1, result.report().fixes().getFirst().count());
        assertEquals(1, result.report().skipped().size());
        assertTrue(result.report().skipped().getFirst().contains("vector.emf"));

        // When no reference (not even in comments) remains, the replaced BMP is deleted cleanly.
        Path outputClean = temporary.resolve("polish-images-clean");
        Path mediaClean = outputClean.resolve("media");
        Files.createDirectories(mediaClean);
        Path bmpClean = mediaClean.resolve("only.bmp");
        assertTrue(ImageIO.write(img, "bmp", bmpClean.toFile()));
        var cleanResult = AutoPolish.apply("\\documentclass{article}\\begin{document}\\includegraphics{media/only.bmp}\\end{document}",
                outputClean, List.of());
        assertTrue(cleanResult.changed());
        assertTrue(Files.isRegularFile(mediaClean.resolve("only.png")));
        assertFalse(Files.exists(bmpClean));
    }

    @Test void readinessEvaluatorComputesReadyNotesAndAttentionStates() throws Exception {
        Path jobDir = temporary.resolve("readiness-eval");
        Files.createDirectories(jobDir.resolve("output"));
        String cleanSource = "\\documentclass{article}\n\\begin{document}\n\\caption{Observation}\n\\end{document}\n";

        Job readyJob = new Job(UUID.randomUUID().toString(), "sample.docx", "article", "COMPLETED", "READY",
                "Ready", "PDF preview is ready.", Instant.now(), Instant.now(), 100, 1);
        var autoPolish = new AutoPolish.Report(0, 1, ReviewEvidence.digest(cleanSource),
                List.of(new AutoPolish.Fix("duplicate-captions", "Fixed duplicate figure/table numbers",
                        "Removed repeated numbers from 1 caption so LaTeX numbers them once.", 1)),
                List.of());
        var verification = new ReadinessCheck.Verification(1, true, 2, 0.96, 25, 24);
        var benignFindings = List.of(
                new DocumentInspector.Finding("captions", "warning", "Captions already contain numbers", "Duplicate numbers"),
                new DocumentInspector.Finding("revisions", "info", "Tracked changes accepted", "Accepted"),
                new DocumentInspector.Finding("comments", "info", "Review comments excluded", "Excluded"),
                new DocumentInspector.Finding("source-page-layout-review", "warning", "Page layout needs review",
                        "Some sections have incomplete page size or margin settings. Using the selected paper size and margins instead.")
        );
        var ready = ReadinessCheck.evaluate(readyJob, jobDir, cleanSource, benignFindings, null, autoPolish, verification);
        assertEquals("ready", ready.status());
        assertEquals("Ready to use", ready.headline());
        assertTrue(ready.pdfVerified());
        assertEquals(2, ready.pageCount());
        assertTrue(ready.actions().isEmpty());
        assertFalse(ready.handledAutomatically().isEmpty());

        var notesFindings = List.of(new DocumentInspector.Finding("merged", "warning", "Merged table cells", "Check rows"));
        var withNotes = ReadinessCheck.evaluate(readyJob, jobDir, cleanSource, notesFindings, null, null, verification);
        assertEquals("ready-with-notes", withNotes.status());
        assertEquals(1, withNotes.actions().size());
        assertEquals("merged", withNotes.actions().getFirst().code());
        assertEquals("note", withNotes.actions().getFirst().severity());

        var attentionFindings = List.of(
                new DocumentInspector.Finding("charts", "warning", "Embedded charts detected", "Export as PNG"),
                new DocumentInspector.Finding("merged", "warning", "Merged table cells", "Check rows")
        );
        var attention = ReadinessCheck.evaluate(readyJob, jobDir, cleanSource, attentionFindings, null, null, verification);
        assertEquals("needs-attention", attention.status());
        assertTrue(attention.actions().stream().anyMatch(item -> "charts".equals(item.code()) && "blocking".equals(item.severity())));
    }

    @Test void verifyPdfCountsAcceptedBodyWordsAndIgnoresTrackedDeletions() throws Exception {
        Path docx = temporary.resolve("verify.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(docx))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<w:document xmlns:w=\"" + W + "\"><w:body>"
                    + "<w:p><w:r><w:t>Accepted paragraph contains distinct measurable vocabulary words.</w:t></w:r>"
                    + "<w:del><w:r><w:delText>DeletedSecretTokenShouldBeIgnored</w:delText></w:r></w:del></w:p>"
                    + "</w:body></w:document>";
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path pdf = temporary.resolve("verify.pdf");
        try (var doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("Accepted paragraph contains distinct measurable vocabulary words.");
                stream.endText();
            }
            doc.save(pdf.toFile());
        }
        var verification = ReadinessCheck.verifyPdf(docx, pdf, 0);
        assertTrue(verification.pdfVerified());
        assertEquals(1, verification.pageCount());
        assertTrue(verification.checkedTokens() >= 6);
        assertEquals(verification.checkedTokens(), verification.matchedTokens());
        assertNotNull(verification.textCoverage());
        assertEquals(1.0, verification.textCoverage(), 0.001);
    }

    @Test void autoPrepareFixesCaptionsAndExportsReportEvenWithoutTectonic() throws Exception {
        assumeTrue(tools.available(tools.pandoc()), "Install Pandoc to run real conversion auto-polish checks");
        Path isolatedData = temporary.resolve("no-tectonic-data");
        var props = new AppProperties(isolatedData.toString(), tools.pandoc(), "missing-tectonic-binary", 1, 4, 30, 30);
        var localTools = new Tools(props);
        var localStore = new JobStore(props);
        localStore.load();
        var localService = new ConversionService(localStore, localTools, new ToolRunner(), new DocxValidator(),
                props, new DocumentInspector(), new FontSupport());
        try {
            byte[] corpus;
            try (var input = getClass().getResourceAsStream("/corpus/research-complex.docx")) {
                assertNotNull(input);
                corpus = input.readAllBytes();
            }
            Job job = localService.submit(
                    new MockMultipartFile("file", "research-complex.docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", corpus),
                    "article", LayoutOptions.uploadDefaults(), true);
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline) {
                job = localStore.get(job.id());
                if ("COMPLETED".equals(job.status()) || "FAILED".equals(job.status())) break;
                Thread.sleep(50);
            }
            assertEquals("COMPLETED", job.status(), job.message());
            assertEquals("NOT_REQUESTED", job.previewStatus());
            assertEquals(1, job.revision());

            var history = localService.versions(job.id());
            assertEquals(2, history.items().size());
            assertEquals("auto-fixed", history.items().getFirst().origin());
            assertEquals("converted", history.items().getLast().origin());
            assertTrue(localService.versionSource(job.id(), 0).contains("\\DocCaptionNumber{Figure}{1}{}"));
            assertFalse(localService.source(job.id()).contains("\\DocCaptionNumber{Figure}{1}{}"));

            var review = localService.review(job.id());
            assertNotNull(review.readiness());
            assertEquals("ready-with-notes", review.readiness().status());
            assertFalse(review.readiness().pdfVerified());
            assertTrue(review.readiness().autoFixes().stream().anyMatch(f -> "duplicate-captions".equals(f.code())));
            assertTrue(review.readiness().actions().stream().noneMatch(a -> "captions".equals(a.code())));

            var archiveBytes = new ByteArrayOutputStream();
            localService.writeArchive(job.id(), archiveBytes);
            Map<String, byte[]> entries = new HashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes.toByteArray()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
            assertTrue(entries.containsKey("auto-polish.json"));
        } finally {
            localService.stop();
        }
    }

    @Test void autoPrepareBuildsAndVerifiesReadySampleDocumentPdf() throws Exception {
        assumeTrue(Boolean.getBoolean("doc2tex.test-pdf"), "Enable with -Ddoc2tex.test-pdf=true");
        assumeTrue(tools.available(tools.pandoc()) && tools.available(tools.tectonic()));

        Job job = awaitCompletedAndPreview(upload("sample.docx", SampleDocument.create(), "article", true));
        assertEquals("COMPLETED", job.status(), job.message());
        assertEquals("READY", job.previewStatus(), job.previewMessage());

        var review = client().get().uri("/api/jobs/" + job.id() + "/review").retrieve().body(DocumentInspector.Review.class);
        assertNotNull(review);
        assertNotNull(review.readiness());
        assertEquals("ready", review.readiness().status());
        assertTrue(review.readiness().pdfVerified());
        assertNotNull(review.readiness().pageCount());
        assertTrue(review.readiness().pageCount() >= 1);
        assertNotNull(review.readiness().textCoverage());
        assertTrue(review.readiness().textCoverage() >= 0.85, "Coverage was " + review.readiness().textCoverage());
        assertTrue(review.readiness().actions().isEmpty());
    }

    @Test void autoPrepareFixesResearchComplexCaptionsAndPreservesOriginalVersion0() throws Exception {
        assumeTrue(Boolean.getBoolean("doc2tex.test-pdf"), "Enable with -Ddoc2tex.test-pdf=true");
        assumeTrue(tools.available(tools.pandoc()) && tools.available(tools.tectonic()));

        byte[] corpus;
        try (var input = getClass().getResourceAsStream("/corpus/research-complex.docx")) {
            assertNotNull(input);
            corpus = input.readAllBytes();
        }
        Job job = awaitCompletedAndPreview(upload("research-complex.docx", corpus, "article", true));
        assertEquals("COMPLETED", job.status(), job.message());
        assertEquals("READY", job.previewStatus(), job.previewMessage());
        assertEquals(1, job.revision());

        String base = "/api/jobs/" + job.id();
        var history = client().get().uri(base + "/versions").retrieve().body(SourceVersionStore.History.class);
        assertNotNull(history);
        assertEquals("auto-fixed", history.items().getFirst().origin());
        assertEquals("converted", history.items().getLast().origin());
        String v0 = client().get().uri(base + "/versions/0/source").retrieve().body(String.class);
        assertNotNull(v0);
        assertTrue(v0.contains("\\DocCaptionNumber{Figure}{1}{}"));

        var review = client().get().uri(base + "/review").retrieve().body(DocumentInspector.Review.class);
        assertNotNull(review);
        assertNotNull(review.readiness());
        assertTrue(review.readiness().pdfVerified());
        assertTrue(review.readiness().autoFixes().stream().anyMatch(fix -> "duplicate-captions".equals(fix.code())));
        assertTrue(review.readiness().actions().stream().noneMatch(item -> "captions".equals(item.code())));
    }

    @Test void autoPrepareReportsNeedsAttentionForChartsWhileSummarizingHandledItems() throws Exception {
        assumeTrue(Boolean.getBoolean("doc2tex.test-pdf"), "Enable with -Ddoc2tex.test-pdf=true");
        assumeTrue(tools.available(tools.pandoc()) && tools.available(tools.tectonic()));

        byte[] corpus;
        try (var input = getClass().getResourceAsStream("/corpus/revisions-layout.docx")) {
            assertNotNull(input);
            corpus = input.readAllBytes();
        }
        Job job = awaitCompletedAndPreview(upload("revisions-layout.docx", corpus, "article", true));
        assertEquals("COMPLETED", job.status(), job.message());

        var review = client().get().uri("/api/jobs/" + job.id() + "/review").retrieve().body(DocumentInspector.Review.class);
        assertNotNull(review);
        assertNotNull(review.readiness());
        assertEquals("needs-attention", review.readiness().status());
        assertTrue(review.readiness().actions().stream()
                .anyMatch(item -> "charts".equals(item.code()) && "blocking".equals(item.severity())));
        assertTrue(review.readiness().handledAutomatically().stream()
                .anyMatch(text -> text.contains("tracked changes")));
        assertTrue(review.readiness().handledAutomatically().stream()
                .anyMatch(text -> text.contains("review comments")));
    }
}
