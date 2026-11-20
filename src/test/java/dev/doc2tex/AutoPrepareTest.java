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
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
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

        // Retain replaced BMP alongside PNG so version 0 remains restorable without false image blockers on rev 1.
        Path outputClean = temporary.resolve("polish-images-clean");
        Path mediaClean = outputClean.resolve("media");
        Files.createDirectories(mediaClean);
        Path bmpClean = mediaClean.resolve("only.bmp");
        assertTrue(ImageIO.write(img, "bmp", bmpClean.toFile()));
        String cleanInput = "\\documentclass{article}\\begin{document}\\includegraphics{media/only.bmp}\\end{document}";
        var cleanResult = AutoPolish.apply(cleanInput, outputClean, List.of());
        assertTrue(cleanResult.changed());
        assertTrue(Files.isRegularFile(mediaClean.resolve("only.png")));
        assertTrue(Files.exists(bmpClean), "Original BMP must stay on disk for version restoration");

        Job rev1Job = new Job(UUID.randomUUID().toString(), "bmp.docx", "article", "COMPLETED", "READY",
                "Ready", "PDF preview is ready.", Instant.now(), Instant.now(), 100, 1);
        var cleanVerification = new ReadinessCheck.Verification(1, true, 1, 1.0, 10, 10);
        var imageFindings = List.of(new DocumentInspector.Finding("image_formats", "warning", "Images may need conversion", "Use PNG"));
        var rev1Readiness = ReadinessCheck.evaluate(rev1Job, outputClean, cleanResult.source(),
                imageFindings, null, cleanResult.report(), cleanVerification);
        assertTrue(rev1Readiness.actions().stream().noneMatch(a -> "image_formats".equals(a.code())));
        assertTrue(rev1Readiness.actions().stream().noneMatch(a -> a.code().startsWith("auto-polish-skipped-")));
    }

    @Test void autoPolishConvertsSinglePageTiffAndSkipsMultiPageTiff() throws Exception {
        var writers = ImageIO.getImageWritersByFormatName("tiff");
        assertTrue(writers.hasNext(), "JDK ImageIO TIFF writer is required for multi-page TIFF checks");
        ImageWriter writer = writers.next();
        Path output = temporary.resolve("polish-tiff");
        Path media = output.resolve("media");
        Files.createDirectories(media);
        BufferedImage pageOne = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        BufferedImage pageTwo = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        pageTwo.setRGB(0, 0, Color.RED.getRGB());
        try {
            try (ImageOutputStream singleOut = ImageIO.createImageOutputStream(media.resolve("single.tif").toFile())) {
                writer.setOutput(singleOut);
                writer.write(null, new IIOImage(pageOne, null, null), null);
            }
            try (ImageOutputStream multiOut = ImageIO.createImageOutputStream(media.resolve("multi.tif").toFile())) {
                writer.setOutput(multiOut);
                writer.prepareWriteSequence(null);
                writer.writeToSequence(new IIOImage(pageOne, null, null), (ImageWriteParam) null);
                writer.writeToSequence(new IIOImage(pageTwo, null, null), (ImageWriteParam) null);
                writer.endWriteSequence();
            }
        } finally {
            writer.dispose();
        }
        String source = "\\documentclass{article}\\begin{document}"
                + "\\includegraphics{media/single.tif}"
                + "\\includegraphics{media/multi.tif}"
                + "\\end{document}";
        var result = AutoPolish.apply(source, output, List.of());
        assertTrue(result.changed());
        assertTrue(Files.isRegularFile(media.resolve("single.png")));
        assertFalse(Files.exists(media.resolve("multi.png")));
        assertTrue(result.source().contains("\\includegraphics{media/single.png}"));
        assertTrue(result.source().contains("\\includegraphics{media/multi.tif}"));
        assertEquals(1, result.report().fixes().getFirst().count());
        assertTrue(result.report().skipped().stream().anyMatch(s -> s.contains("multi.tif") && s.contains("Multi-page")));
    }

    @Test void restoringVersion0RetainsOriginalBmpInExportAndRestoresImageBlocker() throws Exception {
        Path dataDir = temporary.resolve("restore-bmp-data");
        var props = new AppProperties(dataDir.toString(), "", "missing-tectonic-binary", 1, 2, 30, 30);
        var localStore = new JobStore(props);
        localStore.load();
        var localService = new ConversionService(localStore, new Tools(props), new ToolRunner(), new DocxValidator(),
                props, new DocumentInspector(), new FontSupport());
        try {
            Job initial = new Job(UUID.randomUUID().toString(), "diagram.docx", "article", "COMPLETED", "NOT_REQUESTED",
                    "Ready", "", Instant.now(), Instant.now(), 100, 0);
            localStore.save(initial);
            Path jobDir = localStore.directory(initial.id());
            Path output = jobDir.resolve("output");
            Files.createDirectories(output.resolve("media"));
            writeBmpDocx(jobDir.resolve("input.docx"));

            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(img, "bmp", output.resolve("media/diagram.bmp").toFile()));
            String v0Source = "\\documentclass{article}\n\\begin{document}\n\\includegraphics{media/diagram.bmp}\n\\end{document}\n";
            Files.writeString(output.resolve("main.tex"), v0Source);
            localStore.versions().initialize(initial, jobDir, false);

            var polish = AutoPolish.apply(v0Source, output, List.of());
            AutoPolish.save(output, polish.report());
            Job rev1 = localStore.versions().commitAutoFix(initial, jobDir, polish.source(), localStore::save);
            assertEquals(1, rev1.revision());
            assertTrue(localService.review(rev1.id()).readiness().actions().stream()
                    .noneMatch(item -> "image_formats".equals(item.code())));

            Job restored = localService.restore(rev1.id(), 0, rev1.revision());
            assertEquals(2, restored.revision());
            assertTrue(localService.source(restored.id()).contains("media/diagram.bmp"));

            var archiveBytes = new ByteArrayOutputStream();
            localService.writeArchive(restored.id(), archiveBytes);
            Map<String, byte[]> entries = readZipEntries(archiveBytes.toByteArray());
            assertTrue(entries.containsKey("media/diagram.bmp"), "Restored ZIP must still contain v0 BMP");
            assertTrue(new String(entries.get("main.tex"), StandardCharsets.UTF_8).contains("media/diagram.bmp"));

            var restoredReview = localService.review(restored.id());
            assertEquals("needs-attention", restoredReview.readiness().status());
            assertTrue(restoredReview.readiness().actions().stream().anyMatch(item -> "image_formats".equals(item.code())));
            assertTrue(restoredReview.readiness().handledAutomatically().isEmpty());
        } finally {
            localService.stop();
        }
    }

    @Test void verifyPdfMatchesWholeWordsAndHandlesLineWrapHyphens() throws Exception {
        Path substringDocx = temporary.resolve("substrings.docx");
        writeDocx(substringDocx, "<w:p><w:r><w:t>cat dog hen ant rat bee owl pig cow fox</w:t></w:r></w:p>");
        Path supersetPdf = temporary.resolve("superset.pdf");
        writePdfLines(supersetPdf, List.of("catalog dogwood henhouse antler rattle beehive owlet pigment cowboy foxtrot"));

        var subVerification = ReadinessCheck.verifyPdf(substringDocx, supersetPdf, 0);
        assertFalse(subVerification.pdfVerified());
        assertEquals(10, subVerification.checkedTokens());
        assertEquals(0, subVerification.matchedTokens());
        assertNotNull(subVerification.textCoverage());
        assertEquals(0.0, subVerification.textCoverage(), 0.001);

        Path wrapDocx = temporary.resolve("wrap.docx");
        writeDocx(wrapDocx, "<w:p><w:r><w:t>measurement observation</w:t></w:r></w:p>");
        Path wrapPdf = temporary.resolve("wrap.pdf");
        writePdfLines(wrapPdf, List.of("measure-", "ment", "obser\u00AD", "vation"));

        var wrapVerification = ReadinessCheck.verifyPdf(wrapDocx, wrapPdf, 0);
        assertTrue(wrapVerification.pdfVerified());
        assertEquals(2, wrapVerification.checkedTokens());
        assertEquals(2, wrapVerification.matchedTokens());
        assertNotNull(wrapVerification.textCoverage());
        assertEquals(1.0, wrapVerification.textCoverage(), 0.001);
    }

    @Test void readinessBlocksBlankPdfOnSmallDocAndMarksUnmeasurableDocUnverified() throws Exception {
        Path jobDir = temporary.resolve("small-doc-eval");
        Files.createDirectories(jobDir.resolve("output"));

        Path eightTokenDocx = temporary.resolve("eight-tokens.docx");
        writeDocx(eightTokenDocx, "<w:p><w:r><w:t>alpha bravo charlie delta echo foxtrot golf hotel</w:t></w:r></w:p>");
        Path blankPdf = temporary.resolve("blank.pdf");
        writePdfLines(blankPdf, List.of());

        var blankVerification = ReadinessCheck.verifyPdf(eightTokenDocx, blankPdf, 0);
        assertFalse(blankVerification.pdfVerified());
        assertEquals(8, blankVerification.checkedTokens());
        assertEquals(0, blankVerification.matchedTokens());
        assertEquals(0.0, blankVerification.textCoverage(), 0.001);

        Job builtJob = new Job(UUID.randomUUID().toString(), "small.docx", "article", "COMPLETED", "READY",
                "Ready", "PDF preview is ready.", Instant.now(), Instant.now(), 100, 0);
        var blankReport = ReadinessCheck.evaluate(builtJob, jobDir, "\\documentclass{article}\\begin{document}x\\end{document}",
                List.of(), null, null, blankVerification);
        assertEquals("needs-attention", blankReport.status());
        assertFalse(blankReport.pdfVerified());
        assertTrue(blankReport.actions().stream()
                .anyMatch(item -> "low-text-coverage".equals(item.code()) && "blocking".equals(item.severity())));

        Path unmeasurableDocx = temporary.resolve("unmeasurable.docx");
        writeDocx(unmeasurableDocx, "<w:p><w:r><w:t>Hi</w:t></w:r></w:p>");
        Path onePagePdf = temporary.resolve("one-page.pdf");
        writePdfLines(onePagePdf, List.of("Hi"));
        var unmeasurableVerification = ReadinessCheck.verifyPdf(unmeasurableDocx, onePagePdf, 0);
        assertFalse(unmeasurableVerification.pdfVerified());
        assertEquals(0, unmeasurableVerification.checkedTokens());
        assertNull(unmeasurableVerification.textCoverage());

        var unmeasurableReport = ReadinessCheck.evaluate(builtJob, jobDir, "\\documentclass{article}\\begin{document}Hi\\end{document}",
                List.of(), null, null, unmeasurableVerification);
        assertEquals("ready-with-notes", unmeasurableReport.status());
        assertFalse(unmeasurableReport.pdfVerified());
        assertTrue(unmeasurableReport.actions().stream()
                .anyMatch(item -> "pdf-text-unverified".equals(item.code()) && "note".equals(item.severity())));
    }

    @Test void readinessExcludesHistoricalAutoFixesAndPreservesSkippedShortCaptions() throws Exception {
        Path jobDir = temporary.resolve("historical-and-short-caption");
        Files.createDirectories(jobDir.resolve("output"));

        String fixedSource = "\\documentclass{article}\n\\begin{document}\n\\caption{Observation}\n\\end{document}\n";
        String restoredV0 = "\\documentclass{article}\n\\begin{document}\n\\caption{Figure 1: Observation}\n\\end{document}\n";
        var historicalReport = new AutoPolish.Report(0, 1, ReviewEvidence.digest(fixedSource),
                List.of(new AutoPolish.Fix("duplicate-captions", "Fixed duplicate figure/table numbers",
                        "Removed repeated numbers from 1 caption so LaTeX numbers them once.", 1)),
                List.of());
        Job restoredJob = new Job(UUID.randomUUID().toString(), "sample.docx", "article", "COMPLETED", "READY",
                "Ready", "PDF preview is ready.", Instant.now(), Instant.now(), 100, 2);
        var verification = new ReadinessCheck.Verification(2, true, 1, 1.0, 10, 10);
        var captionFinding = List.of(new DocumentInspector.Finding("captions", "warning", "Captions already contain numbers", "Duplicate"));

        var staleReport = ReadinessCheck.evaluate(restoredJob, jobDir, restoredV0, captionFinding, null, historicalReport, verification);
        assertTrue(staleReport.autoFixes().isEmpty());
        assertTrue(staleReport.handledAutomatically().stream().noneMatch(text -> text.contains("Removed repeated numbers")));
        assertTrue(staleReport.actions().stream().anyMatch(item -> "captions".equals(item.code())));

        String shortCaptionSource = "\\documentclass{article}\n\\begin{document}\n\\begin{figure}\n"
                + "\\caption[Short]{Figure 1: Overview}\n\\end{figure}\n\\end{document}\n";
        var shortCaptionReport = ReadinessCheck.evaluate(restoredJob, jobDir, shortCaptionSource,
                captionFinding, null, null, verification);
        assertEquals("ready-with-notes", shortCaptionReport.status());
        assertTrue(shortCaptionReport.actions().stream().anyMatch(item -> "captions".equals(item.code())));
        assertTrue(shortCaptionReport.handledAutomatically().stream().noneMatch(text -> text.contains("cleaned up automatically")));
    }

    @Test void verifyExistingPdfThroughPreviewEndpointWorksForReadyJobWithoutTectonic() throws Exception {
        Path dataDir = temporary.resolve("verify-existing-pdf");
        var props = new AppProperties(dataDir.toString(), "", "missing-tectonic-binary", 1, 2, 30, 30);
        var localStore = new JobStore(props);
        localStore.load();
        var localService = new ConversionService(localStore, new Tools(props), new ToolRunner(), new DocxValidator(),
                props, new DocumentInspector(), new FontSupport());
        try {
            Job job = new Job(UUID.randomUUID().toString(), "legacy.docx", "article", "COMPLETED", "READY",
                    "Ready", "PDF preview is ready.", Instant.now(), Instant.now(), 100, 0);
            localStore.save(job);
            Path jobDir = localStore.directory(job.id());
            Files.createDirectories(jobDir.resolve("output"));
            Files.createDirectories(jobDir.resolve("preview-0"));
            writeDocx(jobDir.resolve("input.docx"), "<w:p><w:r><w:t>Legacy verified content survives check.</w:t></w:r></w:p>");
            Files.writeString(jobDir.resolve("output/main.tex"),
                    "\\documentclass{article}\n\\begin{document}\nLegacy verified content survives check.\n\\end{document}\n");
            localStore.versions().initialize(job, jobDir, true);
            writePdfLines(jobDir.resolve("preview-0/main.pdf"), List.of("Legacy verified content survives check."));

            var before = localService.review(job.id());
            assertNotNull(before.readiness());
            assertFalse(before.readiness().pdfVerified());
            assertTrue(before.readiness().actions().stream().anyMatch(item -> "pdf-unverified-built".equals(item.code())));

            Job verifiedJob = localService.preview(job.id());
            assertEquals("READY", verifiedJob.previewStatus());

            var after = localService.review(job.id());
            assertNotNull(after.readiness());
            assertEquals("ready", after.readiness().status());
            assertTrue(after.readiness().pdfVerified());
            assertTrue(after.readiness().actions().isEmpty());
        } finally {
            localService.stop();
        }
    }

    @Test void autoPrepareKeepsConvertedSourceWhenAutoFixHistoryFails() throws Exception {
        assumeTrue(tools.available(tools.pandoc()), "Install Pandoc to test preparation failure recovery");
        byte[] corpus;
        try (var input = getClass().getResourceAsStream("/corpus/research-complex.docx")) {
            assertNotNull(input);
            corpus = input.readAllBytes();
        }
        for (var failStage : SourceVersionStore.Stage.values()) {
            Path isolatedData = temporary.resolve("prep-fail-" + failStage.name().toLowerCase());
            var props = new AppProperties(isolatedData.toString(), tools.pandoc(), "missing-tectonic-binary", 1, 2, 30, 30);
            var failingVersions = new SourceVersionStore() {
                @Override void checkpoint(Stage stage) throws IOException {
                    if (stage == failStage) throw new IOException("Simulated disk full at " + stage);
                }
            };
            var localStore = new JobStore(props, failingVersions);
            localStore.load();
            var localService = new ConversionService(localStore, new Tools(props), new ToolRunner(), new DocxValidator(),
                    props, new DocumentInspector(), new FontSupport());
            try {
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
                assertEquals("COMPLETED", job.status(), failStage + ": " + job.message());
                assertTrue(job.message().contains("automatic preparation could not finish"), job.message());

                String source = localService.source(job.id());
                assertFalse(source.isBlank());
                if (failStage == SourceVersionStore.Stage.METADATA_COMMITTED) {
                    assertEquals(1, job.revision());
                } else {
                    assertEquals(0, job.revision());
                    assertTrue(source.contains("\\DocCaptionNumber{Figure}{1}{}"));
                }

                var review = localService.review(job.id());
                assertNotNull(review.readiness());
                assertTrue(review.readiness().actions().stream().anyMatch(item ->
                        item.guidance().contains("Automatic preparation could not finish")));
                if (failStage == SourceVersionStore.Stage.METADATA_COMMITTED) {
                    assertFalse(review.readiness().autoFixes().isEmpty());
                } else {
                    assertTrue(review.readiness().autoFixes().isEmpty());
                }

                var archiveBytes = new ByteArrayOutputStream();
                localService.writeArchive(job.id(), archiveBytes);
                Map<String, byte[]> entries = readZipEntries(archiveBytes.toByteArray());
                assertTrue(entries.containsKey("main.tex"));
                assertEquals(source, new String(entries.get("main.tex"), StandardCharsets.UTF_8));
            } finally {
                localService.stop();
            }
        }
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
        requirePdfTools();

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
        requirePdfTools();

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
        requirePdfTools();

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

    private void requirePdfTools() {
        assumeTrue(Boolean.getBoolean("doc2tex.test-pdf"), "Enable with -Ddoc2tex.test-pdf=true");
        assertTrue(tools.available(tools.pandoc()),
                "Pandoc is required when -Ddoc2tex.test-pdf=true; run scripts/setup-tools.ps1 or install Pandoc.");
        assertTrue(tools.available(tools.tectonic()),
                "Tectonic is required when -Ddoc2tex.test-pdf=true; run scripts/setup-tools.ps1 or install Tectonic.");
    }

    private static void writeDocx(Path target, String bodyXml) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<w:document xmlns:w=\"" + W + "\"><w:body>" + bodyXml + "</w:body></w:document>";
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void writeBmpDocx(Path target) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<w:document xmlns:w=\"" + W + "\"><w:body><w:p><w:r><w:t>Diagram caption</w:t></w:r></w:p></w:body></w:document>";
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/diagram.bmp"));
            zip.write(new byte[]{'B', 'M', 0, 0});
            zip.closeEntry();
        }
    }

    private static void writePdfLines(Path target, List<String> lines) throws IOException {
        try (var doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (!lines.isEmpty()) {
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, 700);
                    for (int i = 0; i < lines.size(); i++) {
                        if (i > 0) stream.newLineAtOffset(0, -16);
                        stream.showText(lines.get(i));
                    }
                    stream.endText();
                }
            }
            doc.save(target.toFile());
        }
    }

    private static Map<String, byte[]> readZipEntries(byte[] archiveBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
