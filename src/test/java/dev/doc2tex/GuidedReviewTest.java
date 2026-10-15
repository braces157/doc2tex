package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class GuidedReviewTest {
    @TempDir Path temporary;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String CAPTION = "Figure 1: Distinctive caption evidence";
    private static final String CELL = "Distinctive merged table anchor";
    private static final String BODY = "<w:p><w:pPr><w:pStyle w:val=\"Caption\"/></w:pPr><w:r><w:t>" + CAPTION + "</w:t></w:r></w:p>"
            + "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p><w:r><w:t>" + CELL + "</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
            + "<w:p><w:r><w:t>Tracked original </w:t></w:r><w:ins><w:r><w:t>accepted insertion</w:t></w:r></w:ins><w:del><w:r><w:delText>removed deletion</w:delText></w:r></w:del></w:p>";
    private static final String SOURCE = "\\documentclass{article}\n\\begin{document}\n\n\\caption{" + CAPTION + "}\n\n\\begin{longtable}{ll}\n" + CELL + " & value \\\\\n\\end{longtable}\n\nTracked original accepted insertion\n\n\\end{document}\n";

    private byte[] docx(String body, String header) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"" + W + "\"><w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            if (header != null) {
                zip.putNextEntry(new ZipEntry("word/header1.xml"));
                zip.write(("<w:hdr xmlns:w=\"" + W + "\"><w:p><w:r><w:t>" + header + "</w:t></w:r></w:p></w:hdr>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private Path input(String body, String header) throws IOException {
        Path file = temporary.resolve(UUID.randomUUID() + ".docx"); Files.write(file, docx(body, header)); return file;
    }
    private ReviewEvidence.Item item(ReviewEvidence.Extraction report, String code) { return report.items().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow(); }

    @Test void extractsStableOriginalOccurrencesAndConservativeStructuredLocations() throws Exception {
        Path input = input(BODY, "Essential header omitted by conversion");
        var report = ReviewEvidence.inspect(input, SOURCE, 0);
        assertFalse(report.truncated()); assertEquals(4, report.items().size());
        assertEquals(report, ReviewEvidence.inspect(input, SOURCE, 0));
        assertTrue(report.paragraphs().stream().anyMatch(paragraph -> paragraph.text().contains("removed deletion")));
        var caption = item(report, "captions"); assertEquals(CAPTION, caption.excerpt()); assertEquals("matched", caption.mapping().status()); assertEquals(4, caption.mapping().line());
        var merged = item(report, "merged"); assertTrue(merged.excerpt().contains(CELL)); assertEquals("matched", merged.mapping().status()); assertTrue(merged.mapping().sourceExcerpt().contains("\\begin{longtable}"));
        var header = item(report, "headers"); assertEquals("Essential header omitted by conversion", header.excerpt()); assertEquals("unavailable", header.mapping().status()); assertTrue(header.mapping().reason().contains("omitted"));
        assertEquals("matched", item(report, "revisions").mapping().status());
        var later = ReviewEvidence.inspect(input, SOURCE + "\n% unrelated change\n", 1);
        assertEquals(caption.id(), item(later, "captions").id()); assertEquals(caption.fingerprint(), item(later, "captions").fingerprint());
        assertNotEquals(header.fingerprint(), item(later, "headers").fingerprint());
        var modifiedTable = ReviewEvidence.inspect(input, SOURCE.replace("{longtable}{ll}", "{longtable}{p{3cm}l}"), 2);
        assertNotEquals(merged.fingerprint(), item(modifiedTable, "merged").fingerprint());
    }

    @Test void repeatedMissingCommentedAndUnstructuredTextNeverGetsGuessedLocations() throws Exception {
        String caption = BODY.substring(0, BODY.indexOf("<w:tbl>"));
        var repeated = ReviewEvidence.inspect(input(caption + caption, null), SOURCE, 0);
        assertEquals(2, repeated.items().size()); assertNotEquals(repeated.items().getFirst().id(), repeated.items().getLast().id());
        assertTrue(repeated.items().stream().allMatch(item -> item.mapping().status().equals("unavailable") && item.mapping().reason().contains("ambiguous")));
        var duplicateSource = ReviewEvidence.inspect(input(caption, null), SOURCE + "\n" + CAPTION, 0);
        assertEquals("unavailable", item(duplicateSource, "captions").mapping().status());
        var commented = ReviewEvidence.inspect(input(caption, null), "% " + CAPTION + "\n", 0);
        assertEquals("unavailable", item(commented, "captions").mapping().status());
        var unstructured = ReviewEvidence.inspect(input(BODY, null), SOURCE.replace("\\begin{longtable}{ll}", "").replace("\\end{longtable}", ""), 0);
        assertEquals("unavailable", item(unstructured, "merged").mapping().status());
        var missing = ReviewEvidence.inspect(input(caption, null), "unrelated source text", 0);
        assertEquals("unavailable", item(missing, "captions").mapping().status());
    }

    @Test void malformedExternalDeepAndOversizedXmlAreLimitedWithoutUnsafeMappings() throws Exception {
        Path malformed = input("<w:p>", "Essential header still readable");
        var partial = ReviewEvidence.inspect(malformed, SOURCE, 0);
        assertTrue(partial.truncated()); assertTrue(partial.paragraphs().stream().anyMatch(paragraph -> paragraph.text().contains("Essential header")));
        Path external = temporary.resolve("external.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(external))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///C:/Windows/win.ini'>]><x>&secret;</x>".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(ReviewEvidence.inspect(external, SOURCE, 0).truncated());
        Path foreign = temporary.resolve("foreign.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(foreign))) {
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write("<document xmlns='urn:unexpected'><p>Unsupported structure</p></document>".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(ReviewEvidence.inspect(foreign, SOURCE, 0).truncated());
        var deep = ReviewEvidence.inspect(input("<w:r>".repeat(150) + "<w:t>Deep text</w:t>" + "</w:r>".repeat(150), null), SOURCE, 0);
        assertTrue(deep.truncated()); assertTrue(deep.paragraphs().isEmpty());
        String huge = "<w:p><w:r><w:t>" + "x".repeat(2 * 1024 * 1024 + 1) + "</w:t></w:r></w:p>";
        assertTrue(ReviewEvidence.inspect(input(huge, null), SOURCE, 0).truncated());
        var bounded = ReviewEvidence.inspect(input(BODY + "<w:p><w:r><w:t>" + "y".repeat(3000) + "</w:t></w:r></w:p>", null), SOURCE, 0);
        assertTrue(bounded.truncated()); assertTrue(bounded.items().stream().noneMatch(item -> item.mapping().status().equals("matched")));
        assertTrue(bounded.paragraphs().stream().mapToInt(paragraph -> paragraph.text().length()).sum() <= 64 * 1024);
        String longBody = ("<w:p><w:r><w:t>" + "z".repeat(2000) + "</w:t></w:r></w:p>").repeat(40);
        var longReport = ReviewEvidence.inspect(input(longBody, "Essential header retained before body budget"), SOURCE, 0);
        assertTrue(longReport.truncated()); assertTrue(longReport.paragraphs().stream().anyMatch(paragraph -> paragraph.text().contains("Essential header retained")));
        var unicode = ReviewEvidence.inspect(input("<w:p><w:r><w:t>" + "x".repeat(2047) + "😀</w:t></w:r></w:p>", null), SOURCE, 0);
        String clipped = unicode.paragraphs().getFirst().text(); assertTrue(unicode.paragraphs().getFirst().truncated());
        assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length() - 1)), "Truncation must not split an original Unicode character");
    }

    private AppProperties properties() { return new AppProperties(temporary.resolve("jobs").toString(), "", "", 1, 2, 10, 10); }
    private ConversionService service(JobStore store) { return new ConversionService(store, new Tools(properties()), new ToolRunner(), new DocxValidator(), properties(), new DocumentInspector(), new FontSupport()); }
    private Job create(JobStore store) throws IOException {
        Job job = new Job(UUID.randomUUID().toString(), "review.docx", "article", "COMPLETED", "NOT_REQUESTED", "", "", Instant.now(), Instant.now(), 100, 0);
        store.save(job); Path directory = store.directory(job.id()); Files.createDirectories(directory.resolve("output"));
        Files.write(directory.resolve("input.docx"), docx(BODY, "Essential header omitted by conversion"));
        Files.writeString(directory.resolve("output/main.tex"), SOURCE); store.versions().initialize(job, directory, false); return job;
    }

    @Test void decisionsSurviveRestartCarryOnlyUnchangedMappedEvidenceAndRemainSeparateInExport() throws Exception {
        var store = new JobStore(properties()); store.load(); Job job = create(store); var service = service(store);
        try {
            var initial = service.review(job.id()); var caption = initial.items().stream().filter(item -> item.code().equals("captions")).findFirst().orElseThrow();
            var header = initial.items().stream().filter(item -> item.code().equals("headers")).findFirst().orElseThrow();
            service.decide(job.id(), caption.id(), 0, "reviewed", caption.fingerprint());
            var decided = service.decide(job.id(), header.id(), 0, "needs-work", header.fingerprint());
            assertEquals(initial.findings(), decided.findings()); assertEquals(2, decided.decisions().size()); assertTrue(decided.decisions().stream().allMatch(ReviewDecisions.Decision::applicable));
            var restarted = new JobStore(properties()); restarted.load(); var second = service(restarted);
            try {
                assertEquals(decided.decisions(), second.review(job.id()).decisions());
                second.saveSource(job.id(), SOURCE + "\n% unrelated edit\n", 0);
                var advanced = second.review(job.id());
                var carried = advanced.decisions().stream().filter(decision -> decision.findingId().equals(caption.id())).findFirst().orElseThrow();
                assertTrue(carried.applicable()); assertTrue(carried.carried()); assertEquals(1, carried.applicableRevision()); assertEquals(0, carried.revision());
                assertFalse(advanced.decisions().stream().filter(decision -> decision.findingId().equals(header.id())).findFirst().orElseThrow().applicable());
                assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> second.decide(job.id(), caption.id(), 0, "needs-work", caption.fingerprint())).status);
                second.saveSource(job.id(), SOURCE.replace(CAPTION, "Figure 1: Changed caption text"), 1);
                var changed = second.review(job.id()); assertTrue(changed.decisions().stream().noneMatch(ReviewDecisions.Decision::applicable));
                assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> second.decide(job.id(), caption.id(), 2, "reviewed", caption.fingerprint())).status);
                var archive = new ByteArrayOutputStream(); second.writeArchive(job.id(), archive);
                try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive.toByteArray()))) {
                    ZipEntry entry; boolean found = false;
                    while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("conversion-review.json")) {
                        var json = JsonMapper.builder().build().readTree(zip.readAllBytes());
                        assertEquals(2, json.get("revision").asInt()); assertTrue(json.get("findings").size() > 0); assertEquals(2, json.get("decisions").size()); assertTrue(json.get("items").size() > 0); found = true;
                    }
                    assertTrue(found);
                }
            } finally { second.stop(); }
        } finally { service.stop(); }
    }

    @Test void interruptedOrDamagedDecisionMetadataCannotEraseExistingDecisions() throws Exception {
        var store = new JobStore(properties()); store.load(); Job job = create(store); var service = service(store);
        try {
            var finding = service.review(job.id()).items().getFirst(); service.decide(job.id(), finding.id(), 0, "reviewed", finding.fingerprint());
            Path directory = store.directory(job.id()), file = directory.resolve("review-decisions.json"); byte[] original = Files.readAllBytes(file);
            Files.writeString(directory.resolve("review-decisions.json.tmp"), "partial interrupted write");
            assertEquals("reviewed", service.review(job.id()).decisions().getFirst().status());
            Path snapshot = directory.resolve(".versions/0/main.tex"); byte[] savedSource = Files.readAllBytes(snapshot);
            Files.writeString(snapshot, "damaged saved source");
            var unavailable = service.review(job.id()).items().stream().filter(item -> item.id().equals(finding.id())).findFirst().orElseThrow();
            assertThrows(IOException.class, () -> service.decide(job.id(), finding.id(), 0, "needs-work", unavailable.fingerprint()));
            assertArrayEquals(original, Files.readAllBytes(file)); Files.write(snapshot, savedSource);
            Files.writeString(file, "{broken"); var corrupt = service.review(job.id());
            assertFalse(corrupt.decisionError().isBlank()); assertFalse(corrupt.items().isEmpty());
            assertThrows(IOException.class, () -> service.decide(job.id(), finding.id(), 0, "needs-work", finding.fingerprint()));
            assertEquals("{broken", Files.readString(file));
            Files.write(file, original); service.decide(job.id(), finding.id(), 0, "unreviewed", finding.fingerprint());
            assertTrue(service.review(job.id()).decisions().isEmpty()); assertFalse(service.review(job.id()).findings().isEmpty());
        } finally { service.stop(); }
    }
}
