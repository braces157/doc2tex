package dev.doc2tex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Inspects source features, without claiming a lossless conversion. Never resolves external XML. */
@Component
class DocumentInspector {
    record Finding(String code, String severity, String title, String detail) {}
    record Review(Map<String, Integer> elements, List<Finding> findings, List<String> compilerWarnings, List<String> scripts, String scope,
                  Integer revision, List<ReviewEvidence.Paragraph> originalText, List<ReviewEvidence.Item> items,
                  List<ReviewDecisions.Decision> decisions, boolean originalTruncated, String decisionError) {
        Review(Map<String, Integer> elements, List<Finding> findings, List<String> compilerWarnings, List<String> scripts, String scope) {
            this(elements, findings, compilerWarnings, scripts, scope, null, List.of(), List.of(), List.of(), false, "");
        }
    }
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";

    Review review(Path input, Path compilerLog) {
        var counts = new LinkedHashMap<String, Integer>();
        for (String name : List.of("headings", "tables", "equations", "images", "footnotes", "links")) counts.put(name, 0);
        Set<String> flags = new LinkedHashSet<>();
        Set<String> scripts = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(input.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("word/media/") && !entry.isDirectory()) {
                    counts.merge("images", 1, Integer::sum);
                    if (!lower.matches(".*\\.(png|jpe?g|pdf)$")) flags.add("image_formats");
                }
                if (name.matches("word/(header|footer)\\d+\\.xml")) {
                    // python-docx emits blank header/footer parts too; only flag visible text.
                    if (entry.getSize() <= 8 * 1024 * 1024) {
                        try (InputStream stream = zip.getInputStream(entry)) {
                            parse(stream, new Handler(counts, flags, scripts, false, true));
                        }
                    }
                }
                if (name.startsWith("word/charts/")) flags.add("charts");
                if (name.startsWith("word/embeddings/")) flags.add("objects");
                if (name.equals("word/comments.xml")) flags.add("comments");
                if (name.equals("word/document.xml") || name.equals("word/footnotes.xml") || name.equals("word/endnotes.xml")) {
                    if (entry.getSize() > 8 * 1024 * 1024) { flags.add("inspection_limit"); continue; }
                    try (InputStream stream = zip.getInputStream(entry)) {
                        parse(stream, new Handler(counts, flags, scripts, true, false));
                    }
                }
            }
        } catch (Exception exception) { flags.add("inspection_limit"); }
        var findings = new ArrayList<Finding>();
        if (flags.contains("revisions")) findings.add(new Finding("revisions", "info", "Tracked changes accepted", "Inserted text is kept and deleted text is removed. Review the accepted version against Word."));
        if (flags.contains("merged")) findings.add(new Finding("merged", "warning", "Merged table cells", "Check row spans, column spans, table widths, and page breaks in the preview."));
        if (flags.contains("captions")) findings.add(new Finding("captions", "warning", "Captions already contain numbers", "LaTeX can add its own figure or table numbers. Check for duplicate labels such as Figure 1: Figure 1, and edit the caption text if needed."));
        if (flags.contains("headers")) findings.add(new Finding("headers", "warning", "Headers or footers contain text", "Word page headers and footers are not carried into the LaTeX body. Recreate any essential content in the source."));
        if (flags.contains("textboxes")) findings.add(new Finding("textboxes", "warning", "Floating text boxes", "Text may move into the document flow. Check its content and reading order."));
        if (flags.contains("charts")) findings.add(new Finding("charts", "warning", "Embedded charts detected", "Editable Word charts may be omitted. Export important charts as PNG images in Word before converting."));
        if (flags.contains("objects")) findings.add(new Finding("objects", "warning", "Embedded objects detected", "OLE objects and legacy equation objects may not convert. Replace them with native Word equations or images."));
        if (flags.contains("image_formats")) findings.add(new Finding("image_formats", "warning", "Images may need conversion", "Some embedded images use formats that the PDF compiler may not support. Use PNG or JPEG for reliable preview."));
        if (flags.contains("columns")) findings.add(new Finding("columns", "warning", "Multiple-column page layout", "Content is converted structurally. Word columns and exact pagination may change."));
        if (flags.contains("altchunk")) findings.add(new Finding("altchunk", "warning", "Imported document content", "Word altChunk content can be omitted. Open and save a normal DOCX copy in Word first."));
        if (flags.contains("comments")) findings.add(new Finding("comments", "info", "Review comments excluded", "Word review comments are not part of the converted body."));
        if (!scripts.isEmpty()) findings.add(new Finding("fonts", "warning", "Multilingual text detected", "Check fonts, line breaks, and reading direction for " + String.join(", ", scripts) + ". Bundled Noto fonts are applied where available; unusual scripts may need manual font settings."));
        if (flags.contains("inspection_limit")) findings.add(new Finding("inspection_limit", "warning", "Source inspection was limited", "Some document parts could not be inspected. Compare all important content with the original."));
        return new Review(counts, findings, compilerWarnings(compilerLog), List.copyOf(scripts), "Counts describe the Word source. Compilation and source inspection do not prove that every element was preserved.");
    }

    private List<String> compilerWarnings(Path log) {
        if (log == null || !Files.isRegularFile(log)) return List.of();
        try (InputStream stream = Files.newInputStream(log)) {
            String text = new String(stream.readNBytes(256 * 1024), StandardCharsets.UTF_8);
            var messages = new LinkedHashSet<String>();
            if (text.contains("Missing character:") || text.contains("does not contain the requested"))
                messages.add("The PDF font is missing characters. Change the LaTeX fonts before using this PDF; text may be invisible.");
            if (text.contains("Overfull \\hbox") || text.contains("Overfull \\vbox"))
                messages.add("Some content exceeds the page or column width. Check wide tables, equations, and long lines.");
            text.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains("warning:") && !line.contains("Missing character"))
                    .map(String::trim).limit(8).forEach(messages::add);
            return List.copyOf(messages);
        } catch (IOException exception) { return List.of("The compiler log could not be read."); }
    }

    private void parse(InputStream input, DefaultHandler handler) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var parser = factory.newSAXParser();
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        parser.parse(input, handler);
    }

    private static class Handler extends DefaultHandler {
        final Map<String, Integer> counts;
        final Set<String> flags;
        final Set<String> scripts;
        final boolean body;
        final boolean header;
        boolean text;
        boolean caption;
        final StringBuilder paragraphText = new StringBuilder();

        Handler(Map<String, Integer> counts, Set<String> flags, Set<String> scripts, boolean body, boolean header) {
            this.counts = counts; this.flags = flags; this.scripts = scripts; this.body = body; this.header = header;
        }

        @Override public void startElement(String uri, String local, String qualified, Attributes attributes) {
            if (W.equals(uri)) {
                if (local.equals("p")) { caption = false; paragraphText.setLength(0); }
                if (local.equals("t")) text = true;
                if (!body) return;
                if (local.equals("tbl")) counts.merge("tables", 1, Integer::sum);
                if (local.equals("hyperlink")) counts.merge("links", 1, Integer::sum);
                if (local.equals("pStyle")) {
                    String style = attributes.getValue(W, "val");
                    if (style != null && style.toLowerCase(Locale.ROOT).startsWith("heading")) counts.merge("headings", 1, Integer::sum);
                    if (style != null && style.equalsIgnoreCase("caption")) caption = true;
                }
                if (local.equals("footnote") || local.equals("endnote")) {
                    String id = attributes.getValue(W, "id");
                    if (id != null && !id.startsWith("-") && !id.equals("0")) counts.merge("footnotes", 1, Integer::sum);
                }
                if (local.equals("ins") || local.equals("del")) flags.add("revisions");
                if (local.equals("vMerge") || (local.equals("gridSpan") && !"1".equals(attributes.getValue(W, "val")))) flags.add("merged");
                if (local.equals("txbxContent")) flags.add("textboxes");
                if (local.equals("object")) flags.add("objects");
                if (local.equals("altChunk")) flags.add("altchunk");
                if (local.equals("cols") && attributes.getValue(W, "num") != null && !"1".equals(attributes.getValue(W, "num"))) flags.add("columns");
            }
            if (body && M.equals(uri) && local.equals("oMath")) counts.merge("equations", 1, Integer::sum);
        }

        @Override public void endElement(String uri, String local, String qualified) {
            if (W.equals(uri) && local.equals("t")) text = false;
            if (body && W.equals(uri) && local.equals("p") && caption
                    && paragraphText.toString().matches("(?is)^\\s*(figure|fig\\.?|table)\\s+\\d.*")) flags.add("captions");
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (!text) return;
            String value = new String(chars, start, length);
            if (header && !value.isBlank()) flags.add("headers");
            if (!body) return;
            if (paragraphText.length() < 2048) paragraphText.append(value);
            value.codePoints().filter(Character::isLetter).forEach(point -> {
                var script = Character.UnicodeScript.of(point);
                if (!Set.of(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED).contains(script))
                    scripts.add(script.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            });
        }
    }
}
