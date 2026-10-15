package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class WordFieldPreservationTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void citationManagerInstructionsReachPandocForComplexAndNormalizedSimpleFields(boolean simple) throws Exception {
        var properties = new AppProperties(directory.toString(), "", "", 1, 2, 30, 30);
        var tools = new Tools(properties); assumeTrue(tools.available(tools.pandoc()));
        String citation = "ADDIN ZOTERO_ITEM CSL_CITATION {\"citationID\":\"synthetic\",\"citationItems\":[{\"id\":\"doe2020\",\"itemData\":{\"id\":\"doe2020\",\"type\":\"book\",\"title\":\"Preserved citation metadata\",\"note\":\"" + "x".repeat(2200)
                + "\",\"author\":[{\"family\":\"Doe\",\"given\":\"Jane\"}],\"issued\":{\"date-parts\":[[2020]]}}}],\"properties\":{\"formattedCitation\":\"(Doe, 2020)\",\"plainCitation\":\"(Doe, 2020)\",\"noteIndex\":0}}";
        String cached = "<w:r><w:t>(Doe, 2020)</w:t></w:r>";
        String field = simple ? "<w:fldSimple w:instr=\"" + citation.replace("\"", "&quot;") + "\">" + cached + "</w:fldSimple>"
                : "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText>" + citation + "</w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" + cached + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
        Path baseline = directory.resolve("baseline.docx"), original = directory.resolve("input.docx"), output = directory.resolve("output");
        Files.write(baseline, SampleDocument.create()); Files.createDirectories(output);
        try (var zip = new ZipFile(baseline.toFile()); var target = new ZipOutputStream(Files.newOutputStream(original))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); target.putNextEntry(new ZipEntry(entry.getName()));
                byte[] bytes; try (var input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
                if (entry.getName().equals("word/document.xml")) bytes = new String(bytes, StandardCharsets.UTF_8).replace("<w:sectPr", "<w:p>" + field + "</w:p><w:sectPr").getBytes(StandardCharsets.UTF_8);
                target.write(bytes); target.closeEntry();
            }
        }
        byte[] uploaded = Files.readAllBytes(original);
        Path converted = WordReferenceSupport.prepare(original, directory, output);
        assertArrayEquals(uploaded, Files.readAllBytes(original));
        try (var zip = new ZipFile(converted.toFile()); var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xml.contains("ADDIN ZOTERO_ITEM CSL_CITATION")); assertFalse(xml.contains("w:fldSimple"));
        }
        var result = new ToolRunner().run(List.of(tools.pandoc(), "--sandbox", "--from=docx+citations", "--to=json", "--standalone", "--output=ast.json", converted.toString()), output, directory.resolve("pandoc.log"), 30);
        assertEquals(0, result.exitCode(), result.log());
        String ast = Files.readString(output.resolve("ast.json"));
        assertEquals(1, Pattern.compile("\"t\"\\s*:\\s*\"Cite\"").matcher(ast).results().count(), ast);
        var reference = JsonMapper.builder().build().readTree(ast).path("meta").path("references").path("c").get(0).path("c");
        assertEquals("doe2020", reference.path("id").path("c").asString());
        assertEquals("MetaInlines", reference.path("title").path("t").asString());
        assertEquals("x".repeat(2200), reference.path("note").path("c").asString());
        var author = reference.path("author").path("c").get(0).path("c");
        assertEquals("Doe", author.path("family").path("c").asString());
        assertEquals("Jane", author.path("given").path("c").asString());
    }
}
