package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-multiformat-${random.uuid}")
class MultiFormatTest {
    @LocalServerPort int port;
    @Autowired Tools tools;
    @Autowired ToolRunner runner;
    @Autowired DocxValidator docxValidator;
    @Autowired AppProperties properties;
    @Autowired JobStore store;
    @Autowired AiLayoutSettings aiSettings;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    private static ByteArrayResource part(String filename, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
    }

    @Test
    void catalogListsInputsAndOutputs() {
        MultiFormatService.FormatCatalog catalog = client().get()
                .uri("/api/convert/formats")
                .retrieve()
                .body(MultiFormatService.FormatCatalog.class);
        assertNotNull(catalog);
        assertTrue(catalog.inputs().stream().anyMatch(i -> i.id().equals("markdown")));
        assertTrue(catalog.inputs().stream().anyMatch(i -> i.id().equals("latex")));
        assertTrue(catalog.outputs().stream().anyMatch(o -> o.id().equals("docx")));
        assertTrue(catalog.outputs().stream().anyMatch(o -> o.id().equals("typst")));
        assertTrue(catalog.outputs().stream().anyMatch(o -> o.id().equals("pptx")));
    }

    @Test
    void convertsMarkdownAndLatexBidirectionallyAcrossFormats(@TempDir Path tempDir) throws Exception {
        assumeTrue(tools.available(tools.pandoc()));

        String md = "# Ecosystem Test\n\nHello **world** with $E = mc^2$.\n\n- Item A\n- Item B\n";
        for (String fmt : new String[]{"docx", "html", "typst", "epub", "pptx"}) {
            var parts = new LinkedMultiValueMap<String, Object>();
            parts.add("file", part("notes.md", md.getBytes(StandardCharsets.UTF_8)));
            parts.add("targetFormat", fmt);
            parts.add("toc", "true");
            ResponseEntity<byte[]> res = client().post().uri("/api/convert")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .toEntity(byte[].class);
            assertEquals(HttpStatus.OK, res.getStatusCode(), fmt);
            assertNotNull(res.getBody());
            assertTrue(res.getBody().length > 50, fmt);
            if (fmt.equals("docx")) {
                Path outDocx = tempDir.resolve("roundtrip.docx");
                Files.write(outDocx, res.getBody());
                docxValidator.validate(outDocx);
                try (ZipFile zip = new ZipFile(outDocx.toFile())) {
                    String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(xml.contains("Ecosystem Test"), xml);
                    assertTrue(xml.contains("Item A"), xml);
                }
            } else if (fmt.equals("html")) {
                String html = new String(res.getBody(), StandardCharsets.UTF_8);
                assertTrue(html.contains("<html"), html);
                assertTrue(html.contains("Ecosystem Test"), html);
                assertTrue(html.contains("Item B"), html);
            } else if (fmt.equals("typst")) {
                String typ = new String(res.getBody(), StandardCharsets.UTF_8);
                assertTrue(typ.contains("Ecosystem Test"), typ);
                assertTrue(typ.contains("Item A"), typ);
            } else if (fmt.equals("epub") || fmt.equals("pptx")) {
                StringBuilder allXml = new StringBuilder();
                try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(res.getBody()))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".xhtml")) {
                            allXml.append(new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
                assertTrue(allXml.toString().contains("Ecosystem Test"), fmt);
            }
        }

        // Long upload filename (>140 chars) must preserve trailing extension detection.
        String longMdName = "a".repeat(160) + ".md";
        var longNameParts = new LinkedMultiValueMap<String, Object>();
        longNameParts.add("file", part(longMdName, "# Long Name Title\n\nBody text.\n".getBytes(StandardCharsets.UTF_8)));
        longNameParts.add("targetFormat", "html");
        String longHtml = client().post().uri("/api/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(longNameParts)
                .retrieve()
                .body(String.class);
        assertNotNull(longHtml);
        assertTrue(longHtml.contains("Long Name Title"), longHtml);

        String tex = "\\documentclass{article}\n\\begin{document}\n\\section{Summary}\nLaTeX to Word test.\n\\end{document}\n";
        var texParts = new LinkedMultiValueMap<String, Object>();
        texParts.add("file", part("paper.tex", tex.getBytes(StandardCharsets.UTF_8)));
        texParts.add("targetFormat", "docx");
        byte[] docxFromTex = client().post().uri("/api/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(texParts)
                .retrieve()
                .body(byte[].class);
        assertNotNull(docxFromTex);
        Path outFromTex = tempDir.resolve("from-tex.docx");
        Files.write(outFromTex, docxFromTex);
        docxValidator.validate(outFromTex);
        try (ZipFile zip = new ZipFile(outFromTex.toFile())) {
            String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xml.contains("LaTeX to Word test."), xml);
        }
    }

    @Test
    void exportsCompletedWorkspaceJobToDocxAndMarkdown(@TempDir Path tempDir) throws Exception {
        assumeTrue(tools.available(tools.pandoc()));
        var upload = new LinkedMultiValueMap<String, Object>();
        upload.add("file", part("sample.docx", SampleDocument.create()));
        upload.add("template", "article");
        Job job = client().post().uri("/api/jobs")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(upload)
                .retrieve()
                .body(Job.class);
        assertNotNull(job);

        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = client().get().uri("/api/jobs/" + job.id()).retrieve().body(Job.class);
            if (job.status().equals("COMPLETED") || job.status().equals("FAILED")) break;
            Thread.sleep(100);
        }
        assertEquals("COMPLETED", job.status());

        ResponseEntity<byte[]> exportedDocxRes = client().get().uri("/api/jobs/" + job.id() + "/export/docx")
                .retrieve()
                .toEntity(byte[].class);
        byte[] exportedDocx = exportedDocxRes.getBody();
        assertNotNull(exportedDocx);
        Path outDocx = tempDir.resolve("job-export.docx");
        Files.write(outDocx, exportedDocx);
        docxValidator.validate(outDocx);
        try (var zip = new java.util.zip.ZipFile(outDocx.toFile())) {
            boolean hasMedia = zip.stream().anyMatch(e -> e.getName().startsWith("word/media/"));
            assertTrue(hasMedia, "Workspace DOCX export must preserve embedded job media under --sandbox");
        }

        byte[] exportedPptx = client().get().uri("/api/jobs/" + job.id() + "/export/pptx")
                .retrieve()
                .body(byte[].class);
        assertNotNull(exportedPptx);
        assertTrue(exportedPptx.length > 500);

        ResponseEntity<String> exportedMdRes = client().get().uri("/api/jobs/" + job.id() + "/export/markdown")
                .retrieve()
                .toEntity(String.class);
        String exportedMd = exportedMdRes.getBody();
        assertNotNull(exportedMd);
        assertTrue(exportedMd.contains("Sample") || exportedMd.length() > 40, exportedMd);
        String mdWarnings = exportedMdRes.getHeaders().getFirst("X-Doc2tex-Warnings");
        assertNotNull(mdWarnings, "Single-file Markdown export with job media must warn about unbundled images");
        assertTrue(mdWarnings.contains("media/"), mdWarnings);

        String completedJobId = job.id();
        var badExportFmt = assertThrows(HttpClientErrorException.class, () ->
                client().get().uri("/api/jobs/" + completedJobId + "/export/unknownfmt")
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badExportFmt.getStatusCode());
    }

    @Test
    void stagesLegacyDocOutputDirectlyAndMapsRendererFailures(@TempDir Path tempDir) throws Exception {
        assumeTrue(tools.available(tools.pandoc()));
        byte[] sampleDocx = SampleDocument.create();
        ToolRunner stubRendererRunner = new ToolRunner() {
            @Override
            Result run(List<String> command, Path workDir, Path log, int timeoutSeconds) throws java.io.IOException {
                Files.write(workDir.resolve("input.docx"), sampleDocx);
                return new Result(0, "");
            }
        };
        var service = new MultiFormatService(
                tools, runner, docxValidator, properties, store, aiSettings,
                new SourcePageRenderer(stubRendererRunner), () -> true);

        MockMultipartFile docUpload = new MockMultipartFile(
                "file", "legacy-paper.doc", "application/msword", "dummy-doc-bytes".getBytes(StandardCharsets.UTF_8));
        MultiFormatService.ConvertedFile directDocx = service.convert(docUpload, "docx", false);
        assertEquals("legacy-paper.docx", directDocx.filename());
        Path outDocx = tempDir.resolve("direct.docx");
        Files.write(outDocx, directDocx.bytes());
        docxValidator.validate(outDocx);

        MultiFormatService.ConvertedFile toMd = service.convert(docUpload, "markdown", false);
        assertEquals("legacy-paper.md", toMd.filename());
        String mdText = new String(toMd.bytes(), StandardCharsets.UTF_8);
        assertFalse(mdText.isBlank(), mdText);

        ToolRunner failingRunner = new ToolRunner() {
            @Override
            Result run(List<String> command, Path workDir, Path log, int timeoutSeconds) {
                return new Result(1, "corrupt legacy doc");
            }
        };
        var failingService = new MultiFormatService(
                tools, runner, docxValidator, properties, store, aiSettings,
                new SourcePageRenderer(failingRunner), () -> true);
        ApiException err = assertThrows(ApiException.class, () -> failingService.convert(docUpload, "markdown", false));
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, err.status);

        var unavailableService = new MultiFormatService(
                tools, runner, docxValidator, properties, store, aiSettings,
                new SourcePageRenderer(stubRendererRunner), () -> false);
        ApiException unavail = assertThrows(ApiException.class, () -> unavailableService.convert(docUpload, "markdown", false));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavail.status);
    }

    @Test
    void rejectsUnsupportedExtensionsAndBadTargetFormats() {
        var badExt = new LinkedMultiValueMap<String, Object>();
        badExt.add("file", part("binary.exe", new byte[]{1, 2, 3}));
        badExt.add("targetFormat", "markdown");
        var errExt = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/convert")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(badExt)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, errExt.getStatusCode());

        var badTarget = new LinkedMultiValueMap<String, Object>();
        badTarget.add("file", part("notes.md", "# Hi".getBytes(StandardCharsets.UTF_8)));
        badTarget.add("targetFormat", "unknownfmt");
        var errTarget = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/convert")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(badTarget)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, errTarget.getStatusCode());
    }

    @Test
    @Tag("ai")
    void convertsLegacyDocToDocxAndMarkdownWhenRendererIsReady(@TempDir Path tempDir) throws Exception {
        MultiFormatService.FormatCatalog catalog = client().get()
                .uri("/api/convert/formats")
                .retrieve()
                .body(MultiFormatService.FormatCatalog.class);
        assertNotNull(catalog);
        assumeTrue(catalog.pandocReady() && catalog.legacyDocReady());

        byte[] rtfDoc = "{\\rtf1\\ansi\\deff0 Legacy Word Conversion Check\\par Second line.}".getBytes(StandardCharsets.UTF_8);

        var toMd = new LinkedMultiValueMap<String, Object>();
        toMd.add("file", part("legacy.doc", rtfDoc));
        toMd.add("targetFormat", "markdown");
        toMd.add("toc", "false");
        ResponseEntity<byte[]> mdRes = client().post().uri("/api/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(toMd)
                .retrieve()
                .toEntity(byte[].class);
        assertEquals(HttpStatus.OK, mdRes.getStatusCode());
        assertNotNull(mdRes.getBody());
        String mdText = new String(mdRes.getBody(), StandardCharsets.UTF_8);
        assertTrue(mdText.contains("Legacy Word Conversion Check"), mdText);

        var toDocx = new LinkedMultiValueMap<String, Object>();
        toDocx.add("file", part("legacy.doc", rtfDoc));
        toDocx.add("targetFormat", "docx");
        toDocx.add("toc", "false");
        ResponseEntity<byte[]> docxRes = client().post().uri("/api/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(toDocx)
                .retrieve()
                .toEntity(byte[].class);
        assertEquals(HttpStatus.OK, docxRes.getStatusCode());
        assertNotNull(docxRes.getBody());
        Path outDocx = tempDir.resolve("legacy-converted.docx");
        Files.write(outDocx, docxRes.getBody());
        docxValidator.validate(outDocx);
    }
}
