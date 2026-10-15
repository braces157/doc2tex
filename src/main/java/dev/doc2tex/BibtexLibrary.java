package dev.doc2tex;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** A bounded structural BibTeX reader. Values stay literal; no TeX is executed here. */
final class BibtexLibrary {
    record Entry(String key, String type, String title, String author, String year, int line) {}
    record Parsed(List<Entry> entries, List<String> warnings) {}
    private static final Set<String> PLAIN_TYPES = Set.of("article", "book", "booklet", "inbook", "incollection", "inproceedings", "conference", "manual", "mastersthesis", "misc", "phdthesis", "proceedings", "techreport", "unpublished");
    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_][A-Za-z0-9:._+-]{0,127}");
    static boolean validKey(String key) { return key != null && key.length() <= 128 && KEY.matcher(key).matches(); }

    static Parsed parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > BibliographyAssets.MAX_BIB) throw bad("Choose a non-empty .bib file up to 1 MiB.");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException exception) { throw bad("Save the bibliography as UTF-8 and import it again."); }
        if (text.codePoints().anyMatch(point -> Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t')) throw bad("The bibliography contains unsupported control characters.");
        return new Parser(text).parse();
    }

    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private static final class Parser {
        final String text; int index, line = 1; long expanded;
        final List<Entry> entries = new ArrayList<>(); final Set<String> warnings = new LinkedHashSet<>();
        final Map<String, Integer> keys = new LinkedHashMap<>(); final Map<String, String> macros = new LinkedHashMap<>();
        Parser(String text) { this.text = text; }
        Parsed parse() {
            while (index < text.length()) {
                if (peek() == '%') { comment(); continue; }
                if (peek() != '@') { take(); continue; }
                take(); space(); int entryLine = line; String type = identifier().toLowerCase(Locale.ROOT); space();
                char open = take(); if (open != '{' && open != '(') throw error("Expected '{' or '(' after the entry type.");
                char close = open == '{' ? '}' : ')'; space();
                if (type.equals("comment")) { group(open, close); continue; }
                if (type.equals("preamble")) {
                    value(close); space(); if (peek() == ',') { take(); space(); } expect(close);
                    warnings.add("BibTeX @preamble content is preserved and requires manual review."); continue;
                }
                if (type.equals("string")) {
                    String name = identifier().toLowerCase(Locale.ROOT); space(); expect('='); space(); String value = value(close); space();
                    if (peek() == ',') { take(); space(); } expect(close);
                    if (macros.size() >= 2000) throw error("Only 2,000 string macros are supported."); macros.put(name, value); continue;
                }
                int start = index; while (index < text.length() && peek() != ',' && peek() != close && !Character.isWhitespace(peek())) take();
                String key = text.substring(start, index); if (!validKey(key)) throw error("Use citation keys of 1–128 ASCII letters/digits and : . _ + - characters.");
                Integer previous = keys.putIfAbsent(key.toLowerCase(Locale.ROOT), entryLine);
                if (previous != null) throw error("Duplicate citation key '" + key + "' (first defined on line " + previous + "). Rename duplicate keys before importing; the existing bibliography is unchanged.");
                space(); var fields = new LinkedHashMap<String, String>();
                if (peek() == ',') {
                    take(); space();
                    while (peek() != close) {
                        if (fields.size() >= 128) throw error("An entry exceeds 128 fields.");
                        String field = identifier().toLowerCase(Locale.ROOT); space(); expect('='); space(); String value = value(close); space();
                        if (fields.putIfAbsent(field, value) != null) throw error("Repeated field '" + field + "' in citation '" + key + "'.");
                        if (peek() == close) break; expect(','); space();
                    }
                }
                expect(close); if (entries.size() >= 2000) throw error("Import at most 2,000 bibliography entries per project.");
                entries.add(new Entry(key, type, display(fields.get("title")), display(fields.get("author")), display(fields.get("year")), entryLine));
                if (!PLAIN_TYPES.contains(type)) warnings.add("Entry type '" + type + "' may require a custom BibTeX style. The default plain style supports classic BibTeX entries.");
            }
            if (entries.isEmpty()) throw error("No bibliography entries were found. Export a UTF-8 BibTeX library with citation keys.");
            return new Parsed(List.copyOf(entries), warnings.stream().limit(32).toList());
        }

        String value(char entryClose) {
            var result = new StringBuilder(); int terms = 0;
            while (true) {
                if (++terms > 128) throw error("A field exceeds 128 concatenated values.");
                char next = peek(); String term;
                if (next == '{') { take(); term = group('{', '}'); }
                else if (next == '"') { take(); term = quoted(); }
                else {
                    int start = index;
                    while (index < text.length() && !Character.isWhitespace(peek()) && peek() != ',' && peek() != '#' && peek() != entryClose && peek() != '%') take();
                    if (start == index) throw error("A field value is missing or unbalanced.");
                    term = text.substring(start, index);
                    if (!term.matches("(?:[0-9]+|[A-Za-z_][A-Za-z0-9_.:-]{0,63})")) throw error("Put field text in braces or quotes; bare values must be numbers or string macro names.");
                    term = macros.getOrDefault(term.toLowerCase(Locale.ROOT), term);
                }
                expanded += term.length(); if (expanded > 8L * BibliographyAssets.MAX_BIB) throw error("Expanded bibliography values exceed the 8 MiB inspection limit.");
                if (result.length() + term.length() > BibliographyAssets.MAX_BIB) throw error("Expanded string macros exceed the bibliography size limit.");
                result.append(term); space(); if (peek() != '#') return result.toString(); take(); space();
            }
        }
        String group(char open, char close) {
            int start = index, depth = 1;
            while (index < text.length()) {
                char next = take();
                if (next == '\\' && index < text.length()) { take(); continue; }
                if (next == open && ++depth > 128) throw error("Bibliography groups exceed 128 levels.");
                if (next == close && --depth == 0) return text.substring(start, index - 1);
            }
            throw error("Unclosed bibliography group. Check braces/parentheses before importing.");
        }
        String quoted() {
            int start = index, braces = 0;
            while (index < text.length()) {
                char next = take(); if (next == '\\' && index < text.length()) { take(); continue; }
                if (next == '{' && ++braces > 128) throw error("Bibliography groups exceed 128 levels.");
                if (next == '}' && --braces < 0) throw error("Unbalanced braces inside a quoted value.");
                if (next == '"' && braces == 0) return text.substring(start, index - 1);
            }
            throw error("Unclosed quoted bibliography value.");
        }
        String identifier() {
            int start = index; while (index < text.length() && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '-')) take();
            if (start == index || index - start > 64) throw error("A bibliography entry type or field name is invalid.");
            return text.substring(start, index);
        }
        void space() { while (index < text.length()) { if (Character.isWhitespace(peek())) take(); else if (peek() == '%') comment(); else break; } }
        void comment() { while (index < text.length() && take() != '\n') { /* Skip one comment line. */ } }
        char peek() { return index < text.length() ? text.charAt(index) : '\0'; }
        char take() { if (index >= text.length()) throw error("Unexpected end of bibliography."); char next = text.charAt(index++); if (next == '\n') line++; return next; }
        void expect(char value) { if (take() != value) throw error("Expected '" + value + "'. Check the bibliography syntax."); }
        ApiException error(String message) { return bad("Bibliography line " + line + ": " + message); }
        String display(String value) {
            if (value == null) return ""; String display = value.replaceAll("\\s+", " ").strip(); if (display.length() <= 240) return display;
            int end = 239; if (Character.isHighSurrogate(display.charAt(end - 1)) && Character.isLowSurrogate(display.charAt(end))) end--;
            return display.substring(0, end) + "…";
        }
    }
}
