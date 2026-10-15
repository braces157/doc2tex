package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BibtexLibraryTest {
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void importsLiteralUtf8NestedQuotedAndMacroValuesWithoutInventingMissingFields() throws Exception {
        byte[] fixture; try (var input = getClass().getResourceAsStream("/bibliography/two-entries.bib")) { assertNotNull(input); fixture = input.readAllBytes(); }
        var parsed = BibtexLibrary.parse(fixture); assertEquals(2, parsed.entries().size());
        assertEquals("Synthetic Unicode Résumé", parsed.entries().getFirst().title()); assertEquals("Synthetic Проверка", parsed.entries().getLast().title());
        var values = BibtexLibrary.parse(bytes("\uFEFF% Synthetic comment\n@comment{not an entry {nested}}\n@string{prefix = \"Nested \"}\n"
                + "@book(key_1, title = prefix # {A {protected} \\{literal\\} title}, author=\"Doe, {Jane}\", year=2024,)\n"
                + "@misc{empty}\n@preamble{\"\\newcommand{\\Example}{value}\"}"));
        assertEquals("Nested A {protected} \\{literal\\} title", values.entries().getFirst().title());
        assertEquals("Doe, {Jane}", values.entries().getFirst().author()); assertEquals("2024", values.entries().getFirst().year());
        assertEquals("", values.entries().getLast().title()); assertEquals("", values.entries().getLast().year());
        assertTrue(values.warnings().stream().anyMatch(warning -> warning.contains("@preamble")));
    }

    @Test void malformedDuplicateUnsafeAndOversizeInputsHaveActionableErrors() {
        for (String input : java.util.List.of("", "not a bibliography", "@book{key,title={unclosed}", "@book{key,title=\"unclosed}",
                "@book{bad/key,title={unsafe}}", "@book{key,title={one},title={two}}", "@book{key,title=unbalanced{text}",
                "@book{key,title={one}}\n@article{KEY,title={two}}", "@book{key,title={" + "{".repeat(130) + "x" + "}".repeat(132), "@book{key,title={bad\u0000text}}"))
            assertThrows(ApiException.class, () -> BibtexLibrary.parse(bytes(input)), input);
        var duplicate = assertThrows(ApiException.class, () -> BibtexLibrary.parse(bytes("@book{duplicate,title={one}}\n@book{duplicate,title={two}}")));
        assertTrue(duplicate.getMessage().contains("Duplicate citation key 'duplicate'")); assertTrue(duplicate.getMessage().contains("line 1"));
        assertThrows(ApiException.class, () -> BibtexLibrary.parse(new byte[BibliographyAssets.MAX_BIB + 1]));
        assertTrue(assertThrows(ApiException.class, () -> BibtexLibrary.parse(new byte[]{(byte) 0xc0, (byte) 0xff})).getMessage().contains("UTF-8"));
        String explosive = "@string{a={" + "x".repeat(1024) + "}}\n@string{b=" + String.join("#", java.util.Collections.nCopies(128, "a")) + "}\n"
                + String.join("\n", java.util.stream.IntStream.range(0, 100).mapToObj(number -> "@book{k" + number + ",title=b}").toList());
        assertTrue(assertThrows(ApiException.class, () -> BibtexLibrary.parse(bytes(explosive))).getMessage().contains("inspection limit"));
        String many = String.join("\n", java.util.stream.IntStream.range(0, 2001).mapToObj(number -> "@misc{k" + number + "}").toList());
        assertTrue(assertThrows(ApiException.class, () -> BibtexLibrary.parse(bytes(many))).getMessage().contains("2,000"));
    }
}
