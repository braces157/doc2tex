package dev.doc2tex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.json.JsonMapper;

/** Page geometry from current Word sections; text and native equations still go through Pandoc. */
record SourceLayout(List<SourceLayout.Page> sections, List<String> warnings) {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int XML_LIMIT = 8 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    SourceLayout {
        sections = List.copyOf(sections);
        warnings = List.copyOf(warnings);
        if (sections.size() > 1000 || warnings.size() > 10) throw new IllegalArgumentException("Layout report exceeds its limit.");
        if (sections.isEmpty() && warnings.isEmpty()) throw new IllegalArgumentException("Missing page settings require a fallback explanation.");
    }

    record Page(BigDecimal widthMm, BigDecimal heightMm, BigDecimal topMm, BigDecimal rightMm,
                BigDecimal bottomMm, BigDecimal leftMm) {
        Page {
            for (var value : List.of(widthMm, heightMm, topMm, rightMm, bottomMm, leftMm)) {
                if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(600)) > 0 || value.scale() > 3)
                    throw new IllegalArgumentException("Unsupported page dimensions.");
            }
            if (widthMm.compareTo(BigDecimal.valueOf(50)) < 0 || heightMm.compareTo(BigDecimal.valueOf(50)) < 0
                    || widthMm.subtract(leftMm).subtract(rightMm).compareTo(BigDecimal.valueOf(20)) < 0
                    || heightMm.subtract(topMm).subtract(bottomMm).compareTo(BigDecimal.valueOf(20)) < 0)
                throw new IllegalArgumentException("Unsupported page text area.");
        }

        String geometry() {
            return "paperwidth=" + widthMm.toPlainString() + "mm,paperheight=" + heightMm.toPlainString()
                    + "mm,top=" + topMm.toPlainString() + "mm,right=" + rightMm.toPlainString()
                    + "mm,bottom=" + bottomMm.toPlainString() + "mm,left=" + leftMm.toPlainString() + "mm";
        }

        String description() {
            return widthMm.toPlainString() + " × " + heightMm.toPlainString() + " mm "
                    + (widthMm.compareTo(heightMm) > 0 ? "landscape" : "portrait")
                    + "; margins (top/right/bottom/left): " + topMm.toPlainString() + "/" + rightMm.toPlainString()
                    + "/" + bottomMm.toPlainString() + "/" + leftMm.toPlainString() + " mm";
        }
    }

    static SourceLayout inspect(Path original) {
        try (var zip = new ZipFile(original.toFile())) {
            var entry = zip.getEntry("word/document.xml");
            if (entry == null) return fallback("The document has no page settings.");
            Document document = parse(zip, "word/document.xml");
            if (zip.getEntry("word/settings.xml") != null) {
                Document settings = parse(zip, "word/settings.xml");
                for (String feature : List.of("mirrorMargins", "gutterAtTop", "bookFoldPrinting", "bookFoldRevPrinting")) {
                    var nodes = settings.getElementsByTagNameNS(W, feature);
                    for (int i = 0; i < nodes.getLength(); i++) if (enabled((Element) nodes.item(i)))
                        return fallback("Mirrored margins or book-binding settings need manual layout review.");
                }
            }
            var bodies = document.getElementsByTagNameNS(W, "body");
            if (bodies.getLength() != 1) return fallback("The document body could not be inspected.");
            var nodes = ((Element) bodies.item(0)).getElementsByTagNameNS(W, "sectPr");
            var sections = new ArrayList<Page>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element section = (Element) nodes.item(i);
                if (historical(section)) continue;
                if (section.getParentNode() instanceof Element properties && W.equals(properties.getNamespaceURI())
                        && "pPr".equals(properties.getLocalName())) {
                    Element mark = child(properties, "rPr");
                    if (mark != null && (child(mark, "del") != null || child(mark, "moveFrom") != null))
                        return fallback("Tracked deletion or movement of a section break needs manual layout review.");
                }
                if (sections.size() >= 1000) return fallback("The document has too many sections to inspect.");
                Element size = child(section, "pgSz"), margins = child(section, "pgMar");
                if (size == null || margins == null) return fallback("Some sections have incomplete page size or margin settings.");
                if (margins.hasAttributeNS(W, "gutter") && Integer.parseInt(margins.getAttributeNS(W, "gutter")) != 0)
                    return fallback("Binding gutters need manual layout review.");
                sections.add(new Page(mm(size, "w"), mm(size, "h"), mm(margins, "top"), mm(margins, "right"),
                        mm(margins, "bottom"), mm(margins, "left")));
            }
            if (sections.isEmpty()) return fallback("No explicit page size and margins were found.");
            var warnings = new ArrayList<String>();
            if (sections.stream().anyMatch(page -> !page.equals(sections.getFirst())))
                warnings.add("Page settings change between sections. The first section's page size and margins are used throughout; review later sections.");
            return new SourceLayout(sections, warnings);
        } catch (Exception exception) {
            return fallback("Page settings could not be read safely or use unsupported dimensions.");
        }
    }

    private static SourceLayout fallback(String reason) {
        return new SourceLayout(List.of(), List.of(reason + " Using the selected paper size and margins instead."));
    }

    private static Document parse(ZipFile zip, String name) throws Exception {
        byte[] xml;
        try (var input = zip.getInputStream(zip.getEntry(name))) { xml = input.readNBytes(XML_LIMIT + 1); }
        if (xml.length > XML_LIMIT) throw new IOException("Page inspection exceeds its XML limit.");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("jdk.xml.maxElementDepth", "128");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
        });
        return builder.parse(new ByteArrayInputStream(xml));
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && W.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())) return element;
        return null;
    }

    private static boolean historical(Element section) {
        for (Node node = section.getParentNode(); node instanceof Element element; node = node.getParentNode())
            if (W.equals(element.getNamespaceURI()) && List.of("sectPrChange", "del", "moveFrom").contains(element.getLocalName())) return true;
        return false;
    }

    private static boolean enabled(Element element) {
        return !List.of("0", "false", "off").contains(element.getAttributeNS(W, "val"));
    }

    private static BigDecimal mm(Element element, String name) {
        int twips = Integer.parseInt(element.getAttributeNS(W, name));
        if (twips < 0 || twips > 34015) throw new IllegalArgumentException("Unsupported page measurement.");
        return BigDecimal.valueOf(twips).multiply(new BigDecimal("25.4"))
                .divide(BigDecimal.valueOf(1440), 3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    void save(Path output) throws IOException {
        Files.write(output.resolve("source-layout.json"), JSON.writeValueAsBytes(this));
    }

    static SourceLayout read(Path output) throws IOException {
        Path file = output.resolve("source-layout.json");
        JobStore.checkUnlinked(file);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("The layout report exceeds its limit.");
            return JSON.readValue(bytes, SourceLayout.class);
        }
    }

    List<DocumentInspector.Finding> findings() {
        var findings = new ArrayList<DocumentInspector.Finding>();
        if (!sections.isEmpty()) findings.add(new DocumentInspector.Finding("source-page-layout", "info", "Original page settings applied",
                sections.getFirst().description() + ". These are initial conversion settings; later source edits take precedence. Fonts, columns and exact pagination may differ."));
        for (String warning : warnings) findings.add(new DocumentInspector.Finding("source-page-layout-review", "warning", "Page layout needs review", warning));
        return findings;
    }
}
