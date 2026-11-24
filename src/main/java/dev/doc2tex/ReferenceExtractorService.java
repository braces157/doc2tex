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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Offline citation identifier (DOI / arXiv / ISBN / reference-line) extractor emitting BibTeX. */
@Service
class ReferenceExtractorService {
    private static final int MAX_TEXT_CHARS = 500_000;
    private static final int MAX_REFS = 250;
    private static final int MAX_PDF_PAGES = 120;
    private static final Pattern DOI_PATTERN = Pattern.compile("\\b(10\\.\\d{4,9}/[-._;()/:<>#+A-Za-z0-9]+)");
    private static final Pattern ARXIV_PATTERN = Pattern.compile("\\barXiv:(\\d{4}\\.\\d{4,5}(?:v\\d+)?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISBN_PATTERN = Pattern.compile("\\bISBN(?:-1[03])?:?\\s*([0-9][0-9Xx-]{8,15}[0-9Xx])\\b");
    private static final Pattern NUMBERED_REF = Pattern.compile("(?m)^(?:\\[(\\d{1,3})]|(\\d{1,3})\\.)\\s+(.{18,400})$");
    private static final Pattern REF_SECTION_HEADING = Pattern.compile(
            "(?im)^(?:\\d+(?:\\.\\d+)?\\.?\\s+)?(?:references|bibliography|works cited|literature cited)\\s*$");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20[0-3]\\d)\\b");

    record ExtractedRef(String key, String type, String title, String author, String year, String doi, String arxiv, String raw) {}
    record ExtractionReport(String sourceName, int totalFound, List<ExtractedRef> entries, String bibtex, List<String> warnings) {}

    private final DocxValidator docxValidator;

    ReferenceExtractorService(DocxValidator docxValidator) {
        this.docxValidator = docxValidator;
    }

    ExtractionReport extract(MultipartFile file, String rawText) throws IOException {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = rawText != null && !rawText.isBlank();
        if (!hasFile && !hasText) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload a PDF/DOCX/text file or paste reference text to extract.");
        }

        String sourceName = "pasted-text.txt";
        List<String> warnings = new ArrayList<>();
        String text;
        if (hasFile) {
            sourceName = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document");
            text = readUploadedText(file, sourceName, warnings);
        } else {
            if (rawText.length() > MAX_TEXT_CHARS) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Pasted reference text exceeds the 500,000-character limit.");
            }
            text = rawText;
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Extracted document text exceeds the 500,000-character limit. Upload a shorter section or bibliography file.");
        }

        Map<String, ExtractedRef> entriesByDedupe = new LinkedHashMap<>();
        Set<String> usedKeys = new LinkedHashSet<>();

        Matcher doiMatcher = DOI_PATTERN.matcher(text);
        while (doiMatcher.find() && entriesByDedupe.size() < MAX_REFS) {
            String doi = stripTrailingPunctuation(doiMatcher.group(1));
            if (doi.length() < 8) continue;
            String dedupe = "doi:" + doi.toLowerCase(Locale.ROOT);
            if (entriesByDedupe.containsKey(dedupe)) continue;
            String snippet = lineAround(text, doiMatcher.start(), doiMatcher.end());
            String year = findYear(snippet);
            String key = uniqueKey("doi_" + doi.replaceAll("[^A-Za-z0-9._+-]", "_"), usedKeys);
            entriesByDedupe.put(dedupe, new ExtractedRef(
                    key, "article", inferTitle(snippet, "Reference " + doi), "Unknown Author", year, doi, "", snippet));
        }

        Matcher arxivMatcher = ARXIV_PATTERN.matcher(text);
        while (arxivMatcher.find() && entriesByDedupe.size() < MAX_REFS) {
            String arxiv = arxivMatcher.group(1);
            String dedupe = "arxiv:" + arxiv.toLowerCase(Locale.ROOT);
            if (entriesByDedupe.containsKey(dedupe)) continue;
            String snippet = lineAround(text, arxivMatcher.start(), arxivMatcher.end());
            String year = "20" + arxiv.substring(0, 2);
            String key = uniqueKey("arxiv_" + arxiv.replace('.', '_'), usedKeys);
            entriesByDedupe.put(dedupe, new ExtractedRef(
                    key, "misc", inferTitle(snippet, "arXiv preprint " + arxiv), "Unknown Author", year, "", arxiv, snippet));
        }

        Matcher isbnMatcher = ISBN_PATTERN.matcher(text);
        while (isbnMatcher.find() && entriesByDedupe.size() < MAX_REFS) {
            String isbn = isbnMatcher.group(1).replaceAll("[^0-9Xx]", "");
            if (isbn.length() != 10 && isbn.length() != 13) continue;
            String dedupe = "isbn:" + isbn;
            if (entriesByDedupe.containsKey(dedupe)) continue;
            String snippet = lineAround(text, isbnMatcher.start(), isbnMatcher.end());
            String key = uniqueKey("isbn_" + isbn, usedKeys);
            entriesByDedupe.put(dedupe, new ExtractedRef(
                    key, "book", inferTitle(snippet, "Book ISBN " + isbn), "Unknown Author", findYear(snippet), "", "", snippet));
        }

        String numberedScope = scopeToReferencesSection(text);
        Matcher refLineMatcher = NUMBERED_REF.matcher(numberedScope);
        while (refLineMatcher.find() && entriesByDedupe.size() < MAX_REFS) {
            String body = refLineMatcher.group(3).trim();
            if (DOI_PATTERN.matcher(body).find() || ARXIV_PATTERN.matcher(body).find()) continue;
            String num = refLineMatcher.group(1) != null ? refLineMatcher.group(1) : refLineMatcher.group(2);
            String dedupe = "line:" + body.toLowerCase(Locale.ROOT);
            if (entriesByDedupe.containsKey(dedupe)) continue;
            String year = findYear(body);
            String key = uniqueKey("ref_" + num + (year.isEmpty() ? "" : "_" + year), usedKeys);
            entriesByDedupe.put(dedupe, new ExtractedRef(
                    key, "misc", inferTitle(body, "Reference [" + num + "]"), inferAuthor(body), year, "", "", body));
        }

        List<ExtractedRef> entries = List.copyOf(entriesByDedupe.values());
        String bibtex = formatBibtex(entries);
        if (!entries.isEmpty()) {
            BibtexLibrary.parse(bibtex.getBytes(StandardCharsets.UTF_8));
        }

        warnings.add("Offline identifier extraction scans DOIs, arXiv IDs, ISBNs, and numbered reference lines without querying remote APIs.");
        if (entries.isEmpty()) {
            warnings.add("No DOIs, arXiv IDs, ISBNs, or numbered references were detected.");
        }
        return new ExtractionReport(sourceName, entries.size(), entries, bibtex, warnings);
    }

    private String readUploadedText(MultipartFile file, String filename, List<String> warnings) throws IOException {
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file must be 20 MB or smaller.");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            byte[] bytes = PdfStudioService.readAndValidatePdfBytes(file);
            try (PDDocument doc = PdfStudioService.loadPdf(bytes, "")) {
                int pageCount = doc.getNumberOfPages();
                PDFTextStripper stripper = new PDFTextStripper();
                if (pageCount <= MAX_PDF_PAGES) {
                    return stripper.getText(doc);
                }
                // Scan both front matter and tail pages so bibliographies at the end of long theses are captured.
                int half = MAX_PDF_PAGES / 2;
                stripper.setStartPage(1);
                stripper.setEndPage(half);
                String head = stripper.getText(doc);
                int tailStart = pageCount - half + 1;
                stripper.setStartPage(tailStart);
                stripper.setEndPage(pageCount);
                String tail = stripper.getText(doc);
                warnings.add("PDF has " + pageCount + " pages; scanned pages 1–" + half + " and " + tailStart
                        + "–" + pageCount + " (middle pages omitted).");
                return head + "\n" + tail;
            }
        }
        if (lower.endsWith(".docx")) {
            Path tempDir = Files.createTempDirectory("doc2tex-refs-");
            try {
                Path docx = tempDir.resolve("input.docx");
                file.transferTo(docx);
                docxValidator.validate(docx);
                try (ZipFile zip = new ZipFile(docx.toFile())) {
                    ZipEntry entry = zip.getEntry("word/document.xml");
                    if (entry == null) return "";
                    try (InputStream stream = zip.getInputStream(entry)) {
                        byte[] xml = stream.readNBytes(8 * 1024 * 1024);
                        return extractDocxParagraphText(xml);
                    }
                }
            } finally {
                SourceVersionStore.removeTreeQuietly(tempDir);
            }
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".tex") || lower.endsWith(".bib")) {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a .pdf, .docx, .txt, .md, .tex, or .bib file.");
    }

    private static String extractDocxParagraphText(byte[] xmlBytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xmlBytes));
            NodeList paragraphs = doc.getElementsByTagNameNS("*", "p");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                String pText = paragraphs.item(i).getTextContent();
                if (pText != null && !pText.isBlank()) {
                    out.append(pText.trim()).append('\n');
                }
            }
            return out.toString();
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Could not read text from DOCX XML.", exception);
        }
    }

    private static String formatBibtex(List<ExtractedRef> entries) {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ExtractedRef e : entries) {
            sb.append('@').append(e.type()).append('{').append(e.key()).append(",\n");
            sb.append("  author = {").append(cleanBibField(e.author())).append("},\n");
            sb.append("  title = {").append(cleanBibField(e.title())).append("}");
            if (!e.year().isEmpty()) sb.append(",\n  year = {").append(e.year()).append("}");
            if (!e.doi().isEmpty()) sb.append(",\n  doi = {").append(cleanBibField(e.doi())).append("}");
            if (!e.arxiv().isEmpty()) {
                sb.append(",\n  eprint = {").append(cleanBibField(e.arxiv())).append("},\n  archivePrefix = {arXiv}");
            }
            sb.append("\n}\n\n");
        }
        return sb.toString();
    }

    private static String uniqueKey(String candidate, Set<String> used) {
        String clean = candidate.replaceAll("[^A-Za-z0-9:._+-]", "_");
        if (clean.isEmpty() || !Character.isLetterOrDigit(clean.charAt(0)) && clean.charAt(0) != '_') {
            clean = "ref_" + clean;
        }
        if (clean.length() > 110) clean = clean.substring(0, 110);
        String chosen = clean;
        int suffix = 2;
        while (!used.add(chosen.toLowerCase(Locale.ROOT))) {
            chosen = clean + "_" + suffix++;
        }
        return chosen;
    }

    private static String scopeToReferencesSection(String text) {
        if (text.length() <= 4000) return text;
        Matcher m = REF_SECTION_HEADING.matcher(text);
        int lastMatch = -1;
        while (m.find()) lastMatch = m.end();
        return lastMatch >= 0 ? text.substring(lastMatch) : text;
    }

    private static String cleanBibField(String value) {
        if (value == null) return "";
        // Remove backslashes as well as braces so a trailing '\' can never escape the closing '}' in BibTeX.
        return value.replaceAll("[\\p{Cntrl}]+", " ")
                .replace("\\", " ")
                .replace("{", "(")
                .replace("}", ")")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String stripTrailingPunctuation(String doi) {
        String s = doi;
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':' || last == ']') {
                s = s.substring(0, s.length() - 1);
            } else if (last == ')' && countChar(s, ')') > countChar(s, '(')) {
                s = s.substring(0, s.length() - 1);
            } else if (last == '>' && countChar(s, '>') > countChar(s, '<')) {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }

    private static int countChar(String s, char target) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) count++;
        }
        return count;
    }

    private static String lineAround(String text, int start, int end) {
        int left = text.lastIndexOf('\n', start);
        int right = text.indexOf('\n', end);
        String line = text.substring(left < 0 ? 0 : left + 1, right < 0 ? text.length() : right).trim();
        if (left > 0 && (line.length() < 45 || line.toLowerCase(Locale.ROOT).startsWith("arxiv:")
                || line.toLowerCase(Locale.ROOT).startsWith("preprint ") || Character.isLowerCase(line.charAt(0)))) {
            int prevLeft = text.lastIndexOf('\n', left - 1);
            String prev = text.substring(prevLeft < 0 ? 0 : prevLeft + 1, left).trim();
            if (!prev.isEmpty() && prev.length() < 180) {
                line = prev.endsWith("-") ? prev.substring(0, prev.length() - 1) + line : prev + " " + line;
            }
        }
        return line.length() > 240 ? line.substring(0, 240) : line;
    }

    private static String findYear(String snippet) {
        Matcher m = YEAR_PATTERN.matcher(snippet);
        return m.find() ? m.group(1) : "";
    }

    private static String inferAuthor(String body) {
        int comma = body.indexOf(',');
        int paren = body.indexOf('(');
        int cut = comma > 2 && comma < 45 ? comma : (paren > 2 && paren < 45 ? paren : -1);
        return cut > 0 ? body.substring(0, cut).trim() : "Unknown Author";
    }

    private static String inferTitle(String snippet, String fallback) {
        String trimmed = snippet.trim();
        if (trimmed.length() <= 140 && trimmed.length() >= 8) return trimmed;
        return trimmed.length() > 140 ? trimmed.substring(0, 140) : fallback;
    }
}
