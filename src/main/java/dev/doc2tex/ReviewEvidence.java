package dev.doc2tex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Bounded original-text evidence and conservative, exact text/structure mappings. */
final class ReviewEvidence {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int PART_LIMIT = 2 * 1024 * 1024, TOTAL_LIMIT = 8 * 1024 * 1024;
    private static final int TEXT_LIMIT = 64 * 1024, PARAGRAPH_LIMIT = 500, FINDING_LIMIT = 100;
    private static final Pattern CAPTION = Pattern.compile("(?is)^\\s*(figure|fig\\.?|table)\\s+\\d.*");
    private static final Pattern ENVIRONMENT = Pattern.compile("\\\\(begin|end)\\{(longtable|tabular\\*?|table\\*?|figure\\*?)\\}");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    record Paragraph(String part, String location, String text, boolean truncated) {}
    record Mapping(String status, Integer line, String reason, String sourceExcerpt) {}
    record Item(String id, String code, String title, String part, String location, String excerpt,
                boolean excerptTruncated, Mapping mapping, String fingerprint) {}
    record Extraction(List<Paragraph> paragraphs, List<Item> items, boolean truncated) {}
    private record Original(String id, String code, String title, String part, String location, String text, List<String> anchors, String unavailable) {}
    private record Text(String original, String accepted) {}
    private record Frame(String name, int start) {}
    private record Region(int start, int end, String kind) {}

    static Extraction inspect(Path input, String source, int revision) {
        try { JobStore.checkUnlinked(input); }
        catch (RuntimeException exception) { return new Extraction(List.of(), List.of(), true); }
        var paragraphs = new ArrayList<Paragraph>();
        var originals = new ArrayList<Original>();
        Map<String, Integer> occurrences = new HashMap<>();
        boolean limited = false;
        int bytesRead = 0, textRead = 0;
        try (var zip = new ZipFile(input.toFile())) {
            var parts = zip.stream().filter(entry -> entry.getName().equals("word/document.xml")
                    || entry.getName().matches("word/(header|footer)[^/]*\\.xml"))
                    .sorted(Comparator.comparing(entry -> entry.getName().equals("word/document.xml") ? "" : entry.getName())).limit(33).toList();
            if (parts.stream().noneMatch(entry -> entry.getName().equals("word/document.xml"))) limited = true;
            limited |= parts.size() > 32;
            // Reserve the bounded text budget for omitted header/footer text before long body content.
            for (var part : parts.stream().limit(32).sorted(Comparator.comparing(entry -> entry.getName().equals("word/document.xml") ? "~" : entry.getName())).toList()) {
                if (bytesRead >= TOTAL_LIMIT || paragraphs.size() >= PARAGRAPH_LIMIT || textRead >= TEXT_LIMIT) { limited = true; break; }
                byte[] bytes;
                int limit = Math.min(PART_LIMIT, TOTAL_LIMIT - bytesRead);
                try (var stream = zip.getInputStream(part)) { bytes = stream.readNBytes(limit + 1); }
                bytesRead += bytes.length;
                if (bytes.length > limit) { limited = true; continue; }
                try {
                    var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    factory.setAttribute("jdk.xml.maxElementDepth", "128");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
                    var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
                    boolean header = !part.getName().equals("word/document.xml");
                    Element root = document.getDocumentElement();
                    if (root == null || !W.equals(root.getNamespaceURI()) || (header ? !List.of("hdr", "ftr").contains(root.getLocalName()) : !root.getLocalName().equals("document"))) {
                        limited = true; continue;
                    }
                    var values = new IdentityHashMap<Element, Text>();
                    var nodes = document.getElementsByTagNameNS(W, "p");
                    for (int index = 0; index < nodes.getLength(); index++) {
                        Element paragraph = (Element) nodes.item(index);
                        String original = text(paragraph, true).strip(), accepted = text(paragraph, false).strip();
                        if (original.isBlank()) continue;
                        if (paragraphs.size() == PARAGRAPH_LIMIT || textRead >= TEXT_LIMIT) { limited = true; break; }
                        int remaining = Math.min(2048, TEXT_LIMIT - textRead);
                        String excerpt = clip(original, remaining); boolean truncated = original.length() > remaining;
                        limited |= truncated; textRead += excerpt.length();
                        String location = "paragraph " + (index + 1);
                        paragraphs.add(new Paragraph(part.getName(), location, excerpt, truncated));
                        values.put(paragraph, new Text(original, accepted));
                        if (!header && !accepted.isBlank()) occurrences.merge(normalize(accepted), 1, Integer::sum);
                        List<String> anchors = accepted.isBlank() || truncated ? List.of() : List.of(accepted);
                        if (header) add(originals, "headers", "Header or footer text", part.getName(), location, original, List.of(), "Word headers and footers are omitted from the converted body; no source location is assumed.");
                        if (!header && hasCaptionStyle(paragraph) && CAPTION.matcher(original).matches())
                            add(originals, "captions", "Caption already contains a number", part.getName(), location, original, anchors, null);
                        if (!header && (has(paragraph, "ins") || has(paragraph, "del") || ancestor(paragraph, "ins") || ancestor(paragraph, "del")))
                            add(originals, "revisions", "Tracked changes in original paragraph", part.getName(), location, original, anchors, null);
                    }
                    if (!header) {
                        var tables = document.getElementsByTagNameNS(W, "tbl");
                        for (int index = 0; index < tables.getLength(); index++) {
                            if (index == PARAGRAPH_LIMIT) { limited = true; break; }
                            Element table = (Element) tables.item(index);
                            boolean merged = has(table, "vMerge");
                            var spans = table.getElementsByTagNameNS(W, "gridSpan");
                            for (int span = 0; span < spans.getLength(); span++) if (!"1".equals(((Element) spans.item(span)).getAttributeNS(W, "val"))) merged = true;
                            if (!merged) continue;
                            var texts = new ArrayList<Text>(); var tableParagraphs = table.getElementsByTagNameNS(W, "p");
                            for (int paragraph = 0; paragraph < tableParagraphs.getLength(); paragraph++) {
                                Text value = values.get((Element) tableParagraphs.item(paragraph)); if (value != null) texts.add(value);
                            }
                            String original = String.join(" | ", texts.stream().map(Text::original).toList());
                            boolean nested = has(table, "tbl");
                            List<String> anchors = nested ? List.of() : texts.stream().map(Text::accepted).filter(value -> !value.isBlank() && value.length() <= 2048).sorted(Comparator.comparingInt(String::length).reversed()).limit(3).toList();
                            add(originals, "merged", "Merged table cells", part.getName(), "table " + (index + 1), original.isBlank() ? "[No readable table text within inspection limits]" : original,
                                    anchors, nested ? "Nested table structure cannot be mapped reliably." : null);
                        }
                    }
                } catch (Exception exception) { limited = true; }
            }
        } catch (IOException | RuntimeException exception) { limited = true; }
        limited |= originals.size() > FINDING_LIMIT;
        String searchable = source == null ? null : maskComments(source);
        var items = new ArrayList<Item>();
        for (var original : originals.stream().limit(FINDING_LIMIT).toList()) items.add(map(original, source, searchable, occurrences, revision, limited));
        return new Extraction(List.copyOf(paragraphs), List.copyOf(items), limited);
    }

    private static void add(List<Original> list, String code, String title, String part, String location, String text, List<String> anchors, String unavailable) {
        // Keep extraction memory bounded even for documents with thousands of risky paragraphs.
        if (list.size() > FINDING_LIMIT) return;
        String id = digest("finding-v1|" + code + "|" + part + "|" + location + "|" + text);
        list.add(new Original(id, code, title, part, location, text, anchors, unavailable));
    }

    private static Item map(Original original, String source, String searchable, Map<String, Integer> occurrences, int revision, boolean limited) {
        String reason = original.unavailable(); Region region = null; int position = -1;
        if (reason == null && limited) reason = "Original inspection was limited; source mapping cannot be verified.";
        if (reason == null && source == null) reason = "Saved source is unavailable; no location can be verified.";
        if (reason == null) {
            reason = "No unique exact text match is available; compare this excerpt manually.";
            for (String anchor : original.anchors()) {
                if (occurrences.getOrDefault(normalize(anchor), 0) > 1) { reason = "This original text is repeated; its location is ambiguous."; continue; }
                if (normalize(anchor).length() < 12 || occurrences.getOrDefault(normalize(anchor), 0) != 1) continue;
                String[] words = normalize(escape(anchor)).split(" ");
                String expression = String.join("\\s+", java.util.Arrays.stream(words).map(Pattern::quote).toList());
                var match = Pattern.compile("(?<![\\p{L}\\p{N}_])" + expression + "(?![\\p{L}\\p{N}_])", Pattern.UNICODE_CHARACTER_CLASS).matcher(searchable);
                if (!match.find()) continue;
                int candidate = match.start(), end = match.end();
                if (match.find()) { reason = "This text occurs more than once in the source; a location would be ambiguous."; continue; }
                Region container = container(searchable, candidate);
                if (original.code().equals("merged") && (container == null || container.kind().startsWith("figure"))) { reason = "The text was found without a verified table environment."; continue; }
                region = container == null ? paragraph(source, candidate, end) : container; position = candidate; reason = null; break;
            }
        }
        Mapping mapping = region == null ? new Mapping("unavailable", null, reason, "")
                : new Mapping("matched", 1 + (int) source.substring(0, position).chars().filter(value -> value == '\n').count(), "Unique original anchor and exact source text match; compare structure manually.", clip(source.substring(region.start(), region.end()), 800));
        String evidence = region == null ? "unmapped-revision:" + revision : source.substring(region.start(), region.end());
        return new Item(original.id(), original.code(), original.title(), original.part(), original.location(), clip(original.text(), 1200), original.text().length() > 1200,
                mapping, digest("evidence-v1|" + original.id() + "|" + evidence));
    }

    private static Region container(String source, int position) {
        var matcher = ENVIRONMENT.matcher(source); var stack = new ArrayDeque<Frame>();
        while (matcher.find() && matcher.start() <= position) {
            if (matcher.group(1).equals("begin")) stack.push(new Frame(matcher.group(2), matcher.start()));
            else if (!stack.isEmpty() && stack.peek().name().equals(matcher.group(2))) stack.pop();
            else return null;
        }
        if (stack.isEmpty()) return null;
        Frame frame = stack.peek(); int depth = 0;
        matcher = ENVIRONMENT.matcher(source); matcher.region(frame.start(), source.length());
        while (matcher.find()) if (matcher.group(2).equals(frame.name())) {
            depth += matcher.group(1).equals("begin") ? 1 : -1;
            if (depth == 0) return new Region(frame.start(), matcher.end(), frame.name());
        }
        return null;
    }

    private static Region paragraph(String source, int start, int end) {
        int from = 0, to = source.length(); var breaks = PARAGRAPH_BREAK.matcher(source);
        while (breaks.find()) { if (breaks.end() <= start) from = breaks.end(); else if (breaks.start() >= end) { to = breaks.start(); break; } }
        return new Region(from, to, "paragraph");
    }

    private static String maskComments(String source) {
        char[] chars = source.toCharArray(); boolean comment = false; int slashes = 0;
        for (int index = 0; index < chars.length; index++) {
            char value = chars[index];
            if (value == '\n' || value == '\r') { comment = false; slashes = 0; continue; }
            if (!comment && value == '%' && slashes % 2 == 0) comment = true;
            if (comment) chars[index] = ' ';
            slashes = value == '\\' ? slashes + 1 : 0;
        }
        return new String(chars);
    }

    private static String text(Node node, boolean original) {
        if (node instanceof Element element && W.equals(element.getNamespaceURI())) {
            if (!original && (element.getLocalName().equals("del") || element.getLocalName().equals("delText") || ancestor(element, "del"))) return "";
            if (element.getLocalName().equals("t") || original && element.getLocalName().equals("delText")) return element.getTextContent();
            if (element.getLocalName().equals("tab") || element.getLocalName().equals("br")) return " ";
        }
        StringBuilder result = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) result.append(text(child, original));
        return result.toString();
    }

    private static boolean ancestor(Element element, String name) {
        for (Node node = element.getParentNode(); node instanceof Element parent; node = parent.getParentNode())
            if (W.equals(parent.getNamespaceURI()) && parent.getLocalName().equals(name)) return true;
        return false;
    }
    private static boolean has(Element element, String name) { return element.getElementsByTagNameNS(W, name).getLength() > 0; }
    private static boolean hasCaptionStyle(Element paragraph) {
        var styles = paragraph.getElementsByTagNameNS(W, "pStyle");
        for (int index = 0; index < styles.getLength(); index++) if (((Element) styles.item(index)).getAttributeNS(W, "val").equalsIgnoreCase("caption")) return true;
        return false;
    }
    private static String normalize(String value) { return value.replaceAll("(?U)\\s+", " ").strip(); }
    private static String clip(String value, int length) {
        if (value.length() <= length) return value;
        int end = Math.max(0, length);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value.toCharArray()) result.append(switch (character) {
            case '\\' -> "\\textbackslash{}"; case '{' -> "\\{"; case '}' -> "\\}"; case '$' -> "\\$"; case '&' -> "\\&";
            case '#' -> "\\#"; case '%' -> "\\%"; case '_' -> "\\_"; case '~' -> "\\textasciitilde{}"; case '^' -> "\\textasciicircum{}";
            default -> String.valueOf(character);
        });
        return result.toString();
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
