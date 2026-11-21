package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-compare-${random.uuid}")
class CompareAndCleanTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired DocxValidator docxValidator;

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

    private static byte[] pdfWithPages(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String line : lines) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(line);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildRichDocx() throws Exception {
        BufferedImage largeImg = new BufferedImage(2400, 1600, BufferedImage.TYPE_INT_RGB);
        var g = largeImg.createGraphics();
        g.setColor(new Color(0x1f, 0x4d, 0x3a));
        g.fillRect(0, 0, 2400, 1600);
        g.setColor(Color.WHITE);
        for (int i = 0; i < 2400; i += 40) g.drawLine(i, 0, 2400 - i, 1600);
        g.dispose();
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(largeImg, "png", pngOut);
        byte[] pngBytes = pngOut.toByteArray();
        byte[] paddedPng = pngBytes;
        if (pngBytes.length < 90 * 1024) {
            BufferedImage noisy = new BufferedImage(2200, 1400, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 1400; y++) {
                for (int x = 0; x < 2200; x++) {
                    noisy.setRGB(x, y, ((x * 13 + y * 7) & 0xFFFFFF));
                }
            }
            pngOut.reset();
            ImageIO.write(noisy, "png", pngOut);
            paddedPng = pngOut.toByteArray();
        }

        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipOut)) {
            writeEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Default Extension="png" ContentType="image/png"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                      <Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/>
                      <Override PartName="/word/glossary/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.glossary+xml"/>
                      <Override PartName="/word/glossary/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/>
                      <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                      <Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>
                    </Types>
                    """);
            writeEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                      <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" Target="docProps/custom.xml"/>
                    </Relationships>
                    """);
            writeEntry(zip, "docProps/core.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                       xmlns:dc="http://purl.org/dc/elements/1.1/"
                                       xmlns:dcterms="http://purl.org/dc/terms/"
                                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <dc:title>Secret Draft</dc:title>
                      <dc:creator>Grace Hopper</dc:creator>
                      <cp:lastModifiedBy>Grace Hopper</cp:lastModifiedBy>
                      <dcterms:created xsi:type="dcterms:W3CDTF">2026-11-21T00:00:00Z</dcterms:created>
                      <dcterms:modified xsi:type="dcterms:W3CDTF">2026-11-21T01:00:00Z</dcterms:modified>
                    </cp:coreProperties>
                    """);
            writeEntry(zip, "docProps/custom.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"/>
                    """);
            writeEntry(zip, "word/_rels/document.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.org/blog/comments" TargetMode="External"/>
                    </Relationships>
                    """);
            writeEntry(zip, "word/comments.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:comment w:id="1" w:author="Reviewer"><w:p><w:r><w:t>Needs work</w:t></w:r></w:p></w:comment>
                    </w:comments>
                    """);
            writeEntry(zip, "word/glossary/_rels/document.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/>
                    </Relationships>
                    """);
            writeEntry(zip, "word/glossary/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:glossaryDocument xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:docParts><w:docPart><w:docPartBody><w:p><w:r><w:commentReference w:id="2"/></w:r></w:p></w:docPartBody></w:docPart></w:docParts>
                    </w:glossaryDocument>
                    """);
            writeEntry(zip, "word/glossary/comments.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:comment w:id="2" w:author="GlossaryReviewer"><w:p><w:r><w:t>Hidden glossary note</w:t></w:r></w:p></w:comment>
                    </w:comments>
                    """);
            writeEntry(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p>
                          <w:commentRangeStart w:id="1"/>
                          <w:r><w:t>Hello clean document.</w:t></w:r>
                          <w:commentRangeEnd w:id="1"/>
                          <w:r><w:commentReference w:id="1"/></w:r>
                        </w:p>
                      </w:body>
                    </w:document>
                    """);
            zip.putNextEntry(new ZipEntry("word/media/large.png"));
            zip.write(paddedPng);
            zip.closeEntry();
        }
        return zipOut.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.trim().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void comparesPdfTextAndRendersVisualPixelDiff() throws Exception {
        byte[] left = pdfWithPages(List.of("Shared page one line", "Original page two wording"));
        byte[] right = pdfWithPages(List.of("Shared page one line", "Updated page two revision"));

        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("left", part("v1.pdf", left));
        parts.add("right", part("v2.pdf", right));

        PdfDiffService.PdfDiffReport report = client().post().uri("/api/diff/pdf")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(PdfDiffService.PdfDiffReport.class);
        assertNotNull(report);
        assertEquals(2, report.comparedPages());
        assertEquals(1, report.matchingPages());
        assertEquals(1, report.changedPages());
        assertEquals(1, report.addedLines());
        assertEquals(1, report.removedLines());

        PdfDiffService.VisualPageDiff page1 = client().post().uri("/api/diff/pdf/page?page=1")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(PdfDiffService.VisualPageDiff.class);
        assertNotNull(page1);
        assertEquals(0.0, page1.pixelDiffRatio());
        assertFalse(page1.diffPngBase64().isEmpty());

        PdfDiffService.VisualPageDiff page2 = client().post().uri("/api/diff/pdf/page?page=2")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(PdfDiffService.VisualPageDiff.class);
        assertNotNull(page2);
        assertTrue(page2.pixelDiffRatio() > 0.0);

        // Failure-path coverage: page=0, malformed PDF, malformed DOCX.
        var badPageErr = assertThrows(org.springframework.web.client.HttpClientErrorException.class, () ->
                client().post().uri("/api/diff/pdf/page?page=0")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(parts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badPageErr.getStatusCode());

        var badPdfParts = new LinkedMultiValueMap<String, Object>();
        badPdfParts.add("left", part("bad.pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8)));
        badPdfParts.add("right", part("v2.pdf", right));
        var badPdfErr = assertThrows(org.springframework.web.client.HttpClientErrorException.class, () ->
                client().post().uri("/api/diff/pdf")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(badPdfParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badPdfErr.getStatusCode());

        var badDocxParts = new LinkedMultiValueMap<String, Object>();
        badDocxParts.add("file", part("corrupt.docx", "not-a-zip".getBytes(StandardCharsets.UTF_8)));
        var badDocxErr = assertThrows(org.springframework.web.client.HttpClientErrorException.class, () ->
                client().post().uri("/api/docx/inspect")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(badDocxParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badDocxErr.getStatusCode());
    }

    @Test
    void inspectsAndCleansDocxPreservingOoxmlValidity(@TempDir Path tempDir) throws Exception {
        byte[] rich = buildRichDocx();
        var inspectParts = new LinkedMultiValueMap<String, Object>();
        inspectParts.add("file", part("rich.docx", rich));

        DocxCleanerService.DocxInspection inspection = client().post().uri("/api/docx/inspect")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(inspectParts)
                .retrieve()
                .body(DocxCleanerService.DocxInspection.class);
        assertNotNull(inspection);
        assertEquals(1, inspection.mediaCount());
        assertEquals(1, inspection.commentCount());
        assertEquals(1, inspection.externalLinkCount());
        assertEquals("Grace Hopper", inspection.metadata().get("creator"));

        var cleanParts = new LinkedMultiValueMap<String, Object>();
        cleanParts.add("file", part("rich.docx", rich));
        cleanParts.add("options", JSON.writeValueAsString(
                new DocxCleanerService.CleanOptions(true, true, true, 1200)));

        ResponseEntity<byte[]> cleanedRes = client().post().uri("/api/docx/clean")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(cleanParts)
                .retrieve()
                .toEntity(byte[].class);
        assertEquals(HttpStatus.OK, cleanedRes.getStatusCode());
        assertNotNull(cleanedRes.getBody());
        assertTrue(cleanedRes.getBody().length < rich.length);
        assertEquals("1", cleanedRes.getHeaders().getFirst("X-Doc2tex-Images-Optimized"));

        Path cleanedPath = tempDir.resolve("cleaned.docx");
        Files.write(cleanedPath, cleanedRes.getBody());
        docxValidator.validate(cleanedPath);

        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(cleanedPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
            String coreXml = new String(zip.getInputStream(zip.getEntry("docProps/core.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(coreXml.contains("Grace Hopper"));
            assertFalse(coreXml.contains("Secret Draft"));
            assertFalse(coreXml.contains("dcterms:created"), coreXml);
            assertFalse(coreXml.contains("dcterms:modified"), coreXml);
            String docXml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(docXml.contains("commentReference"));
            assertTrue(docXml.contains("Hello clean document."), docXml);
            String glossaryDocXml = new String(zip.getInputStream(zip.getEntry("word/glossary/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(glossaryDocXml.contains("commentReference"), glossaryDocXml);
            String contentTypesXml = new String(zip.getInputStream(zip.getEntry("[Content_Types].xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(contentTypesXml.contains("glossary/comments.xml"), contentTypesXml);
            String relsXml = new String(zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(relsXml.contains("https://example.org/blog/comments"), "External hyperlink containing 'comments' must be preserved");
            assertFalse(relsXml.contains("Target=\"comments.xml\""), relsXml);
        }
        assertFalse(names.contains("word/comments.xml"));
        assertFalse(names.contains("word/glossary/comments.xml"));
        assertFalse(names.contains("docProps/custom.xml"));
    }
}
