package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
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

    @Test void convertsRealDocxWithMathTablesImagesAndSupportsEditing() throws Exception {
        assumeTrue(tools.available(tools.pandoc()), "Install Pandoc to run the real conversion integration test");
        Job job = await(upload("research.docx", SampleDocument.create(), "article"));
        assertEquals("COMPLETED", job.status(), job.message());
        String base = "/api/jobs/" + job.id();
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

        byte[] archive = client().get().uri(base + "/download").retrieve().body(byte[].class);
        boolean hasSource = false, hasImage = false, hasReadme = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertFalse(entry.getName().contains(".."));
                if (entry.getName().equals("main.tex")) {
                    hasSource = true;
                    assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("thoughtful systems"));
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
