package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;

@Tag("ai")
class AiLayoutIntegrationTest {
    @TempDir Path directory;
    private AppProperties properties() { return new AppProperties(directory.resolve("projects, local").toString(), "", "", 1, 2, 30, 300); }

    static byte[] fixture(boolean columns) throws Exception {
        String body = AiLayoutServiceTest.paragraph("Layout assistance study", "<w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"240\"/></w:pPr>", "<w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr>");
        String[] titles = {"1. Introduction", "2. Materials and methods", "3. Evaluation results", "4. Discussion"};
        for (int section = 0; section < titles.length; section++) {
            if (columns && section == 2) body += "<w:p><w:r><w:br w:type=\"column\"/></w:r></w:p>";
            body += AiLayoutServiceTest.paragraph(titles[section], "<w:pPr><w:spacing w:before=\"180\" w:after=\"100\"/></w:pPr>", "<w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr>");
            for (int paragraph = 0; paragraph < 2; paragraph++) body += AiLayoutServiceTest.paragraph(
                    "BODY_CHECKPOINT_" + section + "_" + paragraph + ". The experiment measures document structure using original source content. "
                    + "All observations remain editable in the resulting project. The conversion retains these sentences while restoring their surrounding layout.",
                    "<w:pPr><w:spacing w:after=\"100\"/></w:pPr>", "<w:rPr><w:sz w:val=\"20\"/></w:rPr>");
        }
        body += AiLayoutServiceTest.paragraph("Native heading checkpoint", "<w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>", "");
        body += "<w:p><w:r><w:t>MATH_CHECKPOINT </w:t></w:r><m:oMath><m:r><m:t>x=42</m:t></m:r></m:oMath></w:p>";
        return AiLayoutServiceTest.fixture(body, columns);
    }

    private Job await(JobStore store, Job job, boolean pdf) throws Exception {
        long deadline = System.nanoTime() + 300_000_000_000L;
        while (System.nanoTime() < deadline) {
            job = store.get(job.id()); String state = pdf ? job.previewStatus() : job.status();
            if (List.of("COMPLETED", "READY", "FAILED").contains(state)) { assertNotEquals("FAILED", state, job.toString()); return job; }
            Thread.sleep(50);
        }
        fail("Timed out waiting for real AI conversion"); return job;
    }

    @Test void realModelRestoresUnstyledHeadingsAndPreservesNativeContentAndExports() throws Exception { verify(false); }
    @Test void realModelConfirmsAndRestoresNativeTwoColumns() throws Exception { verify(true); }

    private void verify(boolean columns) throws Exception {
        var configuration = new java.util.Properties();
        Path local = Path.of(".tools/layout-ai/renderer.properties");
        if (Files.isRegularFile(local)) try (var input = Files.newInputStream(local)) { configuration.load(input); }
        var settings = new AiLayoutSettings(true, System.getProperty("doc2tex.test-ai-model", ".tools/layout-ai/inference.onnx"),
                System.getenv().getOrDefault("SOFFICE_PATH", ""), 6, 90,
                System.getProperty("doc2tex.test-ai-docker-image", configuration.getProperty("doc2tex.ai-layout.docker-image", "")));
        var runner = new ToolRunner(); var ai = new AiLayoutService(settings, runner);
        assertEquals("ready", ai.status().state(), ai.status().guidance());
        var store = new JobStore(properties()); store.load();
        var service = new ConversionService(store, new Tools(properties()), runner, new DocxValidator(), properties(), new DocumentInspector(), new FontSupport(), ai);
        try {
            byte[] original = fixture(columns);
            Job job = await(store, service.submit(new MockMultipartFile("file", columns ? "ai-columns.docx" : "ai-headings.docx", "application/octet-stream", original), "article"), false);
            Path output = store.directory(job.id()).resolve("output"); var report = AiLayoutReport.read(output);
            String renderLog = Files.readString(store.directory(job.id()).resolve("ai-layout-render.log"));
            assertEquals(AiLayoutReport.Status.ANALYZED, report.status(), report + "\n" + renderLog);
            assertFalse(report.appliedHeadings().isEmpty(), report.toString());
            assertEquals(columns, report.twoColumns(), report.toString());
            String source = service.source(job.id());
            assertTrue(source.lines().anyMatch(line -> line.startsWith("\\section{")
                    && (line.contains("1. Introduction") || line.contains("2. Materials and methods"))), source);
            for (int section = 0; section < 4; section++) for (int paragraph = 0; paragraph < 2; paragraph++)
                assertTrue(source.contains("BODY\\_CHECKPOINT\\_" + section + "\\_" + paragraph), source);
            assertTrue(source.contains("\\section{Native heading checkpoint}"), source);
            assertTrue(source.contains("MATH\\_CHECKPOINT"), source); assertTrue(source.contains("x = 42") || source.contains("x=42"), source);
            assertEquals(columns, source.contains("twocolumn"));
            if (columns) { assertTrue(source.contains("\\setlength{\\columnsep}{6.35mm}"), source); assertEquals(1, report.appliedColumnBreaks()); }
            assertArrayEquals(original, Files.readAllBytes(service.original(job.id())));
            assertTrue(service.review(job.id()).findings().stream().anyMatch(finding -> finding.code().equals("ai-layout")));
            var restarted = new JobStore(properties()); restarted.load(); assertTrue(restarted.get(job.id()).layout().aiLayout());
            job = await(store, service.preview(job.id()), true);
            Path evidence = Path.of(System.getProperty("doc2tex.build-directory", "target"), "fidelity-evidence", "ai-layout", columns ? "columns" : "headings");
            Files.createDirectories(evidence);
            Files.write(evidence.resolve("original.docx"), original);
            Files.copy(store.directory(job.id()).resolve("ai-layout-source/input.pdf"), evidence.resolve("original.pdf"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(service.pdf(job.id()), evidence.resolve("converted.pdf"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(evidence.resolve("main.tex"), source); Files.copy(output.resolve("ai-layout.json"), evidence.resolve("ai-layout.json"), StandardCopyOption.REPLACE_EXISTING);
            try (var pdf = Loader.loadPDF(service.pdf(job.id()).toFile())) {
                var intro = new java.util.ArrayList<Float>(); var evaluation = new java.util.ArrayList<Float>();
                var stripper = new PDFTextStripper() {
                    @Override protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) throws java.io.IOException {
                        if (text.contains("Introduction")) intro.add(positions.getFirst().getXDirAdj());
                        if (text.contains("Evaluation")) evaluation.add(positions.getFirst().getXDirAdj());
                        super.writeString(text, positions);
                    }
                };
                String text = stripper.getText(pdf);
                for (int section = 0; section < 4; section++) for (int paragraph = 0; paragraph < 2; paragraph++)
                    assertTrue(text.contains("BODY_CHECKPOINT_" + section + "_" + paragraph), text);
                if (columns) {
                    assertFalse(intro.isEmpty()); assertFalse(evaluation.isEmpty());
                    assertTrue(intro.getFirst() < pdf.getPage(0).getMediaBox().getWidth() / 2);
                    assertTrue(evaluation.getFirst() > pdf.getPage(0).getMediaBox().getWidth() / 2, "The explicit source break puts Results in the right column");
                }
                ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(0, 100), "png", evidence.resolve("converted.png").toFile());
            }
            var archive = new ByteArrayOutputStream(); service.writeArchive(job.id(), archive); boolean found = false;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("ai-layout.json")) { found = true; assertTrue(zip.readAllBytes().length > 0); }
            }
            assertTrue(found, "The export carries the actual model evidence and applied decisions.");
        } finally { service.stop(); ai.close(); }
    }
}
