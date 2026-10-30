package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class AiLayoutServiceTest {
    @TempDir Path directory;

    static byte[] fixture(String paragraphs, boolean columns) throws Exception {
        String xml = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                  xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><w:body>
                """ + paragraphs + SourceLayoutTest.section(12240, 15840, 1440, 1440, 1440, 1440)
                        .replace("</w:sectPr>", columns ? "<w:cols w:num=\"2\" w:space=\"360\"/></w:sectPr>" : "</w:sectPr>")
                + "</w:body></w:document>";
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes); var input = new ZipInputStream(new ByteArrayInputStream(SampleDocument.create()))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals("word/document.xml")) output.write(xml.getBytes(StandardCharsets.UTF_8));
                else input.transferTo(output);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static String paragraph(String text, String properties, String runProperties) {
        return "<w:p>" + properties + "<w:r>" + runProperties + "<w:t xml:space=\"preserve\">"
                + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</w:t></w:r></w:p>";
    }

    @Test void explicitStylesListsDuplicatesAndEquationsConstrainVisualRepairs() throws Exception {
        String body = paragraph("Unique visual heading", "", "")
                + paragraph("Native heading", "<w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>", "")
                + paragraph("List item", "<w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>", "")
                + paragraph("Repeated", "", "") + paragraph("Repeated", "", "")
                + "<w:p><w:r><w:t>Equation </w:t></w:r><m:oMath><m:r><m:t>x=42</m:t></m:r></m:oMath></w:p>";
        Path original = directory.resolve("input.docx"); Files.write(original, fixture(body, true));
        var source = WordLayout.read(original);
        assertEquals(List.of("Unique visual heading"), source.paragraphs().stream().filter(WordLayout.Paragraph::headingCandidate).map(WordLayout.Paragraph::text).toList());
        assertTrue(source.uniformTwoColumns()); assertEquals(new BigDecimal("6.35"), source.columnGapMm());
        assertFalse(source.hasTables());
        Files.write(original, fixture(body, false)); assertFalse(WordLayout.read(original).uniformTwoColumns());
    }

    @Test void missingRuntimeIsVisibleAndOptOutStartsNoAnalysis() throws Exception {
        var settings = new AiLayoutSettings(true, directory.resolve("missing.onnx").toString(), "missing-soffice", 6, 10);
        try (var service = new ServiceResource(new AiLayoutService(settings, new ToolRunner()))) {
            var command = new ArrayList<String>();
            assertNull(service.value.prepare(directory.resolve("not-opened.docx"), directory, directory, LayoutOptions.defaults(), command));
            assertFalse(Files.exists(directory.resolve("ai-layout.json"))); assertTrue(command.isEmpty());
            var report = service.value.prepare(directory.resolve("not-opened.docx"), directory, directory, LayoutOptions.uploadDefaults(), command);
            assertEquals(AiLayoutReport.Status.UNAVAILABLE, report.status());
            assertEquals(report, AiLayoutReport.read(directory)); assertTrue(command.isEmpty());
            assertTrue(report.findings().getFirst().detail().contains("setup-ai-layout"));
            assertFalse(Files.exists(directory.resolve("ai-layout-source")));
        }
    }

    @Test void onlyUniqueParagraphBoundariesCanRestoreExplicitColumnBreaks() throws Exception {
        Path original = directory.resolve("input.docx");
        String split = paragraph("Before break", "", "") + "<w:p><w:r><w:br w:type=\"column\"/></w:r></w:p>" + paragraph("After break", "", "");
        Files.write(original, fixture(split, true));
        var source = WordLayout.read(original); assertTrue(source.uniformTwoColumns()); assertEquals(List.of("After break"), source.columnStarts());
        Files.write(original, fixture("<w:p><w:r><w:br w:type=\"column\"/><w:t>After break</w:t></w:r></w:p>", true));
        assertEquals(List.of("After break"), WordLayout.read(original).columnStarts()); assertTrue(WordLayout.read(original).uniformTwoColumns());
        Files.write(original, fixture("<w:p><w:r><w:t>Before break</w:t><w:br w:type=\"column\"/></w:r></w:p>" + paragraph("After break", "", ""), true));
        assertEquals(List.of("After break"), WordLayout.read(original).columnStarts()); assertTrue(WordLayout.read(original).uniformTwoColumns());
        Files.write(original, fixture(split + paragraph("After break", "", ""), true));
        assertFalse(WordLayout.read(original).uniformTwoColumns(), "Ambiguous break targets must not guess a column start");
        Files.write(original, fixture("<w:p><w:r><w:t>Before</w:t><w:br w:type=\"column\"/><w:t>After</w:t></w:r></w:p>", true));
        assertFalse(WordLayout.read(original).uniformTwoColumns(), "Inline column breaks require manual layout review");
    }

    @Test void failedRendererPreservesStandardConversionArguments() throws Exception {
        Path model = directory.resolve("inference.onnx"); Files.writeString(model, "corrupt model is never loaded if rendering fails");
        var settings = new AiLayoutSettings(true, model.toString(), ProcessHandle.current().info().command().orElseThrow(), 6, 10);
        Path original = directory.resolve("input.docx"); Files.write(original, fixture(paragraph("Original source", "", ""), false));
        try (var service = new ServiceResource(new AiLayoutService(settings, new ToolRunner()))) {
            var command = new ArrayList<>(List.of("pandoc", "--from=docx"));
            var report = service.value.prepare(original, directory, directory, LayoutOptions.uploadDefaults(), command);
            assertEquals(AiLayoutReport.Status.FAILED, report.status()); assertEquals(List.of("pandoc", "--from=docx"), command);
            assertEquals(report, AiLayoutReport.read(directory));
            assertTrue(Files.readString(directory.resolve("ai-layout-source/profile/user/registrymodifications.xcu")).contains("DisableMacrosExecution"));
        }
    }

    @Test void headingFilterKeepsNativeHeadingsDuplicatesAndLiteralText() throws Exception {
        var properties = new AppProperties(directory.toString(), "", "", 1, 2, 30, 30);
        var tools = new Tools(properties); assumeTrue(tools.available(tools.pandoc()));
        String unique = "Unique heading A&B_#% \\input{AI_SENTINEL}";
        Path original = directory.resolve("input.docx");
        String multilingual = "Multilingual heading ภาษาไทย中文";
        Files.write(original, fixture(paragraph(unique, "", "") + paragraph("Repeated", "", "") + paragraph("Repeated", "", "")
                + paragraph("Native heading", "<w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>", "") + paragraph("BODY_SENTINEL", "", "")
                + paragraph(multilingual, "", ""), false));
        var headings = List.of(new AiLayoutReport.Heading(1, 1, unique, .95f), new AiLayoutReport.Heading(2, 1, "Repeated", .95f),
                new AiLayoutReport.Heading(4, 1, "Native heading", .95f), new AiLayoutReport.Heading(6, 1, multilingual, .95f));
        Files.write(directory.resolve("ai-layout-plan.json"), JsonMapper.builder().build().writeValueAsBytes(Map.of("headings", headings, "columnStarts", List.of("BODY_SENTINEL", "Repeated"))));
        try (var input = new ClassPathResource("ai-layout.lua").getInputStream()) { Files.copy(input, directory.resolve("ai-layout.lua")); }
        var command = new ArrayList<>(List.of(tools.pandoc(), "--sandbox", "--from=docx", "--to=latex", "--standalone", "--lua-filter=ai-layout.lua", "--output=main.tex", original.toString()));
        new FontSupport().prepare(new DocumentInspector().review(original, null), directory, command, "");
        var result = new ToolRunner().run(command, directory, directory.resolve("conversion.log"), 30);
        assertEquals(0, result.exitCode(), result.log());
        String source = Files.readString(directory.resolve("main.tex"));
        assertTrue(source.contains("\\section{Unique heading"), source); assertTrue(source.contains("BODY\\_SENTINEL"), source);
        assertTrue(source.contains("\\section{\\texorpdfstring{Multilingual heading"), source);
        assertFalse(source.contains("\\input{AI_SENTINEL}")); assertFalse(source.contains("\\section{Repeated}"));
        var applied = JsonMapper.builder().build().readValue(Files.readAllBytes(directory.resolve("ai-layout-applied.json")), AiLayoutService.Applied.class);
        assertEquals(List.of(unique, multilingual), applied.headings()); assertEquals(List.of("BODY_SENTINEL"), applied.columnBreaks());
        assertTrue(source.indexOf("\\newpage") < source.indexOf("BODY\\_SENTINEL"));
    }

    private record ServiceResource(AiLayoutService value) implements AutoCloseable { @Override public void close() throws Exception { value.close(); } }
}
