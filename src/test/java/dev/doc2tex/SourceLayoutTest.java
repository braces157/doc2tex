package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

class SourceLayoutTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    @TempDir Path directory;

    static String section(int width, int height, int top, int right, int bottom, int left) {
        return "<w:sectPr><w:pgSz w:w=\"" + width + "\" w:h=\"" + height + "\"/>"
                + "<w:pgMar w:top=\"" + top + "\" w:right=\"" + right + "\" w:bottom=\"" + bottom + "\" w:left=\"" + left + "\"/></w:sectPr>";
    }

    static byte[] document(String sections, String settings) throws Exception {
        String xml = "<w:document xmlns:w=\"" + W + "\"><w:body>"
                + "<w:p><w:r><w:t>PAGE_LAYOUT_SENTINEL Original body remains editable.</w:t></w:r></w:p>"
                + sections + "</w:body></w:document>";
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes); var input = new ZipInputStream(new ByteArrayInputStream(SampleDocument.create()))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals("word/settings.xml")) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals("word/document.xml")) output.write(xml.getBytes(StandardCharsets.UTF_8));
                else input.transferTo(output);
                output.closeEntry();
            }
            if (settings != null) {
                output.putNextEntry(new ZipEntry("word/settings.xml"));
                output.write(("<w:settings xmlns:w=\"" + W + "\">" + settings + "</w:settings>").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private SourceLayout inspect(String sections, String settings) throws Exception {
        Path file = directory.resolve("input.docx");
        Files.write(file, document(sections, settings));
        return SourceLayout.inspect(file);
    }

    @Test void detectsLandscapeAndFourIndependentMarginsAndRoundTripsItsReport() throws Exception {
        var report = inspect(section(15840, 12240, 720, 1080, 1440, 1800), null);
        var page = report.sections().getFirst();
        assertEquals(new BigDecimal("279.4"), page.widthMm()); assertEquals(new BigDecimal("215.9"), page.heightMm());
        assertEquals(new BigDecimal("12.7"), page.topMm()); assertEquals(new BigDecimal("19.05"), page.rightMm());
        assertEquals(new BigDecimal("25.4"), page.bottomMm()); assertEquals(new BigDecimal("31.75"), page.leftMm());
        assertTrue(report.warnings().isEmpty()); assertTrue(page.description().contains("landscape"));
        report.save(directory); assertEquals(report, SourceLayout.read(directory));
    }

    @Test void reportsChangingSectionsAndIgnoresTrackedHistoricalPageSettings() throws Exception {
        String first = section(15840, 12240, 720, 1080, 1440, 1800);
        String last = section(12240, 15840, 1440, 1440, 1440, 1440);
        var report = inspect("<w:p><w:pPr>" + first + "</w:pPr></w:p>" + last, null);
        assertEquals(2, report.sections().size()); assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().getFirst().contains("first section"));
        String historical = first.replace("</w:sectPr>", "<w:sectPrChange>" + last + "</w:sectPrChange></w:sectPr>");
        report = inspect(historical, null);
        assertEquals(1, report.sections().size()); assertTrue(report.warnings().isEmpty());
        report = inspect("<w:p><w:pPr>" + first + "</w:pPr></w:p>" + first, null);
        assertEquals(2, report.sections().size()); assertTrue(report.warnings().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"", "<w:sectPr/>", "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr>"})
    void incompleteSettingsFallBackVisibly(String sections) throws Exception {
        var report = inspect(sections, null);
        assertTrue(report.sections().isEmpty()); assertTrue(report.warnings().getFirst().contains("selected paper size"));
        assertEquals("warning", report.findings().getFirst().severity());
    }

    @ParameterizedTest @ValueSource(strings = {"del", "moveFrom"})
    void revisedSectionBreakCannotSelectADeletedFirstPageLayout(String revision) throws Exception {
        String landscape = section(15840, 12240, 720, 1080, 1440, 1800);
        String portrait = section(12240, 15840, 1440, 1440, 1440, 1440);
        var report = inspect("<w:p><w:pPr><w:rPr><w:" + revision + " w:id=\"1\"/></w:rPr>"
                + landscape + "</w:pPr></w:p>" + portrait, null);
        assertTrue(report.sections().isEmpty()); assertTrue(report.warnings().getFirst().contains("section break"));
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "0", "999999999999999999999", "12000;input", "34016"})
    void invalidDimensionsNeverBecomeConverterArguments(String width) throws Exception {
        var report = inspect(section(12240, 15840, 1440, 1440, 1440, 1440).replace("w:w=\"12240\"", "w:w=\"" + width + "\""), null);
        assertTrue(report.sections().isEmpty()); assertFalse(report.warnings().isEmpty());
    }

    @Test void unusableTextAreaAndBindingLayoutsFallBackInsteadOfClaimingPreservation() throws Exception {
        assertTrue(inspect(section(12240, 15840, 1440, 7000, 1440, 7000), null).sections().isEmpty());
        assertTrue(inspect(section(12240, 15840, 1440, 1440, 1440, 1440), "<w:mirrorMargins/>").sections().isEmpty());
        assertTrue(inspect(section(12240, 15840, 1440, 1440, 1440, 1440), "<w:mirrorMargins w:val=\"false\"/>").warnings().isEmpty());
        assertTrue(inspect(section(12240, 15840, 1440, 1440, 1440, 1440).replace("<w:pgMar", "<w:pgMar w:gutter=\"720\""), null).sections().isEmpty());
    }

    @Test void refusesExternalEntitiesAndOversizedXml() throws Exception {
        Path secret = directory.resolve("secret.txt"); Files.writeString(secret, section(12240, 15840, 1440, 1440, 1440, 1440));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<!DOCTYPE document [<!ENTITY external SYSTEM \"" + secret.toUri() + "\">]>"
                    + "<w:document xmlns:w=\"" + W + "\"><w:body>&external;</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path file = directory.resolve("external.docx"); Files.write(file, bytes.toByteArray());
        var report = SourceLayout.inspect(file);
        assertTrue(report.sections().isEmpty(), "External entities must never supply page settings");
        report = inspect(" ".repeat(8 * 1024 * 1024), null);
        assertTrue(report.sections().isEmpty()); assertTrue(report.warnings().getFirst().contains("safely"));
    }

    private AppProperties properties() { return new AppProperties(directory.toString(), "", "", 1, 4, 30, 300); }
    private ConversionService service(JobStore store) {
        return new ConversionService(store, new Tools(properties()), new ToolRunner(), new DocxValidator(), properties(), new DocumentInspector(), new FontSupport());
    }
    private Job await(JobStore store, Job job, boolean pdf) throws Exception {
        long deadline = System.nanoTime() + (pdf ? 300L : 30L) * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = store.get(job.id()); String state = pdf ? job.previewStatus() : job.status();
            if (List.of("COMPLETED", "READY", "FAILED").contains(state)) { assertNotEquals("FAILED", state, job.toString()); return job; }
            Thread.sleep(50);
        }
        fail("Timed out waiting for source layout conversion"); return job;
    }

    @Test void autoSettingsPersistAndExportWhileCustomCopyPreservesParentEdits() throws Exception {
        var tools = new Tools(properties()); assumeTrue(tools.available(tools.pandoc()));
        var store = new JobStore(properties()); store.load(); var service = service(store);
        try {
            byte[] original = document(section(15840, 12240, 720, 1080, 1440, 1800), null);
            Job job = await(store, service.submit(new MockMultipartFile("file", "source.docx", "application/octet-stream", original), "article"), false);
            assertTrue(job.layout().preserveSourceLayout());
            String source = service.source(job.id()); assertTrue(source.contains("paperwidth=279.4mm,paperheight=215.9mm"), source);
            assertTrue(source.contains("left=31.75mm"), source); assertFalse(source.contains("margin=25.4mm"), source);
            assertTrue(service.review(job.id()).findings().stream().anyMatch(finding -> finding.code().equals("source-page-layout")));
            var restarted = new JobStore(properties()); restarted.load(); assertTrue(restarted.get(job.id()).layout().preserveSourceLayout());
            var archive = new ByteArrayOutputStream(); service.writeArchive(job.id(), archive); boolean foundReport = false;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("source-layout.json")) {
                    foundReport = true;
                    assertEquals(SourceLayout.inspect(service.original(job.id())), JsonMapper.builder().build().readValue(zip.readAllBytes(), SourceLayout.class));
                }
            }
            assertTrue(foundReport, "Exports include the actual detected page settings");
            job = service.saveSource(job.id(), source + "\n% KEEP_PAGE_EDITS", 0);
            Job copy = await(store, service.deriveLayout(job.id(), job.revision(), "report", LayoutOptions.defaults()), false);
            assertFalse(copy.layout().preserveSourceLayout()); assertTrue(service.source(copy.id()).contains("margin=25.4mm"));
            assertFalse(service.source(copy.id()).contains("paperwidth=279.4mm")); assertTrue(service.source(job.id()).contains("KEEP_PAGE_EDITS"));
            assertArrayEquals(original, Files.readAllBytes(service.original(job.id())));
            Files.writeString(store.directory(job.id()).resolve("output/source-layout.json"), "{}");
            assertTrue(service.review(job.id()).findings().stream().anyMatch(finding -> finding.title().equals("Page settings could not be verified")));
            assertTrue(service.source(job.id()).contains("KEEP_PAGE_EDITS"));
        } finally { service.stop(); }
    }

    @Test void unsupportedSourceUsesManualFallbackAndExposesTheReason() throws Exception {
        assumeTrue(new Tools(properties()).available(new Tools(properties()).pandoc()));
        var store = new JobStore(properties()); store.load(); var service = service(store);
        try {
            Job job = await(store, service.submit(new MockMultipartFile("file", "fallback.docx", "application/octet-stream", document("", null)), "article"), false);
            assertTrue(service.source(job.id()).contains("margin=25.4mm"));
            assertTrue(service.review(job.id()).findings().stream().anyMatch(finding -> finding.code().equals("source-page-layout-review") && finding.detail().contains("selected paper size")));
            assertFalse(service.review(job.id()).findings().stream().anyMatch(finding -> finding.code().equals("source-page-layout")));
        } finally { service.stop(); }
    }

    @ParameterizedTest @CsvSource({"article,15840,12240", "report,9000,14000"})
    void compiledPdfMatchesOriginalDimensionsAndIndependentMargins(String template, int width, int height) throws Exception {
        var tools = new Tools(properties()); assumeTrue(Boolean.getBoolean("doc2tex.test-pdf"));
        assumeTrue(tools.available(tools.pandoc()) && tools.available(tools.tectonic()));
        var store = new JobStore(properties()); store.load(); var service = service(store);
        try {
            Job job = await(store, service.submit(new MockMultipartFile("file", "page.docx", "application/octet-stream",
                    document(section(width, height, 720, 1080, 1440, 1800), null)), template), false);
            job = await(store, service.preview(job.id()), true);
            try (var pdf = Loader.loadPDF(service.pdf(job.id()).toFile())) {
                assertEquals(1, pdf.getNumberOfPages());
                assertEquals(width / 20f, pdf.getPage(0).getMediaBox().getWidth(), 0.1);
                assertEquals(height / 20f, pdf.getPage(0).getMediaBox().getHeight(), 0.1);
                var positions = new ArrayList<TextPosition>();
                var stripper = new PDFTextStripper() {
                    @Override protected void writeString(String text, List<TextPosition> characters) throws IOException {
                        if (text.contains("PAGE_LAYOUT_SENTINEL")) positions.addAll(characters);
                        super.writeString(text, characters);
                    }
                };
                String text = stripper.getText(pdf); assertTrue(text.contains("Original body remains editable."), text);
                assertFalse(positions.isEmpty()); assertEquals(90f, positions.getFirst().getXDirAdj(), 0.5);
                assertEquals(36f + 10f, positions.getFirst().getYDirAdj(), 2);
                assertEquals(10f, positions.getFirst().getFontSize(), 0.3);
                Path evidence = Path.of(System.getProperty("doc2tex.build-directory", "target"), "fidelity-evidence", "source-layout", template);
                Files.createDirectories(evidence); Files.writeString(evidence.resolve("main.tex"), service.source(job.id()));
                Files.writeString(evidence.resolve("pdf-text.txt"), text);
                Files.copy(service.pdf(job.id()), evidence.resolve("main.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { service.stop(); }
    }
}
