package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        properties = "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-pdftosrc-${random.uuid}")
class PdfToSourceTest {
    @LocalServerPort int port;
    @Autowired Tools tools;
    @Autowired DocxValidator docxValidator;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    private static ByteArrayResource pdfPart(String name, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        };
    }

    private static byte[] buildTwoColumnPdf(String password) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDPage p1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                drawLine(cs, regular, 9, 72, 760, "Journal Draft Running Header 1");
                drawLine(cs, bold, 18, 72, 710, "Structured Two Column Research Paper");
                drawLine(cs, regular, 10, 50, 660, "Left column opening paragraph line one.");
                drawLine(cs, regular, 10, 50, 642, "Left column continuation line two.");
                drawLine(cs, regular, 10, 50, 624, "Left column closing line three.");
                drawLine(cs, regular, 10, 50, 606, "Left column fourth line.");
                drawLine(cs, regular, 10, 330, 660, "Right column opening paragraph line one.");
                drawLine(cs, regular, 10, 330, 642, "Right column continuation line two.");
                drawLine(cs, regular, 10, 330, 624, "Right column closing line three.");
                drawLine(cs, regular, 10, 330, 606, "Right column fourth line.");

                BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                var g = img.createGraphics();
                g.setColor(new Color(0x1f, 0x4d, 0x3a));
                g.fillRect(0, 0, 16, 16);
                g.dispose();
                cs.drawImage(LosslessFactory.createFromImage(doc, img), 72, 460, 64, 64);
            }

            PDPage p2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                drawLine(cs, regular, 9, 72, 760, "Journal Draft Running Header 2");
                drawLine(cs, bold, 14, 72, 700, "Conclusion Section");
                drawLine(cs, regular, 10, 72, 670, "Final summary paragraph on page two.");
            }

            if (password != null) {
                doc.protect(new StandardProtectionPolicy("owner", password, new AccessPermission()));
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void drawLine(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text)
            throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    @Test
    void convertsTwoColumnAndHeadedPdfToMarkdownLatexZipAndDocx(@TempDir Path tempDir) throws Exception {
        byte[] pdf = buildTwoColumnPdf(null);
        var mdParts = new LinkedMultiValueMap<String, Object>();
        mdParts.add("file", pdfPart("paper.pdf", pdf));
        mdParts.add("targetFormat", "markdown");
        mdParts.add("aiLayout", "false");

        PdfToSourceService.PdfSourceResult mdResult = client().post().uri("/api/pdf/to-source")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(mdParts)
                .retrieve()
                .body(PdfToSourceService.PdfSourceResult.class);

        assertNotNull(mdResult);
        assertEquals(2, mdResult.pageCount());
        assertEquals(1, mdResult.imagesExtracted());
        assertTrue(mdResult.twoColumnDetected());
        assertTrue(mdResult.headingsFound() >= 2);
        assertFalse(mdResult.content().contains("Journal Draft Running Header"), mdResult.content());
        assertTrue(mdResult.content().contains("# Structured Two Column Research Paper"), mdResult.content());
        assertTrue(mdResult.content().contains("media/fig-001.png"), mdResult.content());

        int leftPos = mdResult.content().indexOf("Left column closing line three.");
        int rightPos = mdResult.content().indexOf("Right column opening paragraph");
        assertTrue(leftPos > 0 && rightPos > leftPos, mdResult.content());

        assumeTrue(tools.available(tools.pandoc()));

        var texParts = new LinkedMultiValueMap<String, Object>();
        texParts.add("file", pdfPart("paper.pdf", pdf));
        texParts.add("targetFormat", "latex");
        PdfToSourceService.PdfSourceResult texResult = client().post().uri("/api/pdf/to-source")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(texParts)
                .retrieve()
                .body(PdfToSourceService.PdfSourceResult.class);
        assertNotNull(texResult);
        assertTrue(texResult.content().contains("\\documentclass"), texResult.content());
        assertTrue(texResult.content().contains("Left column opening paragraph"), texResult.content());
        assertTrue(texResult.content().contains("Final summary paragraph on page two."), texResult.content());

        var mdDlParts = new LinkedMultiValueMap<String, Object>();
        mdDlParts.add("file", pdfPart("paper.pdf", pdf));
        mdDlParts.add("targetFormat", "markdown");
        String downloadedMd = client().post().uri("/api/pdf/to-source/download")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(mdDlParts)
                .retrieve()
                .body(String.class);
        assertNotNull(downloadedMd);
        assertTrue(downloadedMd.contains("data:image/png;base64,"), "Standalone Markdown download must inline extracted PNGs");
        assertTrue(downloadedMd.contains("Final summary paragraph on page two."), downloadedMd);

        var zipParts = new LinkedMultiValueMap<String, Object>();
        zipParts.add("file", pdfPart("paper.pdf", pdf));
        zipParts.add("targetFormat", "latex-zip");
        byte[] zipBytes = client().post().uri("/api/pdf/to-source/download")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(zipParts)
                .retrieve()
                .body(byte[].class);
        assertNotNull(zipBytes);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertTrue(entries.containsKey("main.tex"));
        String zipTex = new String(entries.get("main.tex"), StandardCharsets.UTF_8);
        assertTrue(zipTex.contains("Left column opening paragraph") && zipTex.contains("Final summary paragraph on page two."), zipTex);
        assertTrue(entries.containsKey("media/fig-001.png"));
        assertTrue(entries.containsKey("README.txt"));

        var docxParts = new LinkedMultiValueMap<String, Object>();
        docxParts.add("file", pdfPart("paper.pdf", pdf));
        docxParts.add("targetFormat", "docx");
        byte[] docxBytes = client().post().uri("/api/pdf/to-source/download")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(docxParts)
                .retrieve()
                .body(byte[].class);
        assertNotNull(docxBytes);
        Path outDocx = tempDir.resolve("from-pdf.docx");
        Files.write(outDocx, docxBytes);
        docxValidator.validate(outDocx);
        boolean hasEmbeddedImage = false;
        String docxDocumentXml = "";
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("word/media/")) hasEmbeddedImage = true;
                if (entry.getName().equals("word/document.xml")) {
                    docxDocumentXml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(hasEmbeddedImage, "DOCX exported from PDF under --sandbox must embed extracted media");
        assertTrue(docxDocumentXml.contains("Left column opening paragraph"), docxDocumentXml);
        assertTrue(docxDocumentXml.contains("Final summary paragraph on page two."), docxDocumentXml);
    }

    @Test
    void singleColumnWordSplitLinesStaySingleColumnAndMergeTitleWords() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Draw words separately (mimicking PDFBox word-level writeString chunks on real LaTeX PDFs).
                drawLine(cs, bold, 17, 160, 720, "Attention");
                drawLine(cs, bold, 17, 245, 720, "Is");
                drawLine(cs, bold, 17, 268, 720, "All");
                drawLine(cs, bold, 17, 298, 720, "You");
                drawLine(cs, bold, 17, 336, 720, "Need");
                for (int i = 0; i < 8; i++) {
                    float y = 660 - i * 14;
                    drawLine(cs, regular, 10, 80, y, "The dominant sequence");
                    drawLine(cs, regular, 10, 195, y, "transduction models");
                    drawLine(cs, regular, 10, 305, y, "are based on complex");
                    drawLine(cs, regular, 10, 415, y, "neural networks line " + (i + 1) + ".");
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", pdfPart("single-col.pdf", pdf));
        parts.add("targetFormat", "markdown");
        parts.add("aiLayout", "false");

        PdfToSourceService.PdfSourceResult res = client().post().uri("/api/pdf/to-source")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(PdfToSourceService.PdfSourceResult.class);
        assertNotNull(res);
        assertFalse(res.twoColumnDetected(), res.content());
        assertTrue(res.content().contains("# Attention Is All You Need"), res.content());
        assertTrue(res.content().contains("The dominant sequence transduction models are based on complex neural networks line 1."), res.content());
    }

    @Test
    void handlesLockedPdfPasswordCheck() throws Exception {
        byte[] locked = buildTwoColumnPdf("unlock42");
        var lockedParts = new LinkedMultiValueMap<String, Object>();
        lockedParts.add("file", pdfPart("secret.pdf", locked));
        lockedParts.add("targetFormat", "markdown");

        var err = assertThrows(HttpClientErrorException.class, () ->
                client().post().uri("/api/pdf/to-source")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(lockedParts)
                        .retrieve()
                        .toBodilessEntity());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, err.getStatusCode());

        var unlocked = client().post().uri("/api/pdf/to-source?password=unlock42")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(lockedParts)
                .retrieve()
                .body(PdfToSourceService.PdfSourceResult.class);
        assertNotNull(unlocked);
        assertEquals(2, unlocked.pageCount());
    }

    @Test
    void preservesInteriorSpanningHeadingsNumericMarginBodyLinesAndImageLimitWarnings() throws Exception {
        List<PdfLayoutExtractor.TextLine> lines = List.of(
                new PdfLayoutExtractor.TextLine(100, 60, 400, 16, 16, true, "Top Title", 0),
                new PdfLayoutExtractor.TextLine(50, 140, 200, 10, 10, false, "Upper Left", 0),
                new PdfLayoutExtractor.TextLine(330, 140, 200, 10, 10, false, "Upper Right", 0),
                new PdfLayoutExtractor.TextLine(100, 260, 400, 14, 14, true, "Mid Spanning Section", 0),
                new PdfLayoutExtractor.TextLine(50, 340, 200, 10, 10, false, "Lower Left", 0),
                new PdfLayoutExtractor.TextLine(330, 340, 200, 10, 10, false, "Lower Right", 0));
        List<String> orderedTexts = PdfLayoutExtractor.orderTwoColumns(lines, 612f)
                .stream().map(PdfLayoutExtractor.TextLine::text).toList();
        assertEquals(
                List.of("Top Title", "Upper Left", "Upper Right", "Mid Spanning Section", "Lower Left", "Lower Right"),
                orderedTexts);

        var page1 = new PdfLayoutExtractor.PageLines(1, 612f, 792f, List.of(
                new PdfLayoutExtractor.TextLine(72, 40, 120, 10, 10, false, "Result: 10 mg", 0),
                new PdfLayoutExtractor.TextLine(72, 54, 60, 10, 10, false, "10 mg", 0),
                new PdfLayoutExtractor.TextLine(72, 760, 140, 9, 9, false, "1 | Chapter Title", 0)));
        var page2 = new PdfLayoutExtractor.PageLines(2, 612f, 792f, List.of(
                new PdfLayoutExtractor.TextLine(72, 40, 120, 10, 10, false, "Result: 20 mg", 0),
                new PdfLayoutExtractor.TextLine(72, 54, 60, 10, 10, false, "20 mg", 0),
                new PdfLayoutExtractor.TextLine(72, 760, 140, 9, 9, false, "2 | Chapter Title", 0)));
        var repeated = PdfLayoutExtractor.detectRepeatedMargins(List.of(page1, page2));
        assertEquals(java.util.Set.of("# | chapter title"), repeated,
                "Only genuine running headers/page numbers should collapse, never distinct numeric body lines");

        byte[] manyImagesPdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawLine(cs, regular, 9, 72, 760, "Journal Draft Running Header 1");
                drawLine(cs, regular, 10, 72, 680, "Body text accompanying sixty-one figures.");
                for (int i = 0; i < 61; i++) {
                    BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
                    img.setRGB(0, 0, i * 1000 + 1);
                    PDImageXObject ximg = LosslessFactory.createFromImage(doc, img);
                    cs.drawImage(ximg, 20 + (i % 10) * 10, 620 - (i / 10) * 10, 4, 4);
                }
            }
            PDPage p2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                drawLine(cs, regular, 9, 72, 760, "Journal Draft Running Header 2");
            }
            doc.save(out);
            manyImagesPdf = out.toByteArray();
        }
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", pdfPart("many-figs.pdf", manyImagesPdf));
        parts.add("targetFormat", "markdown");
        PdfToSourceService.PdfSourceResult manyRes = client().post().uri("/api/pdf/to-source")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(PdfToSourceService.PdfSourceResult.class);
        assertNotNull(manyRes);
        assertEquals(60, manyRes.imagesExtracted());
        assertTrue(manyRes.warnings().stream().anyMatch(w -> w.contains("60-figure limit")), manyRes.warnings().toString());

        assumeTrue(tools.available(tools.pandoc()));
        var dlParts = new LinkedMultiValueMap<String, Object>();
        dlParts.add("file", pdfPart("many-figs.pdf", manyImagesPdf));
        dlParts.add("targetFormat", "docx");
        dlParts.add("aiLayout", "true");
        var docxDownload = client().post().uri("/api/pdf/to-source/download")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(dlParts)
                .retrieve()
                .toEntity(byte[].class);
        assertEquals(HttpStatus.OK, docxDownload.getStatusCode());
        String warnHeader = docxDownload.getHeaders().getFirst("X-Doc2TeX-Warnings");
        assertNotNull(warnHeader, "Download response must include X-Doc2TeX-Warnings header");
        assertTrue(warnHeader.contains("60-figure limit"), "Image-omission warning must not be truncated by earlier notes: " + warnHeader);
    }
}
