package dev.doc2tex;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Inspects and pre-flight cleans Word .docx archives while preserving OOXML validity. */
@Service
class DocxCleanerService {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_XML_BYTES = 8 * 1024 * 1024;
    private static final long MIN_OPTIMIZE_BYTES = 80 * 1024;
    private static final Set<String> SCRUB_CORE_TAGS = Set.of(
            "title", "subject", "creator", "keywords", "description", "lastModifiedBy", "created", "modified");
    private static final Set<String> SCRUB_APP_TAGS = Set.of("Company", "Manager", "Application");

    record MediaItem(String name, long bytes) {}
    record DocxInspection(
            String filename,
            long totalBytes,
            long mediaBytes,
            int mediaCount,
            int commentCount,
            int trackedChangeCount,
            int externalLinkCount,
            Map<String, String> metadata,
            List<MediaItem> largestMedia,
            List<String> recommendations) {}

    record CleanOptions(boolean stripMetadata, boolean stripComments, boolean compressMedia, Integer maxImagePx) {}
    record CleanResult(
            String filename,
            byte[] bytes,
            long originalBytes,
            long cleanedBytes,
            int imagesOptimized,
            boolean metadataStripped,
            boolean commentsStripped) {}

    private final DocxValidator validator;

    DocxCleanerService(DocxValidator validator) {
        this.validator = validator;
    }

    DocxInspection inspect(MultipartFile file) throws IOException {
        Path tempDir = Files.createTempDirectory("doc2tex-docx-inspect-");
        try {
            Path input = validateIncomingDocx(file, tempDir);
            long totalBytes = Files.size(input);
            String filename = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document.docx");
            long mediaBytes = 0;
            List<MediaItem> media = new ArrayList<>();
            Map<String, String> metadata = new LinkedHashMap<>();
            int comments = 0;
            int tracked = 0;
            int externalLinks = 0;

            try (ZipFile zip = new ZipFile(input.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (name.startsWith("word/media/")) {
                        byte[] data = readEntryBounded(zip, entry, MAX_FILE_BYTES);
                        mediaBytes += data.length;
                        media.add(new MediaItem(name.substring("word/media/".length()), data.length));
                    } else if (name.equals("docProps/core.xml")) {
                        readXmlMetadata(readEntryBounded(zip, entry, MAX_XML_BYTES), metadata);
                    } else if (name.equals("docProps/app.xml")) {
                        readXmlMetadata(readEntryBounded(zip, entry, MAX_XML_BYTES), metadata);
                    } else if (name.matches("word/comments.*\\.xml")) {
                        comments += countTags(readEntryBounded(zip, entry, MAX_XML_BYTES), Set.of("comment"));
                    } else if (name.equals("word/document.xml")) {
                        tracked += countTags(readEntryBounded(zip, entry, MAX_XML_BYTES), Set.of("ins", "del"));
                    } else if (name.startsWith("word/_rels/") && name.endsWith(".rels")) {
                        externalLinks += countExternalLinks(readEntryBounded(zip, entry, MAX_XML_BYTES));
                    }
                }
            }

            media.sort(Comparator.comparingLong(MediaItem::bytes).reversed());
            List<MediaItem> largest = media.stream().limit(8).toList();
            List<String> recommendations = new ArrayList<>();
            if (!metadata.isEmpty()) {
                recommendations.add("Document contains author or property metadata (" + String.join(", ", metadata.keySet()) + "). Strip metadata before blind sharing.");
            }
            if (comments > 0) {
                recommendations.add("Found " + comments + " review comment(s). Strip comments before external release.");
            }
            if (tracked > 0) {
                recommendations.add("Document contains " + tracked + " tracked revision element(s). Accept or reject changes in Word before finalizing.");
            }
            if (mediaBytes > 2L * 1024 * 1024) {
                recommendations.add("Embedded media totals " + (mediaBytes / (1024 * 1024)) + " MB. Enable media compression to slim down oversized images.");
            }

            return new DocxInspection(
                    filename, totalBytes, mediaBytes, media.size(), comments, tracked,
                    externalLinks, metadata, largest, recommendations);
        } finally {
            SourceVersionStore.removeTreeQuietly(tempDir);
        }
    }

    CleanResult clean(MultipartFile file, CleanOptions options) throws IOException {
        if (options == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose at least one cleaning option.");
        }
        int maxPx = options.maxImagePx() == null ? 1800 : Math.max(600, Math.min(3000, options.maxImagePx()));
        Path tempDir = Files.createTempDirectory("doc2tex-docx-clean-");
        try {
            Path input = validateIncomingDocx(file, tempDir);
            long originalBytes = Files.size(input);
            String filename = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document.docx");
            String outName = PdfStudioService.sanitizeDownloadName(
                    PdfStudioService.stripExtension(filename) + "-cleaned.docx", "cleaned.docx", ".docx");
            Path output = tempDir.resolve("cleaned.docx");
            int imagesOptimized = 0;

            try (ZipFile zip = new ZipFile(input.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
                CommentDiscovery commentDiscovery = options.stripComments()
                        ? discoverCommentParts(zip)
                        : new CommentDiscovery(Set.of(), Set.of());
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    String normalizedName = normalizePartPath(name);
                    if (options.stripMetadata() && (name.equals("docProps/custom.xml") || name.startsWith("docProps/thumbnail."))) {
                        continue;
                    }
                    if (options.stripComments() && commentDiscovery.commentParts().contains(normalizedName)) {
                        continue;
                    }

                    byte[] bytes = readEntryBounded(zip, entry, name.startsWith("word/media/") ? MAX_FILE_BYTES : MAX_XML_BYTES);
                    if (options.stripMetadata() && name.equals("docProps/core.xml")) {
                        bytes = scrubXmlText(bytes, SCRUB_CORE_TAGS);
                    } else if (options.stripMetadata() && name.equals("docProps/app.xml")) {
                        bytes = scrubXmlText(bytes, SCRUB_APP_TAGS);
                    } else if (options.stripComments() && commentDiscovery.markerOwnerParts().contains(normalizedName)) {
                        bytes = stripCommentMarkers(bytes);
                    } else if ((options.stripComments() || options.stripMetadata()) && name.equals("[Content_Types].xml")) {
                        bytes = stripContentTypeOverrides(bytes, options.stripComments(), options.stripMetadata(), commentDiscovery.commentParts());
                    } else if (options.compressMedia() && name.startsWith("word/media/") && bytes.length >= MIN_OPTIMIZE_BYTES) {
                        byte[] compressed = compressMediaEntry(name, bytes, maxPx);
                        if (compressed.length < bytes.length) {
                            bytes = compressed;
                            imagesOptimized++;
                        }
                    }
                    if (options.stripComments() && name.endsWith(".rels")) {
                        bytes = stripCommentRelationships(bytes);
                    }
                    if (options.stripMetadata() && name.equals("_rels/.rels")) {
                        bytes = stripRelationshipTargets(bytes, "custom.xml", "thumbnail.");
                    }

                    out.putNextEntry(new ZipEntry(name));
                    out.write(bytes);
                    out.closeEntry();
                }
            }

            validator.validate(output);
            byte[] finalBytes = Files.readAllBytes(output);
            return new CleanResult(
                    outName, finalBytes, originalBytes, finalBytes.length,
                    imagesOptimized, options.stripMetadata(), options.stripComments());
        } finally {
            SourceVersionStore.removeTreeQuietly(tempDir);
        }
    }

    private Path validateIncomingDocx(MultipartFile file, Path tempDir) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a non-empty .docx file up to 20 MB.");
        }
        Path input = tempDir.resolve("input.docx");
        file.transferTo(input);
        validator.validate(input);
        return input;
    }

    private static byte[] readEntryBounded(ZipFile zip, ZipEntry entry, long limitBytes) throws IOException {
        try (InputStream stream = zip.getInputStream(entry)) {
            int cap = (int) Math.min(Integer.MAX_VALUE - 8, limitBytes);
            byte[] data = stream.readNBytes(cap + 1);
            if (data.length > cap) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A DOCX component exceeds the size inspection limit.");
            }
            return data;
        }
    }

    private static Document parseSecureXml(byte[] xmlBytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Malformed OOXML part.", exception);
        }
    }

    private static byte[] serializeXml(Document doc) throws IOException {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException exception) {
            throw new IOException("Could not write cleaned OOXML part.", exception);
        }
    }

    private static void readXmlMetadata(byte[] bytes, Map<String, String> out) throws IOException {
        Document doc = parseSecureXml(bytes);
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) continue;
            String local = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
            if (!SCRUB_CORE_TAGS.contains(local) && !SCRUB_APP_TAGS.contains(local)) continue;
            String value = el.getTextContent() == null ? "" : el.getTextContent().trim();
            if (!value.isEmpty()) out.put(local, value.length() > 200 ? value.substring(0, 200) : value);
        }
    }

    private static int countTags(byte[] bytes, Set<String> localNames) throws IOException {
        Document doc = parseSecureXml(bytes);
        int total = 0;
        for (String tag : localNames) {
            total += doc.getElementsByTagNameNS("*", tag).getLength();
        }
        return total;
    }

    private static int countExternalLinks(byte[] bytes) throws IOException {
        Document doc = parseSecureXml(bytes);
        NodeList nodes = doc.getDocumentElement().getElementsByTagName("*");
        int total = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el && "External".equalsIgnoreCase(el.getAttribute("TargetMode"))) {
                total++;
            }
        }
        return total;
    }

    private static byte[] scrubXmlText(byte[] bytes, Set<String> tags) throws IOException {
        Document doc = parseSecureXml(bytes);
        NodeList children = doc.getDocumentElement().getChildNodes();
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) continue;
            String local = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
            if (!tags.contains(local)) continue;
            // Remove typed date nodes (e.g. dcterms:created/modified with xsi:type="dcterms:W3CDTF")
            // instead of leaving invalid empty W3CDTF elements.
            if (local.equals("created") || local.equals("modified") || el.hasAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type")) {
                toRemove.add(el);
            } else {
                el.setTextContent("");
            }
        }
        for (Node n : toRemove) doc.getDocumentElement().removeChild(n);
        return serializeXml(doc);
    }

    private static byte[] stripCommentMarkers(byte[] bytes) throws IOException {
        Document doc = parseSecureXml(bytes);
        for (String tag : List.of("commentRangeStart", "commentRangeEnd", "commentReference")) {
            NodeList list = doc.getElementsByTagNameNS("*", tag);
            List<Node> toRemove = new ArrayList<>();
            for (int i = 0; i < list.getLength(); i++) toRemove.add(list.item(i));
            for (Node node : toRemove) {
                if (node.getParentNode() != null) node.getParentNode().removeChild(node);
            }
        }
        return serializeXml(doc);
    }

    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private record CommentDiscovery(Set<String> commentParts, Set<String> markerOwnerParts) {}

    private static CommentDiscovery discoverCommentParts(ZipFile zip) throws IOException {
        Set<String> commentParts = new LinkedHashSet<>();
        Set<String> markerOwners = new LinkedHashSet<>(List.of(
                "word/document.xml", "word/footnotes.xml", "word/endnotes.xml", "word/glossary/document.xml"));
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            String normalized = normalizePartPath(name);
            if (normalized.matches("^(?:.*/)?comments[^/]*\\.xml$")) {
                commentParts.add(normalized);
            }
            if (!name.endsWith(".rels")) continue;
            byte[] relBytes = readEntryBounded(zip, entry, MAX_XML_BYTES);
            Document doc = parseSecureXml(relBytes);
            NodeList nodes = doc.getDocumentElement().getChildNodes();
            String owner = ownerPartForRels(name);
            String baseDir = baseDirForRels(name);
            boolean ownerHasComments = false;
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element el)) continue;
                if ("External".equalsIgnoreCase(el.getAttribute("TargetMode"))) continue;
                String type = el.getAttribute("Type");
                String target = el.getAttribute("Target");
                if (isCommentRelationship(type, target)) {
                    String resolved = resolveOpcTarget(baseDir, target);
                    if (!resolved.isEmpty()) commentParts.add(resolved);
                    ownerHasComments = true;
                }
            }
            if (ownerHasComments && !owner.isEmpty()) {
                markerOwners.add(owner);
            }
        }
        return new CommentDiscovery(commentParts, markerOwners);
    }

    private static boolean isCommentRelationship(String type, String target) {
        String lowerType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String lowerTarget = target == null ? "" : target.toLowerCase(Locale.ROOT);
        return lowerType.endsWith("/comments")
                || lowerType.endsWith("/commentsextended")
                || lowerType.endsWith("/commentsids")
                || lowerType.endsWith("/commentsextensible")
                || lowerTarget.matches("(?i)(?:^|.*/)comments[^/]*\\.xml$");
    }

    private static String normalizePartPath(String path) {
        if (path == null) return "";
        String cleaned = path.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static String baseDirForRels(String relsName) {
        String normalized = relsName.replace('\\', '/');
        int idx = normalized.lastIndexOf("/_rels/");
        if (idx >= 0) return normalized.substring(0, idx + 1);
        if (normalized.startsWith("_rels/")) return "";
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(0, slash + 1) : "";
    }

    private static String ownerPartForRels(String relsName) {
        String normalized = relsName.replace('\\', '/');
        if (normalized.equals("_rels/.rels")) return "";
        int idx = normalized.lastIndexOf("/_rels/");
        if (idx >= 0 && normalized.endsWith(".rels")) {
            String dir = normalized.substring(0, idx + 1);
            String file = normalized.substring(idx + "/_rels/".length(), normalized.length() - ".rels".length());
            return normalizePartPath(dir + file);
        }
        return "";
    }

    private static String resolveOpcTarget(String baseDir, String target) {
        if (target == null || target.isBlank()) return "";
        String cleanTarget = target.replace('\\', '/').trim();
        if (cleanTarget.startsWith("/")) return normalizePartPath(cleanTarget);
        try {
            URI base = URI.create("opc://pkg/" + baseDir);
            URI resolved = base.resolve(cleanTarget).normalize();
            String path = resolved.getPath();
            return path == null ? "" : normalizePartPath(path);
        } catch (IllegalArgumentException ignored) {
            return normalizePartPath(baseDir + cleanTarget);
        }
    }

    private static byte[] stripCommentRelationships(byte[] bytes) throws IOException {
        Document doc = parseSecureXml(bytes);
        NodeList nodes = doc.getDocumentElement().getChildNodes();
        List<Node> remove = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) continue;
            if ("External".equalsIgnoreCase(el.getAttribute("TargetMode"))) continue;
            if (isCommentRelationship(el.getAttribute("Type"), el.getAttribute("Target"))) {
                remove.add(el);
            }
        }
        for (Node n : remove) doc.getDocumentElement().removeChild(n);
        return serializeXml(doc);
    }

    private static byte[] stripRelationshipTargets(byte[] bytes, String... needles) throws IOException {
        Document doc = parseSecureXml(bytes);
        NodeList nodes = doc.getDocumentElement().getChildNodes();
        List<Node> remove = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) continue;
            String target = el.getAttribute("Target").toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (target.contains(needle)) {
                    remove.add(el);
                    break;
                }
            }
        }
        for (Node n : remove) doc.getDocumentElement().removeChild(n);
        return serializeXml(doc);
    }

    private static byte[] stripContentTypeOverrides(
            byte[] bytes, boolean comments, boolean metadata, Set<String> commentParts) throws IOException {
        Document doc = parseSecureXml(bytes);
        NodeList nodes = doc.getDocumentElement().getChildNodes();
        List<Node> remove = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) continue;
            String rawPart = el.getAttribute("PartName");
            String normalizedPart = normalizePartPath(rawPart);
            if ((comments && (commentParts.contains(normalizedPart) || normalizedPart.matches("^(?:.*/)?comments[^/]*\\.xml$")))
                    || (metadata && normalizedPart.equals("docprops/custom.xml"))) {
                remove.add(el);
            }
        }
        for (Node n : remove) doc.getDocumentElement().removeChild(n);
        return serializeXml(doc);
    }

    private static byte[] compressMediaEntry(String name, byte[] original, int maxPx) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        boolean png = lower.endsWith(".png");
        if (!jpeg && !png) return original;
        try {
            BufferedImage src = readBoundedImage(original, maxPx);
            if (src == null) return original;
            int w = src.getWidth();
            int h = src.getHeight();
            int longest = Math.max(w, h);
            BufferedImage resized = src;
            if (longest > maxPx) {
                double scale = (double) maxPx / longest;
                int nw = Math.max(1, (int) Math.round(w * scale));
                int nh = Math.max(1, (int) Math.round(h * scale));
                int type = (jpeg || !src.getColorModel().hasAlpha())
                        ? BufferedImage.TYPE_INT_RGB
                        : BufferedImage.TYPE_INT_ARGB;
                resized = new BufferedImage(nw, nh, type);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, nw, nh, null);
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (png) {
                if (!ImageIO.write(resized, "png", out)) return original;
            } else {
                BufferedImage rgb = resized;
                if (resized.getColorModel().hasAlpha()) {
                    rgb = new BufferedImage(resized.getWidth(), resized.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = rgb.createGraphics();
                    g.drawImage(resized, 0, 0, java.awt.Color.WHITE, null);
                    g.dispose();
                }
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                if (!writers.hasNext()) return original;
                ImageWriter writer = writers.next();
                try (MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(out)) {
                    writer.setOutput(mos);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.82f);
                    writer.write(null, new IIOImage(rgb, null, null), param);
                } finally {
                    writer.dispose();
                }
            }
            byte[] candidate = out.toByteArray();
            return candidate.length > 0 && candidate.length < original.length ? candidate : original;
        } catch (IOException | RuntimeException ignored) {
            return original;
        }
    }

    private static BufferedImage readBoundedImage(byte[] original, int maxPx) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            if (iis == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0) return null;
                long pixels = (long) w * h;
                int longest = Math.max(w, h);
                ImageReadParam param = reader.getDefaultReadParam();
                int subsample = 1;
                if (longest > maxPx * 2) {
                    subsample = Math.max(1, (int) Math.floor((double) longest / (maxPx * 1.5)));
                }
                long decodedPixels = ((long) (w + subsample - 1) / subsample) * ((long) (h + subsample - 1) / subsample);
                if (pixels > MAX_IMAGE_PIXELS || decodedPixels > MAX_IMAGE_PIXELS) {
                    return null;
                }
                if (subsample > 1) {
                    param.setSourceSubsampling(subsample, subsample, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }
}
