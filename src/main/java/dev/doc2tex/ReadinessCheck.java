package dev.doc2tex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic PDF content verification and plain-English readiness assessment. */
final class ReadinessCheck {
    private static final Logger LOG = LoggerFactory.getLogger(ReadinessCheck.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERIFICATION_FILE = "pdf-verification.json";
    private static final int VERIFICATION_LIMIT = 64 * 1024;
    private static final int XML_LIMIT = 2 * 1024 * 1024;
    private static final int PARAGRAPH_LIMIT = 200;
    private static final int TOKEN_LIMIT = 300;
    private static final int MEDIA_WALK_LIMIT = 500;
    private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    record Item(String code, String severity, String title, String guidance) {}
    record Verification(int revision, boolean pdfVerified, Integer pageCount, Double textCoverage,
                        int checkedTokens, int matchedTokens) {}
    record Report(String status, String headline, String summary, int revision, boolean pdfVerified,
                  Integer pageCount, Double textCoverage, List<AutoPolish.Fix> autoFixes,
                  List<Item> actions, List<String> handledAutomatically) {}

    private ReadinessCheck() {}

    static Verification verifyPdf(Path originalDocx, Path pdfFile, int revision) throws IOException {
        JobStore.checkUnlinked(pdfFile);
        JobStore.checkUnlinked(originalDocx);
        int pageCount;
        String pdfText;
        try (var pdf = Loader.loadPDF(pdfFile.toFile())) {
            pageCount = pdf.getNumberOfPages();
            pdfText = new PDFTextStripper().getText(pdf);
        }
        String compactPdf = Normalizer.normalize(pdfText == null ? "" : pdfText, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Z}\\u00AD]+", "");

        if (!Files.isRegularFile(originalDocx, LinkOption.NOFOLLOW_LINKS)) {
            return new Verification(revision, pageCount >= 1, pageCount, null, 0, 0);
        }

        List<String> tokens = extractAcceptedBodyTokens(originalDocx);
        int checked = tokens.size();
        int matched = 0;
        for (String token : tokens) {
            if (compactPdf.contains(token)) matched++;
        }
        Double coverage = checked >= 5 ? (double) matched / checked : null;
        return new Verification(revision, pageCount >= 1, pageCount, coverage, checked, matched);
    }

    private static List<String> extractAcceptedBodyTokens(Path originalDocx) {
        var tokens = new LinkedHashSet<String>();
        try (var zip = new ZipFile(originalDocx.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) return List.of();
            byte[] bytes;
            try (var input = zip.getInputStream(entry)) {
                bytes = input.readNBytes(XML_LIMIT + 1);
            }
            if (bytes.length > XML_LIMIT) return List.of();
            Document document = parseSecure(bytes);
            Element root = document.getDocumentElement();
            String ns = Ooxml.wordNamespace(root);
            if (ns == null || !"document".equals(root.getLocalName())) return List.of();

            var paragraphs = document.getElementsByTagNameNS(ns, "p");
            int inspected = 0;
            for (int i = 0; i < paragraphs.getLength() && inspected < PARAGRAPH_LIMIT && tokens.size() < TOKEN_LIMIT; i++) {
                Element paragraph = (Element) paragraphs.item(i);
                if (hasCaptionStyle(paragraph, ns)) continue;
                String text = acceptedText(paragraph, ns).strip();
                if (text.isBlank()) continue;
                inspected++;
                String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
                Matcher matcher = WORD_TOKEN.matcher(normalized);
                while (matcher.find() && tokens.size() < TOKEN_LIMIT) {
                    String token = matcher.group();
                    if (containsRtlScript(token)) continue;
                    tokens.add(token);
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException | RuntimeException ignored) {
            // Bounded XML fallback: report PDF verification without text coverage rather than failing a valid build.
            return List.of();
        }
        return List.copyOf(tokens);
    }

    private static Document parseSecure(byte[] bytes) throws ParserConfigurationException, SAXException, IOException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute("jdk.xml.maxElementDepth", "128");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static boolean hasCaptionStyle(Element paragraph, String ns) {
        var styles = paragraph.getElementsByTagNameNS(ns, "pStyle");
        for (int i = 0; i < styles.getLength(); i++) {
            if ("caption".equalsIgnoreCase(((Element) styles.item(i)).getAttributeNS(ns, "val"))) return true;
        }
        return false;
    }

    private static String acceptedText(Node node, String ns) {
        if (node instanceof Element element && ns.equals(element.getNamespaceURI())) {
            String local = element.getLocalName();
            if ("del".equals(local) || "delText".equals(local) || "moveFrom".equals(local) || "instrText".equals(local)) {
                return "";
            }
            if ("t".equals(local)) return element.getTextContent();
            if ("tab".equals(local) || "br".equals(local)) return " ";
        }
        StringBuilder result = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            result.append(acceptedText(child, ns));
        }
        return result.toString();
    }

    private static boolean containsRtlScript(String token) {
        return token.codePoints().anyMatch(point -> {
            var script = Character.UnicodeScript.of(point);
            return script == Character.UnicodeScript.ARABIC || script == Character.UnicodeScript.HEBREW;
        });
    }

    static void saveVerification(Path directory, Verification verification) throws IOException {
        Path destination = directory.resolve(VERIFICATION_FILE);
        Path temporary = directory.resolve(VERIFICATION_FILE + ".tmp");
        JobStore.checkUnlinked(destination);
        JobStore.checkUnlinked(temporary);
        byte[] bytes = JSON.writeValueAsBytes(verification);
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        SourceVersionStore.forceDirectory(directory);
    }

    static Verification loadVerification(Path directory) throws IOException {
        Path file = directory.resolve(VERIFICATION_FILE);
        JobStore.checkUnlinked(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(VERIFICATION_LIMIT + 1);
            if (bytes.length > VERIFICATION_LIMIT) throw new IOException("PDF verification metadata is too large.");
            Verification verification = JSON.readValue(bytes, Verification.class);
            if (verification == null || verification.revision() < 0) {
                throw new IOException("PDF verification metadata is invalid.");
            }
            return verification;
        } catch (RuntimeException exception) {
            throw new IOException("PDF verification metadata could not be read.", exception);
        }
    }

    static void deleteVerification(Path directory) throws IOException {
        Path file = directory.resolve(VERIFICATION_FILE);
        JobStore.checkUnlinked(file);
        Files.deleteIfExists(file);
    }

    static Report evaluateJob(Job job, Path directory, String source, List<DocumentInspector.Finding> findings) {
        if (!"COMPLETED".equals(job.status())) return null;
        AutoPolish.Report autoPolish = null;
        try { autoPolish = AutoPolish.read(directory.resolve("output")); }
        catch (IOException | RuntimeException exception) { LOG.warn("Cannot read auto-polish report for {}", job.id(), exception); }

        CompilerDiagnostics.Report diagnostics = null;
        if (!List.of("QUEUED", "COMPILING").contains(job.previewStatus())) {
            try { diagnostics = CompilerDiagnostics.load(directory); }
            catch (IOException | RuntimeException exception) { LOG.warn("Cannot read diagnostics snapshot for {}", job.id(), exception); }
        }

        Verification verification = null;
        if ("READY".equals(job.previewStatus())) {
            try { verification = loadVerification(directory); }
            catch (IOException | RuntimeException exception) { LOG.warn("Cannot read PDF verification for {}", job.id(), exception); }
        }
        return evaluate(job, directory, source, findings, diagnostics, autoPolish, verification);
    }

    static Report evaluate(Job job, Path directory, String source, List<DocumentInspector.Finding> findings,
                           CompilerDiagnostics.Report diagnostics, AutoPolish.Report autoPolish, Verification verification) {
        int revision = job.revision();
        boolean pdfVerified = "READY".equals(job.previewStatus())
                && verification != null
                && verification.revision() == revision
                && verification.pdfVerified();
        Integer pageCount = pdfVerified ? verification.pageCount() : null;
        Double textCoverage = pdfVerified ? verification.textCoverage() : null;

        List<AutoPolish.Fix> autoFixes = autoPolish == null ? List.of() : autoPolish.fixes();
        var handled = new LinkedHashSet<String>();
        for (AutoPolish.Fix fix : autoFixes) handled.add(fix.detail());

        boolean hasUnfixedCaptions = source != null && !CaptionRepairs.preview(source, revision, List.of()).proposals().isEmpty();
        if (findings.stream().anyMatch(f -> "captions".equals(f.code()))
                && !hasUnfixedCaptions
                && autoFixes.stream().noneMatch(f -> "duplicate-captions".equals(f.code()))) {
            handled.add("Duplicate figure or table numbers were cleaned up automatically.");
        }

        boolean unsupportedMediaRemaining = hasUnsupportedMedia(directory);
        if (findings.stream().anyMatch(f -> "image_formats".equals(f.code()))
                && !unsupportedMediaRemaining
                && autoFixes.stream().noneMatch(f -> "image-conversion".equals(f.code()))) {
            handled.add("Unsupported image formats were replaced or removed from the source.");
        }

        Map<String, Item> actions = new LinkedHashMap<>();
        boolean buildFailed = "FAILED".equals(job.previewStatus());

        if (diagnostics != null && Integer.valueOf(revision).equals(diagnostics.revision())) {
            if (buildFailed) {
                var firstError = diagnostics.items().stream()
                        .filter(item -> "error".equals(item.severity()))
                        .findFirst();
                String code = firstError.map(CompilerDiagnostics.Diagnostic::code).orElse("");
                String guidance = switch (code) {
                    case "unsupported-image", "missing-file" ->
                            "An image or file could not be included in the PDF. Save embedded images as PNG or JPEG in Word and convert again.";
                    case "missing-font" ->
                            "A required font was unavailable during PDF creation. Check Setup diagnostics or open Source to adjust fonts.";
                    default ->
                            "Something in this document stopped PDF creation. You can still download the LaTeX ZIP or open Source to inspect it.";
                };
                actions.put("pdf-failed", new Item("pdf-failed", "blocking", "PDF could not be built", guidance));
            }
            for (var item : diagnostics.items()) {
                if ("missing-glyph".equals(item.code())) {
                    actions.putIfAbsent("missing-glyph", new Item("missing-glyph", "blocking",
                            "Some characters did not appear in the PDF",
                            "A required font character was missing, so some text may look blank. Check the PDF preview carefully."));
                } else if (("unsupported-image".equals(item.code()) || "missing-file".equals(item.code())) && !buildFailed) {
                    actions.putIfAbsent("missing-image", new Item("missing-image", "blocking",
                            "An image could not be added to the PDF",
                            "Save embedded images as PNG or JPEG in Word and convert again."));
                } else if ("overfull-box".equals(item.code())) {
                    actions.putIfAbsent("overfull-box", new Item("overfull-box", "note",
                            "Some lines or tables may run past the page margin",
                            "Glance through the PDF preview for clipped tables or wide equations."));
                }
            }
        } else if (buildFailed) {
            actions.put("pdf-failed", new Item("pdf-failed", "blocking",
                    "PDF could not be built",
                    "Something in this document stopped PDF creation. You can still download the LaTeX ZIP or open Source to inspect it."));
        }

        if (pdfVerified && verification.checkedTokens() >= 10 && textCoverage != null && textCoverage < 0.70) {
            actions.putIfAbsent("low-text-coverage", new Item("low-text-coverage", "blocking",
                    "Some document text may be missing from the PDF",
                    "Compare the PDF preview against your Word file before sharing."));
        } else if (!pdfVerified && !buildFailed) {
            actions.putIfAbsent("pdf-unverified", new Item("pdf-unverified", "note",
                    "PDF has not been checked yet",
                    "Click Build PDF to verify the finished document, or download the LaTeX ZIP now."));
        }

        boolean aiColumnsApplied = findings.stream()
                .anyMatch(f -> "ai-layout".equals(f.code()) && f.detail().contains("two-column"));

        for (var finding : findings) {
            switch (finding.code()) {
                case "charts" -> actions.putIfAbsent("charts", new Item("charts", "blocking",
                        "Word chart could not be converted",
                        "Export the chart as a PNG image in Word, insert it into your document, and convert again."));
                case "objects" -> actions.putIfAbsent("objects", new Item("objects", "blocking",
                        "Embedded Word object may be missing",
                        "Replace embedded objects or legacy equations with native Word equations or PNG images."));
                case "altchunk" -> actions.putIfAbsent("altchunk", new Item("altchunk", "blocking",
                        "Imported Word section may be missing",
                        "Open the document in Word, save a fresh .docx copy, and convert again."));
                case "image_formats" -> {
                    if (unsupportedMediaRemaining) {
                        actions.putIfAbsent("image_formats", new Item("image_formats", "blocking",
                                "Unsupported image format in Word file",
                                "Save vector or unusual images (EMF, WMF, SVG) as PNG in Word and convert again."));
                    }
                }
                case "captions" -> {
                    if (hasUnfixedCaptions) {
                        actions.putIfAbsent("captions", new Item("captions", "note",
                                "Figure or table captions may have double numbers",
                                "Check captions in the preview or open Review to clean them up."));
                    }
                }
                case "merged" -> actions.putIfAbsent("merged", new Item("merged", "note",
                        "Check merged table cells",
                        "Glance at tables in the PDF preview to confirm rows and columns line up."));
                case "headers" -> actions.putIfAbsent("headers", new Item("headers", "note",
                        "Word headers or footers were not carried over",
                        "If your page headers or footers contain important info, check whether you need them."));
                case "textboxes" -> actions.putIfAbsent("textboxes", new Item("textboxes", "note",
                        "Check text box placement",
                        "Floating text boxes from Word were placed into regular paragraphs — confirm reading order."));
                case "columns" -> {
                    if (!aiColumnsApplied) {
                        actions.putIfAbsent("columns", new Item("columns", "note",
                                "Multi-column layout may differ",
                                "Check the PDF preview to confirm the column layout works for you."));
                    }
                }
                case "fonts", "metadata-fonts" -> actions.putIfAbsent("fonts", new Item("fonts", "note",
                        pdfVerified && !actions.containsKey("missing-glyph")
                                ? "Check multilingual text appearance"
                                : "Multilingual text detected",
                        pdfVerified && !actions.containsKey("missing-glyph")
                                ? "Bundled Noto fonts were used with no missing characters — glance at line breaks and alignment in the PDF."
                                : "Build the PDF to confirm all characters render cleanly."));
                case "references" -> actions.putIfAbsent("references", new Item("references", "note",
                        "Check cross-references",
                        "Verify figure and table reference numbers in the PDF preview."));
                case "bibliography" -> actions.putIfAbsent("bibliography", new Item("bibliography", "note",
                        "Check bibliography citations",
                        "Verify citations and references in the PDF preview."));
                case "inspection_limit" -> actions.putIfAbsent("inspection_limit", new Item("inspection_limit", "note",
                        "Large document — quick visual check suggested",
                        "Skim through the PDF preview to confirm all sections are present."));
                case "source-page-layout-review" -> {
                    if (isBenignLayoutFallback(finding.detail())) {
                        handled.add("Standard page size and margins were used because the Word file did not specify all page settings.");
                    } else {
                        actions.putIfAbsent("source-page-layout-review", new Item("source-page-layout-review", "note",
                                "Page settings need a quick check", finding.detail()));
                    }
                }
                case "revisions" -> handled.add("Word tracked changes were accepted automatically.");
                case "comments" -> handled.add("Word review comments were excluded from the document.");
                case "source-page-layout" -> handled.add("Original page size and margins were preserved.");
                case "ai-layout" -> handled.add("AI layout assistance checked headings and columns.");
                case "ai-layout-unavailable" -> { /* Optional enhancement; standard conversion handles layout cleanly. */ }
                default -> {
                    if ("warning".equals(finding.severity())) {
                        actions.putIfAbsent(finding.code(), new Item(finding.code(), "note", finding.title(), finding.detail()));
                    }
                }
            }
        }

        if (autoPolish != null && !autoPolish.skipped().isEmpty() && !actions.containsKey("image_formats")) {
            for (int i = 0; i < autoPolish.skipped().size(); i++) {
                actions.putIfAbsent("auto-polish-skipped-" + i, new Item("auto-polish-skipped-" + i, "note",
                        "Some items could not be fixed automatically", autoPolish.skipped().get(i)));
            }
        }

        List<Item> actionList = List.copyOf(actions.values());
        long blockingCount = actionList.stream().filter(item -> "blocking".equals(item.severity())).count();
        long noteCount = actionList.stream().filter(item -> "note".equals(item.severity())).count();

        String status;
        String headline;
        String summary;
        if (blockingCount > 0) {
            status = "needs-attention";
            headline = "Needs attention before use";
            summary = blockingCount == 1
                    ? "1 item needs your attention in Word or Source before sharing this document."
                    : blockingCount + " items need your attention in Word or Source before sharing this document.";
        } else if (noteCount > 0) {
            status = "ready-with-notes";
            headline = pdfVerified ? "Ready — quick look suggested" : "LaTeX ready — build PDF to verify";
            if (pdfVerified) {
                summary = "Built a " + pageLabel(pageCount) + " PDF" + fixPhrase(autoFixes) + ". "
                        + noteLabel(noteCount) + " worth a quick glance.";
            } else {
                summary = "Conversion finished" + fixPhrase(autoFixes) + ". " + noteLabel(noteCount) + " to check.";
            }
        } else {
            status = "ready";
            headline = "Ready to use";
            summary = "Verified " + pageLabel(pageCount) + " PDF" + coveragePhrase(textCoverage)
                    + fixPhrase(autoFixes) + ". Ready to download.";
        }

        return new Report(status, headline, summary, revision, pdfVerified, pageCount, textCoverage,
                autoFixes, actionList, List.copyOf(handled));
    }

    private static boolean hasUnsupportedMedia(Path directory) {
        if (directory == null) return false;
        Path media = directory.resolve("output").resolve("media");
        try {
            JobStore.checkUnlinked(media);
            if (!Files.isDirectory(media, LinkOption.NOFOLLOW_LINKS)) return false;
            try (var paths = Files.walk(media)) {
                var files = paths.filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .limit(MEDIA_WALK_LIMIT + 1)
                        .toList();
                if (files.size() > MEDIA_WALK_LIMIT) return true;
                for (Path file : files) {
                    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!lower.matches(".*\\.(png|jpe?g|pdf)$")) return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            return true;
        }
    }

    private static boolean isBenignLayoutFallback(String detail) {
        if (detail == null) return false;
        return detail.contains("No explicit page size and margins were found")
                || detail.contains("Some sections have incomplete page size or margin settings")
                || detail.contains("The document has no page settings");
    }

    private static String pageLabel(Integer pageCount) {
        int pages = pageCount == null ? 1 : pageCount;
        return pages + "-page";
    }

    private static String noteLabel(long count) {
        return count == 1 ? "1 item is" : count + " items are";
    }

    private static String fixPhrase(List<AutoPolish.Fix> fixes) {
        if (fixes == null || fixes.isEmpty()) return "";
        int total = fixes.stream().mapToInt(AutoPolish.Fix::count).sum();
        return " with " + total + " automatic " + (total == 1 ? "fix" : "fixes");
    }

    private static String coveragePhrase(Double coverage) {
        if (coverage == null) return "";
        long pct = Math.min(100, Math.max(0, Math.round(coverage * 100)));
        return " (" + pct + "% text match)";
    }
}
