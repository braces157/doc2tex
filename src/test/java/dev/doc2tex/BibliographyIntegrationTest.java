package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-bibliography-${random.uuid}")
class BibliographyIntegrationTest {
    @LocalServerPort int port;
    @Autowired ConversionService service;
    @Autowired JobStore store;
    @Autowired Tools tools;
    @TempDir Path temporary;
    private RestClient client() { return RestClient.builder().baseUrl("http://127.0.0.1:" + port).requestFactory(new JdkClientHttpRequestFactory()).build(); }
    private byte[] fixture() throws Exception { try (var input = getClass().getResourceAsStream("/bibliography/two-entries.bib")) { assertNotNull(input); return input.readAllBytes(); } }
    private Job job(String template) throws Exception {
        assumeTrue(tools.available(tools.pandoc()));
        var file = new org.springframework.mock.web.MockMultipartFile("file", "paper.docx", "application/octet-stream", SampleDocument.create());
        return await(service.submit(file, template), false);
    }
    private Job await(Job job, boolean pdf) throws Exception {
        long deadline = System.nanoTime() + (pdf ? 300L : 30L) * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = store.get(job.id()); String state = pdf ? job.previewStatus() : job.status();
            if (List.of("COMPLETED", "READY", "FAILED").contains(state)) { assertNotEquals("FAILED", state, job.toString()); return job; } Thread.sleep(50);
        }
        fail("Synthetic bibliography job timed out"); return job;
    }
    private LinkedMultiValueMap<String, Object> upload(byte[] bytes, String filename, Integer revision) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return filename; } });
        if (revision != null) body.add("revision", revision); return body;
    }
    private ConversionService.RestoredDocument attach(Job job, byte[] bytes, String filename) {
        return client().post().uri("/api/jobs/" + job.id() + "/bibliography").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(upload(bytes, filename, job.revision())).retrieve().body(ConversionService.RestoredDocument.class);
    }
    private ConversionService.BibliographyReport report(Job job) { return client().get().uri("/api/jobs/" + job.id() + "/bibliography").retrieve().body(ConversionService.BibliographyReport.class); }

    @Test void importsReplaceRestoreAndDownloadsBindExactBytesToSavedSource() throws Exception {
        Job job = job("article"); String baseline = service.source(job.id()); byte[] original = fixture();
        var attached = attach(job, original, "my-library.bib"); job = attached.job(); assertEquals(baseline, attached.source()); assertEquals(1, job.revision());
        assertEquals(2, report(job).entries().size()); assertEquals("my-library.bib", report(job).filename());
        assertArrayEquals(original, client().get().uri("/api/jobs/" + job.id() + "/bibliography/file").retrieve().body(byte[].class));
        job = service.saveSource(job.id(), baseline + "\n% Citation evidence \\cite{doe2024}", 1);
        assertEquals(0, report(job).unknownKeys().size());
        byte[] replacement = new String(original, StandardCharsets.UTF_8).replace("doe2024", "replacement2024").getBytes(StandardCharsets.UTF_8);
        job = attach(job, replacement, "replacement.bib").job(); assertEquals(3, job.revision());
        assertArrayEquals(replacement, client().get().uri("/api/jobs/" + job.id() + "/bibliography/file").retrieve().body(byte[].class));
        var restored = client().post().uri("/api/jobs/" + job.id() + "/versions/2/restore").body(Map.of("revision", 3)).retrieve().body(ConversionService.RestoredDocument.class);
        job = restored.job(); assertEquals(4, job.revision()); assertTrue(restored.source().contains("Citation evidence"));
        assertArrayEquals(original, client().get().uri("/api/jobs/" + job.id() + "/bibliography/file").retrieve().body(byte[].class));
        assertArrayEquals(replacement, client().get().uri("/api/jobs/" + job.id() + "/versions/3/bibliography").retrieve().body(byte[].class));
        String id = job.id();
        var absent = assertThrows(HttpClientErrorException.class, () -> client().get().uri("/api/jobs/" + id + "/versions/0/bibliography").retrieve().body(byte[].class));
        assertEquals(HttpStatus.NOT_FOUND, absent.getStatusCode());
        job = service.restore(job.id(), 0, job.revision()); assertEquals(baseline, service.source(job.id())); assertEquals("", report(job).filename());
        assertFalse(Files.exists(store.directory(id).resolve("output/bibliography/references.bib")));
        assertArrayEquals(original, client().get().uri("/api/jobs/" + id + "/versions/1/bibliography").retrieve().body(byte[].class));
        assertEquals(0, service.versions(id).items().getFirst().assetBytes());
    }

    @Test void invalidImportsAndStaleSetupKeepCurrentSourceAssetsAndRevision() throws Exception {
        Job job = job("article"); String id = job.id(), path = "/api/jobs/" + id + "/bibliography"; String source = service.source(id);
        job = attach(job, fixture(), "references.bib").job();
        byte[] committed = fixture(); int revision = job.revision();
        for (byte[] bytes : List.of("@book{same}\n@book{SAME}".getBytes(StandardCharsets.UTF_8), "@book{broken,title={unclosed}".getBytes(StandardCharsets.UTF_8), new byte[BibliographyAssets.MAX_BIB + 1], new byte[]{(byte) 0xff})) {
            var error = assertThrows(HttpClientErrorException.class, () -> client().post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA).body(upload(bytes, "broken.bib", revision)).retrieve().body(String.class));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        var missingRevision = assertThrows(HttpClientErrorException.class, () -> client().post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA).body(upload(committed, "valid.bib", null)).retrieve().body(String.class));
        assertEquals(HttpStatus.BAD_REQUEST, missingRevision.getStatusCode());
        var stale = assertThrows(HttpClientErrorException.class, () -> client().post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA).body(upload(committed, "valid.bib", 0)).retrieve().body(String.class));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        var plan = client().get().uri(path + "/setup").retrieve().body(LatexBibliography.Plan.class); assertEquals(2, plan.changes().size());
        job = service.saveSource(id, source.replace("\\end{document}", "Unknown key: \\cite{missing_key}.\n\\end{document}"), revision);
        assertEquals(List.of("missing_key"), report(job).unknownKeys());
        assertTrue(service.review(id).findings().stream().anyMatch(finding -> finding.code().equals("bibliography") && finding.detail().contains("missing_key")));
        var staleSetup = assertThrows(HttpClientErrorException.class, () -> client().post().uri(path + "/setup").body(Map.of("revision", plan.revision(), "sourceHash", plan.sourceHash())).retrieve().body(String.class));
        assertEquals(HttpStatus.CONFLICT, staleSetup.getStatusCode());
        Job queued = job.withPreview("QUEUED", "Synthetic busy state"); store.save(queued);
        var busy = assertThrows(HttpClientErrorException.class, () -> client().post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA).body(upload(committed, "valid.bib", queued.revision())).retrieve().body(String.class));
        assertEquals(HttpStatus.CONFLICT, busy.getStatusCode());
        assertArrayEquals(committed, client().get().uri(path + "/file").retrieve().body(byte[].class));
        assertEquals(2, store.get(id).revision()); assertTrue(service.source(id).contains("missing_key"));
        store.save(queued.withPreview("NOT_REQUESTED", ""));
    }

    @ParameterizedTest @ValueSource(strings = {"article", "report"})
    void twoKeysProduceRealUnicodeBibliographyAndExportRecompilesWithoutExternalProjectFiles(String template) throws Exception {
        assumeTrue(Boolean.getBoolean("doc2tex.test-pdf")); assumeTrue(tools.available(tools.tectonic()));
        Job job = job(template); byte[] original = fixture(); job = attach(job, original, "unicode.bib").job();
        String source = service.source(job.id()).replace("\\end{document}", "\nCITATION\\_CHECK: \\cite{doe2024,ivanov2025}.\n\\end{document}");
        job = service.saveSource(job.id(), source, job.revision());
        var plan = service.bibliographySetup(job.id()); assertEquals(2, plan.changes().size());
        var applied = client().post().uri("/api/jobs/" + job.id() + "/bibliography/setup").body(Map.of("revision", job.revision(), "sourceHash", plan.sourceHash())).retrieve().body(ConversionService.RestoredDocument.class);
        job = await(service.preview(applied.job().id()), true);
        String log = Files.readString(store.directory(job.id()).resolve("preview.log")); assertTrue(log.contains("Running BibTeX"), log); assertFalse(log.toLowerCase(java.util.Locale.ROOT).contains("missing character"), log);
        String text;
        try (var pdf = Loader.loadPDF(service.pdf(job.id()).toFile())) { text = new PDFTextStripper().getText(pdf); }
        String compact = text.replaceAll("\\s+", ""); assertTrue(compact.contains("CITATION_CHECK:[1,2]."), text);
        assertTrue(text.contains("Synthetic Unicode Résumé"), text); assertTrue(text.contains("Synthetic Проверка"), text);
        byte[] archive = client().get().uri("/api/jobs/" + job.id() + "/download").retrieve().body(byte[].class);
        Path exported = temporary.resolve("exported"); Files.createDirectories(exported); boolean bib = false, font = false, license = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path file = exported.resolve(entry.getName()).normalize(); assertTrue(file.startsWith(exported)); Files.createDirectories(file.getParent()); byte[] bytes = zip.readAllBytes(); Files.write(file, bytes);
                if (entry.getName().equals("bibliography/references.bib")) { bib = true; assertArrayEquals(original, bytes); }
                if (entry.getName().equals("bibliography/NotoSerif-Regular.ttf")) font = true;
                if (entry.getName().equals("bibliography/OFL-Noto.txt")) license = true;
                assertFalse(entry.getName().contains(".versions")); assertFalse(entry.getName().contains(".doc2tex-owner"));
            }
        }
        assertTrue(bib && font && license); assertEquals(applied.source(), Files.readString(exported.resolve("main.tex")));
        assertTrue(Files.readString(exported.resolve("README.txt")).contains("bibtex main"));
        var result = new ToolRunner().run(List.of(tools.tectonic(), "--untrusted", "--color", "never", "main.tex"), exported, temporary.resolve("export-compile.log"), 300);
        assertEquals(0, result.exitCode(), result.log());
        try (var pdf = Loader.loadPDF(exported.resolve("main.pdf").toFile())) { assertEquals(text, new PDFTextStripper().getText(pdf)); }
        Path evidence = Path.of(System.getProperty("doc2tex.build-directory", "target"), "fidelity-evidence", "bibliography", template); Files.createDirectories(evidence);
        Files.copy(exported.resolve("main.pdf"), evidence.resolve("main.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(evidence.resolve("pdf-text.txt"), text); Files.writeString(evidence.resolve("main.tex"), applied.source());
        Files.write(evidence.resolve("references.bib"), original); Files.writeString(evidence.resolve("compile.log"), log);
    }
}
