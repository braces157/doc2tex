package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-ecotools-${random.uuid}")
class EcosystemToolsTest {
    @LocalServerPort int port;
    @Autowired Tools tools;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    private static ByteArrayResource part(String name, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        };
    }

    @Test
    void listsGeneratesAndEscapesAllSixAcademicTemplates() {
        List<AcademicTemplateService.TemplateDescriptor> list = client().get()
                .uri("/api/templates")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        assertNotNull(list);
        assertEquals(6, list.size());

        for (var tpl : list) {
            var req = new AcademicTemplateService.TemplateRequest(
                    tpl.id(), "R&D 100% #1 $50_test", "Ada Lovelace", "Analytical Lab", "Abstract & scope.\n\nSecond paragraph.", "2026", tpl.defaultPaper(), tpl.defaultFontSize());
            AcademicTemplateService.GeneratedTemplate generated = client().post()
                    .uri("/api/templates/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(AcademicTemplateService.GeneratedTemplate.class);
            assertNotNull(generated);
            assertTrue(generated.source().contains("\\documentclass"), tpl.id());
            assertTrue(generated.source().contains("R\\&D 100\\% \\#1 \\$50\\_test"), tpl.id());
        }
    }

    @Test
    void compilesTemplateToPdfWhenTectonicReady() throws Exception {
        assumeTrue(tools.available(tools.tectonic()));
        var req = new AcademicTemplateService.TemplateRequest(
                "ieee-twocol", "Local Publishing Study", "Jane Doe", "CS Dept", "Short abstract.", "2026", "letter", 10);
        byte[] pdfBytes = client().post()
                .uri("/api/templates/compile")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(byte[].class);
        assertNotNull(pdfBytes);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertTrue(doc.getNumberOfPages() >= 1);
        }
    }

    @Test
    void extractsDoiArxivIsbnAndNumberedRefsIntoValidBibtex() {
        String raw = """
                References
                See https://doi.org/10.1145/3544548.3580999 for the ACM study (2023).
                Balanced suffix (10.1234/example(2026)).
                Legacy SICI DOI 10.1002/(SICI)1521-3951(199911)216:1<135::AID-PSSB135>3.0.CO;2-#.
                Preprint available at arXiv:2401.12345v2 from 2024.
                Foundational textbook ISBN 978-0-201-13447-6 published 1984.
                [1] Knuth, D. (1984). The TeXbook with trailing slash \\
                """;
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("text", raw);
        ReferenceExtractorService.ExtractionReport report = client().post()
                .uri("/api/references/extract")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(ReferenceExtractorService.ExtractionReport.class);

        assertNotNull(report);
        assertEquals(6, report.totalFound());
        assertTrue(report.bibtex().contains("10.1145/3544548.3580999"));
        assertTrue(report.bibtex().contains("doi = {10.1234/example(2026)}"), report.bibtex());
        assertTrue(report.bibtex().contains("doi = {10.1002/(SICI)1521-3951(199911)216:1<135::AID-PSSB135>3.0.CO;2-#}"), report.bibtex());
        assertTrue(report.bibtex().contains("2401.12345v2"));
        var parsed = BibtexLibrary.parse(report.bibtex().getBytes(StandardCharsets.UTF_8));
        assertEquals(6, parsed.entries().size());
    }

    @Test
    void batchConvertsValidFilesAndIsolatesCorruptSiblings() throws Exception {
        assumeTrue(tools.available(tools.pandoc()));
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("files", part("one.md", "# Doc One\n\nBody one.".getBytes(StandardCharsets.UTF_8)));
        parts.add("files", part("two.md", "# Doc Two\n\nBody two.".getBytes(StandardCharsets.UTF_8)));
        parts.add("files", part("bad.exe", new byte[]{1, 2, 3}));
        parts.add("targetFormat", "html");
        parts.add("toc", "false");

        ResponseEntity<byte[]> response = client().post().uri("/api/batch/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(byte[].class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String summary = response.getHeaders().getFirst("X-Doc2tex-Batch-Summary");
        assertNotNull(summary);
        assertTrue(summary.contains("\"total\":3"), summary);
        assertTrue(summary.contains("\"succeeded\":2"), summary);
        assertTrue(summary.contains("\"failed\":1"), summary);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertTrue(entries.containsKey("outputs/one.html"));
        assertTrue(entries.containsKey("outputs/two.html"));
        assertTrue(entries.containsKey("batch-report.json"));
        String htmlOne = new String(entries.get("outputs/one.html"), StandardCharsets.UTF_8);
        String htmlTwo = new String(entries.get("outputs/two.html"), StandardCharsets.UTF_8);
        assertTrue(htmlOne.contains("Doc One") && htmlOne.contains("Body one."), htmlOne);
        assertTrue(htmlTwo.contains("Doc Two") && htmlTwo.contains("Body two."), htmlTwo);
    }
}
