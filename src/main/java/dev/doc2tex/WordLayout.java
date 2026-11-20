package dev.doc2tex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Source text and explicit styles constrain what visual evidence may change. */
record WordLayout(List<WordLayout.Paragraph> paragraphs, boolean uniformTwoColumns, boolean hasTables, BigDecimal columnGapMm,
                  List<String> columnStarts) {
    record Paragraph(int index, String text, boolean headingCandidate) {}

    WordLayout { paragraphs = List.copyOf(paragraphs); columnStarts = List.copyOf(columnStarts); }

    static WordLayout read(Path original) throws IOException {
        try (var zip = new ZipFile(original.toFile())) {
            byte[] xml;
            var entry = zip.getEntry("word/document.xml");
            if (entry == null) throw new IOException("The source document body is missing.");
            try (var input = zip.getInputStream(entry)) { xml = input.readNBytes(8 * 1024 * 1024 + 1); }
            if (xml.length > 8 * 1024 * 1024) throw new IOException("Word layout inspection exceeds its XML limit.");
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("jdk.xml.maxElementDepth", "128"); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
            String ns = Ooxml.wordNamespace(root);
            if (ns == null) throw new IOException("Unsupported Word document namespace.");
            String mathNs = Ooxml.mathNamespace(ns);
            var bodies = root.getElementsByTagNameNS(ns, "body");
            if (bodies.getLength() != 1) throw new IOException("The source document body is ambiguous.");
            Element body = (Element) bodies.item(0);
            var nodes = body.getElementsByTagNameNS(ns, "p");
            var paragraphs = new ArrayList<Paragraph>();
            Map<String, Integer> counts = new HashMap<>();
            var columnStarts = new ArrayList<String>();
            boolean nextColumn = false, unsupportedBreak = false;
            for (int i = 0; i < nodes.getLength(); i++) {
                Element paragraph = (Element) nodes.item(i);
                if (historical(paragraph, ns)) continue;
                StringBuilder text = new StringBuilder(); acceptedText(paragraph, ns, text);
                String value = normalized(text.toString().replace('\0', ' '));
                if (paragraph.getParentNode() == body) {
                    boolean pending = nextColumn;
                    if (!value.isBlank() && nextColumn) { columnStarts.add(value); nextColumn = false; }
                    int split = text.indexOf("\0");
                    if (split >= 0) {
                        boolean before = normalized(text.substring(0, split)).isBlank(), after = normalized(text.substring(split + 1)).isBlank();
                        if (split != text.lastIndexOf("\0") || (!before && !after)) unsupportedBreak = true;
                        else if (before && !after) { if (pending) unsupportedBreak = true; columnStarts.add(value); }
                        else { if (pending && value.isBlank()) unsupportedBreak = true; nextColumn = true; }
                    }
                }
                if (value.isBlank()) continue;
                if (paragraphs.size() >= 20000) throw new IOException("The document has too many paragraphs for visual matching.");
                Element properties = child(paragraph, ns, "pPr"), style = properties == null ? null : child(properties, ns, "pStyle");
                boolean plainStyle = style == null || List.of("Normal", "BodyText").contains(style.getAttributeNS(ns, "val"));
                boolean simple = paragraph.getParentNode() == body && plainStyle && value.length() <= 200
                        && (properties == null || (child(properties, ns, "numPr") == null && child(properties, ns, "outlineLvl") == null))
                        && paragraph.getElementsByTagNameNS(ns, "drawing").getLength() == 0
                        && paragraph.getElementsByTagNameNS(ns, "pict").getLength() == 0
                        && paragraph.getElementsByTagNameNS(ns, "object").getLength() == 0
                        && paragraph.getElementsByTagNameNS(ns, "instrText").getLength() == 0
                        && paragraph.getElementsByTagNameNS(ns, "fldSimple").getLength() == 0
                        && paragraph.getElementsByTagNameNS(mathNs, "*").getLength() == 0;
                paragraphs.add(new Paragraph(i + 1, value, simple));
                counts.merge(key(value), 1, Integer::sum);
            }
            var unique = paragraphs.stream().map(p -> new Paragraph(p.index(), p.text(), p.headingCandidate() && counts.get(key(p.text())) == 1)).toList();
            var sections = body.getElementsByTagNameNS(ns, "sectPr");
            boolean twoColumns = sections.getLength() > 0;
            BigDecimal gap = null;
            int current = 0;
            for (int i = 0; i < sections.getLength(); i++) {
                Element section = (Element) sections.item(i);
                if (historical(section, ns)) continue;
                current++;
                Element columns = child(section, ns, "cols");
                twoColumns &= columns != null && "2".equals(columns.getAttributeNS(ns, "num"))
                        && !List.of("0", "false", "off").contains(columns.getAttributeNS(ns, "equalWidth"))
                        && columns.getElementsByTagNameNS(ns, "col").getLength() == 0;
                if (columns != null && "2".equals(columns.getAttributeNS(ns, "num"))) {
                    String space = columns.getAttributeNS(ns, "space");
                    if (space.isEmpty()) twoColumns = false;
                    else {
                        BigDecimal currentGap;
                        try { currentGap = Ooxml.measureMm(space, BigDecimal.valueOf(50)); }
                        catch (IllegalArgumentException exception) { twoColumns = false; currentGap = null; }
                        if (currentGap != null) {
                            if (gap != null && gap.compareTo(currentGap) != 0) twoColumns = false;
                            gap = currentGap;
                        }
                    }
                }
                if (section.getParentNode() instanceof Element properties && "pPr".equals(properties.getLocalName())) {
                    Element mark = child(properties, ns, "rPr");
                    if (mark != null && (child(mark, ns, "del") != null || child(mark, ns, "moveFrom") != null)) twoColumns = false;
                }
            }
            boolean breaksMapped = !unsupportedBreak && columnStarts.size() <= 100
                    && columnStarts.stream().allMatch(text -> counts.getOrDefault(key(text), 0) == 1);
            return new WordLayout(unique, twoColumns && current > 0 && breaksMapped, body.getElementsByTagNameNS(ns, "tbl").getLength() > 0,
                    gap == null ? BigDecimal.ZERO : gap, columnStarts.stream().limit(100).toList());
        } catch (IOException exception) { throw exception; }
        catch (Exception exception) { throw new IOException("The source layout could not be inspected safely.", exception); }
    }

    static String normalized(String text) { return text.replaceAll("[\\s\\u00a0]+", " ").strip(); }
    static String key(String text) { return normalized(Normalizer.normalize(text, Normalizer.Form.NFKC)); }

    private static void acceptedText(Node node, String ns, StringBuilder text) {
        if (node instanceof Element element && ns.equals(element.getNamespaceURI())) {
            if (List.of("del", "moveFrom", "sectPrChange").contains(element.getLocalName())) return;
            if (element.getLocalName().equals("t")) { text.append(element.getTextContent()); return; }
            if (List.of("br", "tab", "cr").contains(element.getLocalName())) {
                text.append(element.getLocalName().equals("br") && "column".equals(element.getAttributeNS(ns, "type")) ? '\0' : ' '); return;
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) acceptedText(child, ns, text);
    }

    private static boolean historical(Element node, String ns) {
        for (Node parent = node.getParentNode(); parent instanceof Element element; parent = parent.getParentNode())
            if (ns.equals(element.getNamespaceURI()) && List.of("del", "moveFrom", "sectPrChange").contains(element.getLocalName())) return true;
        return false;
    }

    private static Element child(Element parent, String ns, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && ns.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())) return element;
        return null;
    }
}
