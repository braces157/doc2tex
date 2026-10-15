package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
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
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-tests-${random.uuid}")
class ConversionIntegrationTest {
    @LocalServerPort int port;
    @Autowired Tools tools;
    @Autowired JobStore store;
    @Autowired ConversionService service;
    private RestClient client() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory()).build();
    }

    private Job upload(String filename, byte[] bytes, String template) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        parts.add("template", template);
        var response = client().post().uri("/api/jobs").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts).retrieve().toEntity(Job.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        return response.getBody();
    }

    private Job await(Job job) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = client().get().uri("/api/jobs/" + job.id()).retrieve().body(Job.class);
            if (job.status().equals("COMPLETED") || job.status().equals("FAILED")) return job;
            Thread.sleep(100);
        }
        fail("Conversion timed out"); return job;
    }

    @Test void libraryRouteReturnsPagesAndRejectsInvalidParameters() throws Exception {
        Job older = new Job(java.util.UUID.randomUUID().toString(), "library-only-résumé.docx", "report",
                "COMPLETED", "NOT_REQUESTED", "", "", java.time.Instant.EPOCH, java.time.Instant.EPOCH, 20, 0);
        store.save(older);
        var result = client().get().uri(builder -> builder.path("/api/library")
                .queryParam("query", "library-only").queryParam("status", "COMPLETED")
                .queryParam("sort", "oldest").queryParam("page", 0).queryParam("size", 1).build())
                .retrieve().body(JobStore.LibraryPage.class);
        assertNotNull(result);
        assertEquals(1, result.total()); assertEquals(1, result.size()); assertEquals(List.of(older), result.items());
        for (String query : List.of("page=-1", "size=0", "size=101", "sort=sideways", "status=READY", "page=abc", "size=2147483648")) {
            var error = assertThrows(HttpClientErrorException.class,
                    () -> client().get().uri("/api/library?" + query).retrieve().toBodilessEntity());
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode(), query);
        }
    }

    @Test void metadataAndDeleteRoutesPreserveOriginalAndRejectBusyJobs() throws Exception {
        Job job = new Job(java.util.UUID.randomUUID().toString(), "lifecycle-original.docx", "article", "COMPLETED",
                "NOT_REQUESTED", "", "", java.time.Instant.now(), java.time.Instant.now(), 100, 3);
        store.save(job);
        String base = "/api/jobs/" + job.id();
        Job renamed = client().patch().uri(base).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("displayName", "Renamed paper ภาษาไทย")).retrieve().body(Job.class);
        assertNotNull(renamed); assertEquals(job.filename(), renamed.filename());
        assertEquals("Renamed paper ภาษาไทย", renamed.displayName()); assertEquals(3, renamed.revision());
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of(), Map.of("displayName", "bad\nname"), Map.of("displayName", "x".repeat(201)))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpClientErrorException.class,
                    () -> client().patch().uri(base).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity()).getStatusCode());
        }
        store.save(renamed.withPreview("QUEUED", ""));
        assertEquals(HttpStatus.CONFLICT, assertThrows(HttpClientErrorException.class,
                () -> client().delete().uri(base).retrieve().toBodilessEntity()).getStatusCode());
        store.save(renamed);
        assertEquals(HttpStatus.NO_CONTENT, client().delete().uri(base).retrieve().toBodilessEntity().getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpClientErrorException.class,
                () -> client().put().uri(base + "/source").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("source", "Cannot resurrect deleted data", "revision", 3)).retrieve().toBodilessEntity()).getStatusCode());
        assertFalse(Files.exists(store.directory(job.id())));
    }

    @Test void activeArchiveAndPdfStreamsExcludeDeletionUntilTheirLastByte() throws Exception {
        for (String format : List.of("archive", "pdf", "original")) {
            Job job = new Job(java.util.UUID.randomUUID().toString(), "stream-test.docx", "article", "COMPLETED",
                    "READY", "", "", java.time.Instant.now(), java.time.Instant.now(), 100, 0);
            store.save(job);
            var directory = store.directory(job.id());
            Files.createDirectories(directory.resolve("output"));
            Files.createDirectories(directory.resolve("preview-0"));
            Files.write(directory.resolve("input.docx"), SampleDocument.create());
            Files.writeString(directory.resolve("output/main.tex"), "Synthetic stream source");
            Files.writeString(directory.resolve("preview-0/main.pdf"), "Synthetic PDF bytes");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var bytes = new java.io.ByteArrayOutputStream();
            var blocked = new java.io.OutputStream() {
                @Override public void write(int value) throws java.io.IOException {
                    entered.countDown();
                    try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Stream gate timed out"); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new java.io.IOException(exception); }
                    bytes.write(value);
                }
            };
            var task = new java.util.concurrent.FutureTask<Void>(() -> {
                if (format.equals("pdf")) service.writePdf(job.id(), blocked);
                else if (format.equals("original")) service.writeOriginal(job.id(), blocked);
                else service.writeArchive(job.id(), blocked);
                return null;
            });
            Thread thread = Thread.ofPlatform().start(task);
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(HttpStatus.CONFLICT, assertThrows(HttpClientErrorException.class,
                        () -> client().delete().uri("/api/jobs/" + job.id()).retrieve().toBodilessEntity()).getStatusCode());
            } finally { release.countDown(); thread.join(10000); }
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(bytes.size() > 0);
            store.delete(job.id());
            assertFalse(Files.exists(directory));
        }
    }

    @Test void convertsRealDocxWithMathTablesImagesAndSupportsEditing() throws Exception {
        assumeTrue(tools.available(tools.pandoc()), "Install Pandoc to run the real conversion integration test");
        Job job = await(upload("research.docx", SampleDocument.create(), "article"));
        assertEquals("COMPLETED", job.status(), job.message());
        String base = "/api/jobs/" + job.id();
        assertArrayEquals(Files.readAllBytes(store.directory(job.id()).resolve("input.docx")),
                client().get().uri(base + "/original").retrieve().body(byte[].class));
        String source = client().get().uri(base + "/source").retrieve().body(String.class);
        assertNotNull(source);
        assertTrue(source.contains("\\section{Field notes on quiet systems}"), source);
        assertTrue(source.contains("\\textbf{Observation.}"));
        assertTrue(source.contains("\\%"));
        assertTrue(source.contains("c^{2}"), source);
        assertTrue(source.contains("\\begin{longtable}"), source);
        assertTrue(source.contains("\\includegraphics"), source);

        var unavailablePdf = assertThrows(HttpClientErrorException.class,
                () -> client().get().uri(base + "/preview").retrieve().toBodilessEntity());
        assertEquals(HttpStatus.CONFLICT, unavailablePdf.getStatusCode());

        String edited = source.replace("quiet systems", "thoughtful systems");
        Job saved = client().put().uri(base + "/source").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("source", edited, "revision", 0)).retrieve().body(Job.class);
        assertNotNull(saved); assertEquals(1, saved.revision());
        assertEquals("NOT_REQUESTED", saved.previewStatus());
        var conflict = assertThrows(HttpClientErrorException.class,
                () -> client().put().uri(base + "/source").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("source", source, "revision", 0)).retrieve().toBodilessEntity());
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        var history = client().get().uri(base + "/versions").retrieve().body(SourceVersionStore.History.class);
        assertNotNull(history); assertFalse(history.legacyHistoryUnavailable()); assertEquals(0, history.firstRevision());
        assertEquals("converted", history.items().getLast().origin());
        assertEquals(source, client().get().uri(base + "/versions/0/source").retrieve().body(String.class));
        Job second = client().put().uri(base + "/source").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("source", edited + "\n% Second saved experiment", "revision", 1)).retrieve().body(Job.class);
        assertNotNull(second);
        var restored = client().post().uri(base + "/versions/1/restore").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("revision", second.revision())).retrieve().body(ConversionService.RestoredDocument.class);
        assertNotNull(restored); assertEquals(3, restored.job().revision()); assertEquals(edited, restored.source());
        assertEquals("NOT_REQUESTED", restored.job().previewStatus());
        assertEquals(edited, client().get().uri(base + "/source").retrieve().body(String.class));
        assertEquals(HttpStatus.CONFLICT, assertThrows(HttpClientErrorException.class,
                () -> client().post().uri(base + "/versions/0/restore").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("revision", 1)).retrieve().toBodilessEntity()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpClientErrorException.class,
                () -> client().post().uri(base + "/versions/0/restore").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of()).retrieve().toBodilessEntity()).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpClientErrorException.class,
                () -> client().get().uri(base + "/versions/999/source").retrieve().toBodilessEntity()).getStatusCode());

        byte[] archive = client().get().uri(base + "/download").retrieve().body(byte[].class);
        boolean hasSource = false, hasImage = false, hasReadme = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertFalse(entry.getName().contains(".."));
                assertFalse(entry.getName().contains(".versions"));
                if (entry.getName().equals("main.tex")) {
                    hasSource = true;
                    assertEquals(edited, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
                hasImage |= entry.getName().startsWith("media/") && entry.getName().endsWith(".png");
                hasReadme |= entry.getName().equals("README.txt");
            }
        }
        assertTrue(hasSource && hasImage && hasReadme, "The ZIP must contain edited source, images, and instructions");
        assertTrue(Files.exists(store.directory(job.id()).resolve("job.properties")));
    }

    @Test void reportUsesReportDocumentClass() throws Exception {
        assumeTrue(tools.available(tools.pandoc()));
        Job job = await(upload("report.docx", SampleDocument.create(), "report"));
        assertEquals("COMPLETED", job.status(), job.message());
        String source = client().get().uri("/api/jobs/" + job.id() + "/source").retrieve().body(String.class);
        assertNotNull(source); assertTrue(source.contains("{report}"));
    }

    @Test void invalidInputsReturnUsefulClientErrors() throws Exception {
        var wrongExtension = assertThrows(HttpClientErrorException.class,
                () -> upload("notes.pdf", SampleDocument.create(), "article"));
        assertEquals(HttpStatus.BAD_REQUEST, wrongExtension.getStatusCode());
        var wrongTemplate = assertThrows(HttpClientErrorException.class,
                () -> upload("notes.docx", SampleDocument.create(), "unknown"));
        assertEquals(HttpStatus.BAD_REQUEST, wrongTemplate.getStatusCode());
        var absentJob = assertThrows(HttpClientErrorException.class,
                () -> client().get().uri("/api/jobs/not-a-job").retrieve().toBodilessEntity());
        assertEquals(HttpStatus.NOT_FOUND, absentJob.getStatusCode());
    }

    @Test void refusesRequestsFromRemoteWebsites() {
        var rejected = assertThrows(HttpClientErrorException.class,
                () -> client().get().uri("/api/system").header("Origin", "https://untrusted.example")
                        .retrieve().toBodilessEntity());
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode());
        assertEquals(HttpStatus.OK, client().get().uri("/api/system").header("Origin", "http://127.0.0.1:5173")
                .retrieve().toBodilessEntity().getStatusCode());
    }
}
