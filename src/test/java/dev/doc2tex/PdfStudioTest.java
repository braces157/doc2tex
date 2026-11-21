package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;

import dev.doc2tex.PdfStudioModels.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-pdf-studio-${random.uuid}")
class PdfStudioTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired PdfStudioService service;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    private static ByteArrayResource pdfPart(String filename, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
    }

    private static byte[] createSamplePdf(List<String> pageTexts, boolean withMetadata, boolean withXmp,
            boolean withOutline, boolean withAnnotation, boolean withImage, String userPassword) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pageTexts.size(); i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    String line = pageTexts.get(i);
                    if (line != null && !line.isEmpty()) {
                        content.beginText();
                        content.setFont(font, 12);
                        content.newLineAtOffset(72, 700);
                        content.showText(line);
                        content.endText();
                    }
                    if (withImage && i == 0) {
                        BufferedImage img = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
                        var g = img.createGraphics();
                        g.setColor(new Color(0x1f, 0x4d, 0x3a));
                        g.fillRect(0, 0, 12, 12);
                        g.dispose();
                        PDImageXObject ximg = LosslessFactory.createFromImage(doc, img);
                        content.drawImage(ximg, 72, 600, 48, 48);
                    }
                }
                if (withAnnotation && i == 0) {
                    PDAnnotationText note = new PDAnnotationText();
                    note.setContents("Review note");
                    note.setRectangle(new PDRectangle(100, 650, 24, 24));
                    page.getAnnotations().add(note);
                }
            }
            if (withMetadata) {
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle("Experimental Notes");
                info.setAuthor("Ada Lovelace");
                info.setSubject("Computing");
                info.setKeywords("math, engines");
                info.setCreator("doc2TeX Test Suite");
                doc.setDocumentInformation(info);
            }
            if (withXmp) {
                PDMetadata xmp = new PDMetadata(doc);
                xmp.importXMPMetadata("<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?><x:xmpmeta xmlns:x='adobe:ns:meta/'/>".getBytes(StandardCharsets.UTF_8));
                doc.getDocumentCatalog().setMetadata(xmp);
            }
            if (withOutline && doc.getNumberOfPages() >= 2) {
                PDDocumentOutline outline = new PDDocumentOutline();
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle("Second Section");
                PDPageXYZDestination dest = new PDPageXYZDestination();
                dest.setPage(doc.getPage(1));
                item.setDestination(dest);
                outline.addLast(item);
                doc.getDocumentCatalog().setDocumentOutline(outline);
            }
            if (userPassword != null) {
                doc.protect(new StandardProtectionPolicy("owner-pass", userPassword, new AccessPermission()));
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void inspectReportsMetadataPagesImagesAnnotationsAndOutline() throws Exception {
        byte[] pdf = createSamplePdf(List.of("First page text", "Second page text"), true, true, true, true, true, null);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", pdfPart("paper.pdf", pdf));

        Inspection report = client().post().uri("/api/pdf/inspect")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(Inspection.class);

        assertNotNull(report);
        assertEquals("paper.pdf", report.filename());
        assertEquals(2, report.pageCount());
        assertFalse(report.encrypted());
        assertTrue(report.hasMetadata());
        assertTrue(report.hasXmpMetadata());
        assertTrue(report.hasOutline());
        assertEquals(1, report.embeddedImageCount());
        assertEquals(1, report.annotationCount());
        assertTrue(report.fontCount() >= 1);
        assertEquals("Experimental Notes", report.metadata().title());
        assertEquals("Ada Lovelace", report.metadata().author());
        assertEquals(1, report.outline().size());
        assertEquals("Second Section", report.outline().get(0).title());
        assertEquals(2, report.outline().get(0).pageNumber());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("Privacy Scrub")));
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("annotations")));
    }

    @Test
    void inspectWarnsOnImageOnlyPdf() throws Exception {
        byte[] scan = createSamplePdf(List.of(""), false, false, false, false, true, null);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", pdfPart("scan.pdf", scan));

        Inspection report = client().post().uri("/api/pdf/inspect")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(Inspection.class);

        assertNotNull(report);
        assertEquals(1, report.pageCount());
        assertEquals(1, report.embeddedImageCount());
        assertEquals(0, report.pages().get(0).charCount());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("No selectable text layer")));
    }

    @Test
    void assembleMergesReordersRotatesAndDeletesPages() throws Exception {
        byte[] pdfA = createSamplePdf(List.of("DocA Page One", "DocA Page Two"), false, false, false, false, false, null);
        byte[] pdfB = createSamplePdf(List.of("DocB Page One", "DocB Page Two"), false, false, false, false, false, null);

        AssemblePlan plan = new AssemblePlan(
                List.of(new PageSpec(1, 2, 90), new PageSpec(0, 1, 180), new PageSpec(1, 1, 0)),
                false, false, "", 0.18, false, "merged-output");

        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("files", pdfPart("a.pdf", pdfA));
        parts.add("files", pdfPart("b.pdf", pdfB));
        parts.add("plan", JSON.writeValueAsString(plan));

        ResponseEntity<byte[]> response = client().post().uri("/api/pdf/assemble")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(byte[].class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("merged-output.pdf"), disposition);

        try (PDDocument assembled = Loader.loadPDF(response.getBody())) {
            assertEquals(3, assembled.getNumberOfPages());
            assertEquals(90, assembled.getPage(0).getRotation());
            assertEquals(180, assembled.getPage(1).getRotation());
            assertEquals(0, assembled.getPage(2).getRotation());

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1); stripper.setEndPage(1);
            String p1 = stripper.getText(assembled);
            String p1Compact = p1.replaceAll("\\s+", "");
            assertTrue(p1Compact.contains("DocBPageTwo") || p1Compact.contains("owTegaPBcoD"), p1);
            stripper.setStartPage(2); stripper.setEndPage(2);
            String p2 = stripper.getText(assembled);
            assertTrue(p2.contains("DocA Page One"), p2);
            stripper.setStartPage(3); stripper.setEndPage(3);
            String p3 = stripper.getText(assembled);
            assertTrue(p3.contains("DocB Page One"), p3);
        }
    }

    @Test
    void assembleScrubsMetadataStripsAnnotationsAndStampsWatermarkAndPageNumbers() throws Exception {
        byte[] pdf = createSamplePdf(List.of("Confidential body 1", "Confidential body 2"), true, true, false, true, false, null);
        String originalId;
        try (PDDocument orig = Loader.loadPDF(pdf)) {
            originalId = String.valueOf(orig.getDocument().getTrailer().getItem(COSName.ID));
        }
        AssemblePlan plan = new AssemblePlan(
                List.of(new PageSpec(0, 1, 0), new PageSpec(0, 2, 90)),
                true, true, "CONFIDENTIAL", 0.25, true, "clean.pdf");

        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("files", pdfPart("draft.pdf", pdf));
        parts.add("plan", JSON.writeValueAsString(plan));

        byte[] outBytes = client().post().uri("/api/pdf/assemble")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(byte[].class);

        assertNotNull(outBytes);
        try (PDDocument cleaned = Loader.loadPDF(outBytes)) {
            assertEquals(2, cleaned.getNumberOfPages());
            PDDocumentInformation info = cleaned.getDocumentInformation();
            assertNull(info.getTitle());
            assertNull(info.getAuthor());
            assertNull(info.getSubject());
            assertNull(info.getKeywords());
            assertNull(info.getCreator());
            assertNull(info.getProducer());
            assertTrue(info.getMetadataKeys().isEmpty());
            assertNull(cleaned.getDocumentCatalog().getMetadata());
            String cleanedId = String.valueOf(cleaned.getDocument().getTrailer().getItem(COSName.ID));
            assertNotEquals(originalId, cleanedId);
            assertEquals(0, cleaned.getPage(0).getAnnotations().size());
            assertEquals(0, cleaned.getPage(1).getAnnotations().size());

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1); stripper.setEndPage(1);
            String page1 = stripper.getText(cleaned);
            assertTrue(page1.replaceAll("\\s+", "").contains("CONFIDENTIAL"), page1);
            assertTrue(page1.contains("1 / 2"), page1);

            stripper.setStartPage(2); stripper.setEndPage(2);
            String page2 = stripper.getText(cleaned);
            assertTrue(page2.replaceAll("\\s+", "").contains("CONFIDENTIAL"), page2);
            assertTrue(page2.contains("2 / 2"), page2);
        }
    }

    @Test
    void extractTextMarkdownAndImagesZip() throws Exception {
        byte[] withImage = createSamplePdf(List.of("Alpha paragraph", "Beta paragraph"), false, false, false, false, true, null);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", pdfPart("report.pdf", withImage));

        TextExtract text = client().post().uri("/api/pdf/extract/text")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(TextExtract.class);
        assertNotNull(text);
        assertEquals(2, text.pageCount());
        assertFalse(text.truncated());
        assertTrue(text.pages().get(0).text().contains("Alpha paragraph"));
        assertTrue(text.pages().get(1).text().contains("Beta paragraph"));

        MarkdownExtract markdown = client().post().uri("/api/pdf/extract/markdown")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(MarkdownExtract.class);
        assertNotNull(markdown);
        assertTrue(markdown.markdown().contains("# report"));
        assertTrue(markdown.markdown().contains("## Page 1"));
        assertTrue(markdown.markdown().contains("Alpha paragraph"));

        byte[] zipBytes = client().post().uri("/api/pdf/extract/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(byte[].class);
        assertNotNull(zipBytes);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertTrue(entries.containsKey("page-001-img-01.png"));
        assertTrue(entries.containsKey("manifest.json"));
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"imageCount\" : 1") || manifest.contains("\"imageCount\":1"), manifest);

        byte[] textOnly = createSamplePdf(List.of("No images here"), false, false, false, false, false, null);
        var noImageParts = new LinkedMultiValueMap<String, Object>();
        noImageParts.add("file", pdfPart("plain.pdf", textOnly));
        var emptyImages = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/pdf/extract/images")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(noImageParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, emptyImages.getStatusCode());
    }

    @Test
    void validationRejectsCorruptEncryptedAndBadPlans() throws Exception {
        var notPdf = new LinkedMultiValueMap<String, Object>();
        notPdf.add("file", pdfPart("fake.pdf", "not a pdf document".getBytes(StandardCharsets.UTF_8)));
        var badHeader = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/pdf/inspect")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(notPdf)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badHeader.getStatusCode());

        byte[] locked = createSamplePdf(List.of("Secret text"), false, false, false, false, false, "secret123");
        var lockedParts = new LinkedMultiValueMap<String, Object>();
        lockedParts.add("file", pdfPart("locked.pdf", locked));
        var lockedErr = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/pdf/inspect")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(lockedParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, lockedErr.getStatusCode());

        var unlocked = client().post().uri("/api/pdf/inspect?password=secret123")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(lockedParts)
                .retrieve()
                .body(Inspection.class);
        assertNotNull(unlocked);
        assertTrue(unlocked.encrypted());
        assertEquals(1, unlocked.pageCount());

        var unlockedAssemble = new LinkedMultiValueMap<String, Object>();
        unlockedAssemble.add("files", pdfPart("locked.pdf", locked));
        unlockedAssemble.add("plan", JSON.writeValueAsString(new AssemblePlan(
                List.of(new PageSpec(0, 1, 0)), true, false, "", 0.2, false, "unlocked.pdf", List.of("secret123"))));
        byte[] assembledUnlocked = client().post().uri("/api/pdf/assemble")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(unlockedAssemble)
                .retrieve()
                .body(byte[].class);
        assertNotNull(assembledUnlocked);
        try (PDDocument doc = Loader.loadPDF(assembledUnlocked)) {
            assertEquals(1, doc.getNumberOfPages());
        }

        byte[] valid = createSamplePdf(List.of("One page"), false, false, false, false, false, null);
        var badPageParts = new LinkedMultiValueMap<String, Object>();
        badPageParts.add("files", pdfPart("one.pdf", valid));
        badPageParts.add("plan", JSON.writeValueAsString(new AssemblePlan(
                List.of(new PageSpec(0, 99, 0)), false, false, "", 0.2, false, "bad.pdf")));
        var badPage = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/pdf/assemble")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(badPageParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badPage.getStatusCode());

        var badWatermarkParts = new LinkedMultiValueMap<String, Object>();
        badWatermarkParts.add("files", pdfPart("one.pdf", valid));
        badWatermarkParts.add("plan", JSON.writeValueAsString(new AssemblePlan(
                List.of(new PageSpec(0, 1, 0)), false, false, "BAD\u0007TEXT", 0.2, false, "bad.pdf")));
        var badWatermark = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/pdf/assemble")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(badWatermarkParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badWatermark.getStatusCode());
        assertTrue(badWatermark.getResponseBodyAsString().toLowerCase().contains("watermark"), badWatermark.getResponseBodyAsString());

        var badRotationParts = new LinkedMultiValueMap<String, Object>();
        badRotationParts.add("files", pdfPart("one.pdf", valid));
        badRotationParts.add("plan", JSON.writeValueAsString(new AssemblePlan(
                List.of(new PageSpec(0, 1, 45)), false, false, "", 0.2, false, "bad.pdf")));
        var badRotation = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/pdf/assemble")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(badRotationParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.BAD_REQUEST, badRotation.getStatusCode());
        assertTrue(badRotation.getResponseBodyAsString().toLowerCase().contains("rotation"), badRotation.getResponseBodyAsString());
    }

    @Test
    void extractorProcessesAll500PagesAndPage2ImagesWhenPdfExceedsPageCap() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            BufferedImage img = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
            PDImageXObject ximg = LosslessFactory.createFromImage(doc, img);
            for (int p = 1; p <= 501; p++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                if (p == 1 || p == 2 || p == 500 || p == 501) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(font, 11);
                        cs.newLineAtOffset(72, 700);
                        cs.showText("Marker page " + p);
                        cs.endText();
                        if (p == 2) cs.drawImage(ximg, 72, 600, 12, 12);
                    }
                }
            }
            TextExtract text = PdfStudioExtractor.extractText(doc, "long.pdf");
            assertEquals(501, text.pageCount());
            assertTrue(text.truncated());
            assertEquals(500, text.pages().size(), "Must process all 500 capped pages, not stop after page 1");
            assertTrue(text.pages().get(499).text().contains("Marker page 500"));

            BinaryResult zip = PdfStudioExtractor.extractImagesZip(doc, "long.pdf");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip.bytes()))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) entries.put(e.getName(), zis.readAllBytes());
            }
            assertTrue(entries.containsKey("page-002-img-01.png"), "Image on page 2 must still be extracted");
            String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"truncated\" : true") || manifest.contains("\"truncated\":true"), manifest);
        }
    }

    @Test
    void assemblePreservesInheritedResourcesAndScrubsPageMetadataOnlyWhenEnabled() throws Exception {
        byte[] sourcePdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Inherited Font Resource Proof");
                cs.endText();
            }
            // Move /Resources from leaf page to its parent /Pages tree node to test inheritance.
            var leafRes = page.getCOSObject().getDictionaryObject(COSName.RESOURCES);
            var parentPages = page.getCOSObject().getCOSDictionary(COSName.PARENT);
            parentPages.setItem(COSName.RESOURCES, leafRes);
            page.getCOSObject().removeItem(COSName.RESOURCES);

            // Attach page-level XMP metadata.
            PDMetadata pageMeta = new PDMetadata(doc);
            pageMeta.importXMPMetadata("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">AuthorSecretXmp</x:xmpmeta>"
                    .getBytes(StandardCharsets.UTF_8));
            page.setMetadata(pageMeta);

            doc.save(out);
            sourcePdf = out.toByteArray();
        }

        // 1. Scrub enabled: text via inherited resources survives, page-level XMP is stripped.
        var scrubbedParts = new LinkedMultiValueMap<String, Object>();
        scrubbedParts.add("files", pdfPart("inherited.pdf", sourcePdf));
        scrubbedParts.add("plan", JSON.writeValueAsString(new AssemblePlan(
                List.of(new PageSpec(0, 1, 0)), true, false, "", 0.2, false, "scrubbed.pdf")));
        byte[] scrubbedBytes = client().post().uri("/api/pdf/assemble")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(scrubbedParts)
                .retrieve()
                .body(byte[].class);
        assertNotNull(scrubbedBytes);
        try (PDDocument outDoc = Loader.loadPDF(scrubbedBytes)) {
            String extracted = new PDFTextStripper().getText(outDoc);
            assertTrue(extracted.contains("Inherited Font Resource Proof"), extracted);
            assertNull(outDoc.getPage(0).getMetadata(), "Page-level XMP metadata must be scrubbed when enabled");
        }

        // 2. Scrub disabled: page-level XMP is preserved.
        var keepParts = new LinkedMultiValueMap<String, Object>();
        keepParts.add("files", pdfPart("inherited.pdf", sourcePdf));
        keepParts.add("plan", JSON.writeValueAsString(new AssemblePlan(
                List.of(new PageSpec(0, 1, 0)), false, false, "", 0.2, false, "kept.pdf")));
        byte[] keptBytes = client().post().uri("/api/pdf/assemble")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(keepParts)
                .retrieve()
                .body(byte[].class);
        assertNotNull(keptBytes);
        try (PDDocument outDoc = Loader.loadPDF(keptBytes)) {
            assertNotNull(outDoc.getPage(0).getMetadata(), "Page-level XMP metadata must remain when scrubMetadata=false");
        }
    }
}
