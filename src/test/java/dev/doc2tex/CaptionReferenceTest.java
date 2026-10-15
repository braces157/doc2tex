package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptionReferenceTest {
    @TempDir Path temporary;
    private AppProperties properties() { return new AppProperties(temporary.toString(), "", "", 1, 2, 30, 300); }

    private Path convert(String template) throws Exception {
        return convert(template, false);
    }

    private Path convert(String template, boolean collision) throws Exception {
        var tools = new Tools(properties()); assumeTrue(tools.available(tools.pandoc()), "Install Pandoc for structural reference tests");
        Path directory = temporary.resolve(template + (collision ? "-collision" : "")); Path output = directory.resolve("output"); Files.createDirectories(output);
        Path original = directory.resolve("input.docx");
        try (var input = getClass().getResourceAsStream("/corpus/caption-references.docx")) { assertNotNull(input); Files.copy(input, original); }
        if (collision) {
            String reserved = "doc2tex-figure-" + java.util.HexFormat.of().formatHex("_RefFigureAlpha".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var archive = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipFile(original.toFile()); var target = new java.util.zip.ZipOutputStream(archive)) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement(); target.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    byte[] content; try (var input = zip.getInputStream(entry)) { content = input.readAllBytes(); }
                    if (entry.getName().equals("word/document.xml")) {
                        String paragraph = "<w:p><w:bookmarkStart w:id=\"900\" w:name=\"" + reserved + "\"/><w:r><w:t>Reserved label anchor</w:t></w:r><w:bookmarkEnd w:id=\"900\"/><w:hyperlink w:anchor=\"" + reserved + "\"><w:r><w:t>Ordinary internal link</w:t></w:r></w:hyperlink></w:p>";
                        content = new String(content, java.nio.charset.StandardCharsets.UTF_8).replace("<w:sectPr", paragraph + "<w:sectPr").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    target.write(content); target.closeEntry();
                }
            }
            Files.write(original, archive.toByteArray());
        }
        byte[] bytes = Files.readAllBytes(original);
        Path conversion = WordReferenceSupport.prepare(original, directory, output);
        assertNotEquals(original, conversion); assertArrayEquals(bytes, Files.readAllBytes(original));
        var command = new ArrayList<>(List.of(tools.pandoc(), "--sandbox", "--from=docx", "--to=latex", "--standalone", "--wrap=none", "--extract-media=media",
                "--metadata=date:", "--variable=documentclass:" + template, "--output=main.tex", conversion.toString()));
        WordReferenceSupport.configure(command);
        var result = new ToolRunner().run(command, output, directory.resolve("conversion.log"), 30);
        assertEquals(0, result.exitCode(), result.log()); return output;
    }

    @Test void explicitNumericFieldsUseCaptionLabelsWhileUnsupportedFieldsRetainCachedText() throws Exception {
        Path output = convert("article"); String source = Files.readString(output.resolve("main.tex"));
        var report = WordReferenceSupport.report(output); assertNotNull(report); assertEquals(3, report.convertedReferences()); assertEquals(3, report.captions().size());
        String alpha = "doc2tex-figure-" + java.util.HexFormat.of().formatHex("_RefFigureAlpha".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String beta = "doc2tex-figure-" + java.util.HexFormat.of().formatHex("_RefFigureBeta".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String table = "doc2tex-table-" + java.util.HexFormat.of().formatHex("_RefTableAlpha".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String label : List.of(alpha, beta, table)) { assertTrue(source.contains("\\label{" + label + "}"), source); assertTrue(source.contains("\\ref{" + label + "}"), source); }
        assertTrue(source.contains("\\DocCaptionNumber{Figure}{1}{}"), source); assertTrue(source.contains("\\DocCaptionNumber{Figure}{2}{}"), source);
        assertTrue(source.contains("UNSUPPORTED\\_PAGE\\_REFERENCE=1."), source);
        assertTrue(source.contains("UNRESOLVED\\_REFERENCE=UNRESOLVED\\_REFERENCE."), source);
        assertTrue(source.contains("AMBIGUOUS\\_OWNER\\_REFERENCE=3."), source);
        assertFalse(source.contains("doc2tex-ref-field-"), source);
        assertTrue(source.contains("see Figure 1 and Table 2."));
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("PAGEREF")));
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("missing") || warning.contains("caption owner")));
    }

    @Test void repairScannerChangesOnlyLiteralNumberedCaptionsAndRejectsStalePlans() {
        String source = "\\begin{document}\nOrdinary prose: Figure 1 and see Table 2.\n"
                + "\\begin{figure}\n\\caption{Figure 1: A {nested} caption \\& symbols}\n\\end{figure}\n"
                + "\\begin{longtable}{ll}\n\\caption{Table 2 Measured results}\\tabularnewline\nA & B \\\\\n\\end{longtable}\n"
                + "\\begin{verbatim}\n\\begin{figure}\n\\caption{Figure 9 Literal code}\n\\end{figure}\n\\end{verbatim}\n"
                + "\\begin{figure}\n% \\caption{Figure 4 Comment}\n\\caption*{Figure 3 Unnumbered}\n\\caption[Figure 2 short]{Figure 2 Optional caption}\n\\end{figure}\n\\end{document}";
        var plan = CaptionRepairs.preview(source, 2, List.of()); assertEquals(2, plan.proposals().size(), plan.toString());
        String changed = CaptionRepairs.apply(source, 2, plan.sourceHash(), plan.proposals().stream().map(CaptionRepairs.Proposal::id).toList(), List.of());
        assertTrue(changed.contains("\\caption{A {nested} caption \\& symbols}")); assertTrue(changed.contains("\\caption{Measured results}"));
        assertTrue(changed.contains("Ordinary prose: Figure 1 and see Table 2.")); assertTrue(changed.contains("\\caption{Figure 9 Literal code}"));
        assertTrue(changed.contains("\\caption*{Figure 3 Unnumbered}")); assertTrue(changed.contains("\\caption[Figure 2 short]{Figure 2 Optional caption}"));
        assertFalse(plan.warnings().isEmpty());
        assertThrows(ApiException.class, () -> CaptionRepairs.apply(source + "\n% changed", 2, plan.sourceHash(), List.of(plan.proposals().getFirst().id()), List.of()));
        assertThrows(ApiException.class, () -> CaptionRepairs.apply(source, 2, plan.sourceHash(), List.of("unknown"), List.of()));
        assertTrue(CaptionRepairs.preview(source + "\n{", 2, List.of()).proposals().isEmpty());
    }

    @Test void captionLabelsAvoidOriginalBookmarkCollisionsAndRemainStableAcrossConversions() throws Exception {
        String reserved = "doc2tex-figure-" + java.util.HexFormat.of().formatHex("_RefFigureAlpha".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path output = convert("article", true); var first = WordReferenceSupport.report(output);
        String source = Files.readString(output.resolve("main.tex"));
        assertTrue(source.contains("\\label{" + reserved + "}"), source);
        assertTrue(source.contains("\\label{" + reserved + "-collision-1}"), source);
        assertTrue(source.contains("\\ref{" + reserved + "-collision-1}"), source);
        assertTrue(source.contains("Ordinary internal link"), source);
        var second = WordReferenceSupport.report(convert("report", true));
        assertEquals(first.captions().stream().map(WordReferenceSupport.Caption::label).toList(), second.captions().stream().map(WordReferenceSupport.Caption::label).toList());
        assertEquals(3, first.convertedReferences());
    }

    @ParameterizedTest @ValueSource(strings = {"article", "report"})
    void acceptedRepairsProduceOneCaptionNumberAndCorrectExplicitPdfReferences(String template) throws Exception {
        var tools = new Tools(properties()); assumeTrue(Boolean.getBoolean("doc2tex.test-pdf")); assumeTrue(tools.available(tools.tectonic()));
        Path output = convert(template); String original = Files.readString(output.resolve("main.tex"));
        assertTrue(original.contains("]{" + template + "}"), original);
        var plan = CaptionRepairs.preview(original, 0, WordReferenceSupport.report(output).warnings());
        assertEquals(3, plan.proposals().size(), plan.toString());
        String repaired = CaptionRepairs.apply(original, 0, plan.sourceHash(), plan.proposals().stream().map(CaptionRepairs.Proposal::id).toList(), plan.warnings());
        assertTrue(repaired.contains("see Figure 1 and Table 2."));
        assertEquals(1, Pattern.compile(Pattern.quote("\\DocCaptionNumber")).matcher(repaired).results().count(), "Only the identity definition should remain");
        Files.writeString(output.resolve("main.tex"), repaired);
        var result = new ToolRunner().run(List.of(tools.tectonic(), "--untrusted", "--color", "never", "--outdir", output.toString(), "main.tex"), output, output.resolveSibling("compiler.log"), 300);
        assertEquals(0, result.exitCode(), result.log());
        try (var pdf = Loader.loadPDF(output.resolve("main.pdf").toFile())) {
            String text = new PDFTextStripper().getText(pdf), compact = text.replaceAll("\\s+", "");
            String alpha = "1", beta = "2"; // Both templates omit a chapter prefix before the first chapter.
            assertTrue(compact.contains("REFERENCE_FIGURE_ALPHA=" + alpha + "."), text);
            assertTrue(compact.contains("REFERENCE_FIGURE_BETA=" + beta + "."), text);
            assertTrue(compact.contains("REFERENCE_TABLE_ALPHA=" + alpha + "."), text);
            assertTrue(compact.contains("Figure" + alpha + ":Repeatedsensorobservations"), text);
            assertTrue(compact.contains("Figure" + beta + ":Repeatedsensorobservations"), text);
            assertFalse(compact.contains("Figure" + alpha + ":Figure"), text);
            Path evidence = Path.of(System.getProperty("doc2tex.build-directory", "target"), "fidelity-evidence", "caption-repairs", template);
            Files.createDirectories(evidence); Files.writeString(evidence.resolve("before.tex"), original); Files.writeString(evidence.resolve("after.tex"), repaired);
            Files.writeString(evidence.resolve("pdf-text.txt"), text); Files.copy(output.resolve("main.pdf"), evidence.resolve("main.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
