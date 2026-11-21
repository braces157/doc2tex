package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-multiformat-${random.uuid}")
class MultiFormatTest {
    @LocalServerPort int port;
    @Autowired Tools tools;
    @Autowired DocxValidator docxValidator;

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
            } else if (fmt.equals("html")) {
                String html = new String(res.getBody(), StandardCharsets.UTF_8);
                assertTrue(html.contains("<html"), html);
                assertTrue(html.contains("Ecosystem Test"), html);
            }
        }

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

        String exportedMd = client().get().uri("/api/jobs/" + job.id() + "/export/markdown")
                .retrieve()
                .body(String.class);
        assertNotNull(exportedMd);
        assertFalse(exportedMd.isBlank());
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
