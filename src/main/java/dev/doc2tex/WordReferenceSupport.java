package dev.doc2tex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.json.JsonMapper;

/** Normalizes a conversion copy only; explicit field relationships remain distinct from prose. */
final class WordReferenceSupport {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int XML_LIMIT = 8 * 1024 * 1024, FIELD_LIMIT = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern REF = Pattern.compile("(?i)^REF\\s+([^\\s]+)(.*)$");
    record Field(String id, String target, String cached, boolean supported, String reason) {}
    record Input(Map<String, Integer> bookmarks, List<Field> fields, List<String> warnings) {}
    record Caption(String label, String kind, String prefix, String number) {}
    record Report(List<Caption> captions, List<String> warnings, int convertedReferences) {}
    private static final class Frame {
        final StringBuilder instruction = new StringBuilder(), cached = new StringBuilder();
        boolean result, nested, limited;
        Node begin, separate, end;
        boolean complexResult;
    }

    static Path prepare(Path original, Path directory, Path output) throws IOException {
        JobStore.checkUnlinked(original);
        var warnings = new ArrayList<String>(); Map<String, Integer> bookmarks = new LinkedHashMap<>(); var fields = new ArrayList<Field>();
        Path input = original; byte[] replacement = null;
        try (var zip = new ZipFile(original.toFile())) {
            var entry = zip.getEntry("word/document.xml");
            if (entry == null) throw new IOException("The document body is missing.");
            byte[] xml;
            try (var stream = zip.getInputStream(entry)) { xml = stream.readNBytes(XML_LIMIT + 1); }
            if (xml.length > XML_LIMIT) throw new IOException("Word field inspection exceeds its 8 MiB limit.");
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("jdk.xml.maxElementDepth", "128"); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            if (!W.equals(document.getDocumentElement().getNamespaceURI())) throw new IOException("Unsupported Word document namespace.");
            boolean changed = normalizeSimpleFields(document);
            changed |= collect(document, bookmarks, fields, warnings);
            if (changed) {
                var transformerFactory = TransformerFactory.newInstance(); transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                var transformer = transformerFactory.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                var bytes = new ByteArrayOutputStream(); transformer.transform(new DOMSource(document), new StreamResult(bytes)); replacement = bytes.toByteArray();
            }
            var other = zip.entries();
            while (other.hasMoreElements()) {
                var part = other.nextElement();
                if (part.getName().matches("word/(header|footer|footnotes|endnotes)[^/]*\\.xml")) {
                    byte[] bytes;
                    try (var stream = zip.getInputStream(part)) { bytes = stream.readNBytes(256 * 1024 + 1); }
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (bytes.length > 256 * 1024 || text.contains("instrText") || text.contains("fldSimple")) {
                        warnings.add("Word fields in headers, footers or notes require manual review; automatic references cover the document body only."); break;
                    }
                }
            }
        } catch (Exception exception) {
            replacement = null; bookmarks.clear(); fields.clear();
            warnings.add("Word reference inspection was limited. Compare fields and cached text with the original; no reference relationship is inferred.");
        }
        if (replacement != null) {
            input = directory.resolve("conversion-input.docx"); JobStore.checkUnlinked(input);
            try (var zip = new ZipFile(original.toFile()); var target = new ZipOutputStream(Files.newOutputStream(input))) {
                var entries = zip.entries(); long expanded = 0; int count = 0;
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (++count > 2000) throw new IOException("The conversion copy has too many archive entries.");
                    target.putNextEntry(new ZipEntry(entry.getName()));
                    if (entry.getName().equals("word/document.xml")) { expanded += replacement.length; if (expanded > 100L * 1024 * 1024) throw new IOException("The conversion copy exceeds 100 MiB."); target.write(replacement); }
                    else try (var stream = zip.getInputStream(entry)) {
                        byte[] buffer = new byte[8192]; int read;
                        while ((read = stream.read(buffer)) != -1) { expanded += read; if (expanded > 100L * 1024 * 1024) throw new IOException("The conversion copy exceeds 100 MiB."); target.write(buffer, 0, read); }
                    }
                    target.closeEntry();
                }
            }
        }
        Files.writeString(output.resolve("word-reference-input.json"), JSON.writeValueAsString(new Input(bookmarks, fields, warnings)), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("caption-references.tex"), "\\providecommand{\\DocCaptionNumber}[3]{#1~#2#3}\n", StandardCharsets.UTF_8);
        try (var resource = new ClassPathResource("caption-references.lua").getInputStream()) {
            Files.copy(resource, output.resolve("caption-references.lua"), StandardCopyOption.REPLACE_EXISTING);
        }
        return input;
    }

    static void configure(List<String> command) {
        command.add("--include-in-header=caption-references.tex"); command.add("--lua-filter=caption-references.lua");
    }

    private static boolean normalizeSimpleFields(Document document) {
        var simple = document.getElementsByTagNameNS(W, "fldSimple"); boolean changed = simple.getLength() > 0;
        if (simple.getLength() > 2000) throw new IllegalArgumentException("Too many simple Word fields to normalize safely.");
        for (int index = simple.getLength() - 1; index >= 0; index--) {
            Element field = (Element) simple.item(index); Node parent = field.getParentNode();
            parent.insertBefore(fieldRun(document, "begin", null), field);
            parent.insertBefore(fieldRun(document, null, field.getAttributeNS(W, "instr")), field);
            parent.insertBefore(fieldRun(document, "separate", null), field);
            for (Node child = field.getFirstChild(); child != null; child = child.getNextSibling()) parent.insertBefore(child.cloneNode(true), field);
            parent.insertBefore(fieldRun(document, "end", null), field); parent.removeChild(field);
        }
        return changed;
    }

    private static Element fieldRun(Document document, String type, String instruction) {
        Element run = document.createElementNS(W, "w:r"), value = document.createElementNS(W, type == null ? "w:instrText" : "w:fldChar");
        if (type == null) { value.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve"); value.setTextContent(instruction); }
        else value.setAttributeNS(W, "w:fldCharType", type);
        run.appendChild(value); return run;
    }

    private static boolean collect(Document document, Map<String, Integer> bookmarks, List<Field> fields, List<String> warnings) {
        var nodes = new ArrayDeque<Node>(); var stack = new ArrayDeque<Frame>(); var completed = new ArrayList<Frame>();
        var titles = new java.util.HashSet<String>(); nodes.push(document.getDocumentElement()); boolean boundaries = true;
        while (!nodes.isEmpty()) {
            Node node = nodes.pop();
            if (node instanceof Element element && !stack.isEmpty() && stack.peek().result && !W.equals(element.getNamespaceURI())) stack.peek().complexResult = true;
            if (node instanceof Element element && W.equals(element.getNamespaceURI())) {
                String name = element.getLocalName();
                if (!stack.isEmpty() && stack.peek().result && List.of("drawing", "pict", "object", "br", "tab", "sym", "delText").contains(name)) stack.peek().complexResult = true;
                if (name.equals("hyperlink")) titles.add(element.getAttributeNS(W, "tooltip"));
                if (name.equals("bookmarkStart")) {
                    String bookmark = element.getAttributeNS(W, "name");
                    if ((bookmarks.containsKey(bookmark) || bookmarks.size() < 1000) && validBookmark(bookmark)) bookmarks.merge(bookmark, 1, Integer::sum);
                    else warn(warnings, "Some Word bookmark names or counts exceed supported limits; verify their references manually.");
                }
                if (name.equals("fldChar")) {
                    String type = element.getAttributeNS(W, "fldCharType");
                    if (type.equals("begin")) { if (!stack.isEmpty()) stack.peek().nested = true; var frame = new Frame(); frame.nested = !stack.isEmpty(); frame.begin = element.getParentNode(); stack.push(frame); }
                    else if (type.equals("separate")) { if (stack.isEmpty()) boundaries = false; else { stack.peek().result = true; stack.peek().separate = element.getParentNode(); } }
                    else if (type.equals("end")) { if (stack.isEmpty()) boundaries = false; else { var frame = stack.pop(); frame.end = element.getParentNode(); completed.add(frame); } }
                } else if (!stack.isEmpty() && name.equals("instrText") && !stack.peek().result) append(stack.peek(), false, element.getTextContent());
                else if (!stack.isEmpty() && name.equals("t") && stack.peek().result) append(stack.peek(), true, element.getTextContent());
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPreviousSibling()) nodes.push(child);
        }
        if (!stack.isEmpty()) boundaries = false;
        if (!boundaries) {
            warn(warnings, "Word field boundaries are incomplete; verify field results manually.");
        }
        boolean changed = false;
        for (var frame : completed) changed |= addField(frame, fields, warnings, titles, boundaries);
        fields.replaceAll(field -> field.supported() && bookmarks.getOrDefault(field.target(), 0) != 1
                ? new Field(field.id(), field.target(), field.cached(), false, "The Word bookmark target is missing or duplicated.") : field);
        return changed;
    }

    private static void append(Frame frame, boolean result, String text) {
        StringBuilder target = result ? frame.cached : frame.instruction; int limit = result ? 512 : 2048;
        if (target.length() + text.length() > limit) frame.limited = true;
        if (target.length() < limit) {
            int end = Math.min(text.length(), limit - target.length());
            if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
            target.append(text, 0, end);
        }
    }

    private static boolean addField(Frame frame, List<Field> fields, List<String> warnings, java.util.Set<String> titles, boolean boundaries) {
        String instruction = frame.instruction.toString().strip(), cached = frame.cached.toString().strip();
        String command = instruction.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (List.of("SEQ", "HYPERLINK").contains(command)) return false;
        if (fields.size() >= FIELD_LIMIT) { warn(warnings, "Only the first 200 Word reference fields were inspected."); return false; }
        var match = REF.matcher(instruction); String target = "", reason = "Unsupported Word field" + (command.matches("[A-Z]{1,32}") ? ": " + command : "") + ". Cached text requires manual review.";
        boolean supported = false;
        if (match.matches()) {
            target = match.group(1);
            String switches = match.group(2).strip().replaceAll("(?i)\\\\\\*\\s+MERGEFORMAT", "").replaceAll("(?i)\\\\h", "").strip();
            supported = boundaries && !frame.nested && !frame.limited && !frame.complexResult && switches.isEmpty() && validBookmark(target) && cached.matches("[0-9]+(?:\\.[0-9]+)*");
            reason = supported ? "" : "Only non-nested numeric REF fields with supported switches and a caption-owned bookmark are automatic.";
        } else if (command.equals("PAGEREF") || command.equals("NOTEREF")) {
            String[] words = instruction.split("\\s+"); if (words.length > 1) target = words[1];
        }
        String marker = "doc2tex-ref-field-" + (fields.size() + 1);
        while (titles.contains(marker)) marker += "-x";
        boolean changed = boundaries && !frame.nested && rewrite(frame, target, marker);
        if (!changed) { marker = ""; supported = false; reason = "The Word field structure cannot be rewritten safely; compare its cached text manually."; }
        titles.add(marker); fields.add(new Field(marker, target, cached, supported, reason));
        return changed;
    }

    private static boolean rewrite(Frame frame, String target, String marker) {
        if (frame.begin == null || frame.separate == null || frame.end == null) return false;
        if (!controlRun(frame.begin) || !controlRun(frame.separate) || !controlRun(frame.end)) return false;
        Node parent = frame.begin.getParentNode();
        if (!(parent instanceof Element paragraph) || !W.equals(paragraph.getNamespaceURI()) || !paragraph.getLocalName().equals("p")
                || frame.separate.getParentNode() != parent || frame.end.getParentNode() != parent) return false;
        var result = new ArrayList<Node>(); boolean afterSeparator = false, foundEnd = false;
        for (Node node = frame.begin; node != null; node = node.getNextSibling()) {
            if (node == frame.end) { foundEnd = true; break; }
            if (node == frame.separate) { afterSeparator = true; continue; }
            if (afterSeparator) {
                if (!(node instanceof Element run) || !W.equals(run.getNamespaceURI()) || !run.getLocalName().equals("r")) return false;
                result.add(node.cloneNode(true));
            } else if (node instanceof Element ? !controlRun(node) : node.getNodeType() != Node.TEXT_NODE || !node.getTextContent().isBlank()) return false;
        }
        if (!foundEnd) return false;
        Document document = paragraph.getOwnerDocument();
        if (validBookmark(target)) {
            Element link = document.createElementNS(W, "w:hyperlink"); link.setAttributeNS(W, "w:anchor", target); link.setAttributeNS(W, "w:tooltip", marker);
            result.forEach(link::appendChild); parent.insertBefore(link, frame.begin);
        } else for (Node node : result) parent.insertBefore(node, frame.begin);
        Node current = frame.begin;
        while (current != null) { Node next = current.getNextSibling(); parent.removeChild(current); if (current == frame.end) break; current = next; }
        return true;
    }
    private static boolean validBookmark(String name) { return name.matches("[\\p{L}_][\\p{L}\\p{N}_.-]{0,127}"); }
    private static boolean controlRun(Node node) {
        if (!(node instanceof Element run) || !W.equals(run.getNamespaceURI()) || !run.getLocalName().equals("r")) return false;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element element
                && (!W.equals(element.getNamespaceURI()) || !List.of("rPr", "fldChar", "instrText").contains(element.getLocalName()))) return false;
        return true;
    }
    private static void warn(List<String> warnings, String message) { if (warnings.size() < 32 && !warnings.contains(message)) warnings.add(message); }

    static Report report(Path output) throws IOException {
        Path file = output.resolve("word-reference-report.json"); JobStore.checkUnlinked(file);
        if (!Files.isRegularFile(file)) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(512 * 1024 + 1); if (bytes.length > 512 * 1024) throw new IOException("Reference report exceeds its limit.");
            Report report = JSON.readValue(bytes, Report.class);
            if (report == null || report.captions() == null || report.captions().size() > 200 || report.warnings() == null || report.warnings().size() > 64
                    || report.convertedReferences() < 0 || report.convertedReferences() > FIELD_LIMIT) throw new IOException("Reference report metadata is invalid.");
            if (report.warnings().stream().anyMatch(warning -> warning == null || warning.length() > 4096)) throw new IOException("Reference warnings are invalid.");
            for (var caption : report.captions()) if (caption == null || caption.label() == null || !caption.label().matches("doc2tex-(figure|table)-[a-z0-9-]{1,1200}")
                    || caption.kind() == null || !List.of("figure", "table").contains(caption.kind()) || caption.prefix() == null || caption.prefix().length() > 256 || caption.number() == null || caption.number().length() > 40)
                throw new IOException("Caption reference metadata is invalid.");
            return report;
        }
    }

    static List<String> warnings(Path original, Path output) {
        try {
            var report = report(output); if (report != null) return report.warnings();
            JobStore.checkUnlinked(original);
            try (var zip = new ZipFile(original.toFile())) {
                var body = zip.getEntry("word/document.xml"); if (body == null) return List.of();
                try (var input = zip.getInputStream(body)) {
                    byte[] bytes = input.readNBytes(XML_LIMIT + 1);
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (bytes.length > XML_LIMIT || text.contains("instrText") || text.contains("fldSimple"))
                        return List.of("This older conversion did not verify Word field relationships. Compare cached references with the original; reconvert the original to use structural reference support.");
                }
            }
            return List.of();
        } catch (IOException | RuntimeException exception) { return List.of("Caption/reference metadata could not be read. Compare fields and numbering with the original Word document."); }
    }
}
