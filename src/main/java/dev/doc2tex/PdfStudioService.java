package dev.doc2tex;

import dev.doc2tex.PdfStudioModels.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class PdfStudioService {
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    static final int MAX_FILES = 20;
    static final int MAX_OUTPUT_PAGES = 500;
    static final int MAX_OUTLINE_ITEMS = 200;
    static final int MAX_TEXT_CHARS = 2_000_000;
    static final int MAX_IMAGES = 200;
    static final long MAX_ZIP_IMAGE_BYTES = 80L * 1024 * 1024;
    static final int MAX_WATERMARK_LEN = 120;

    private final Path fontDir;

    @org.springframework.beans.factory.annotation.Autowired
    PdfStudioService() {
        this(Path.of(System.getenv().getOrDefault("DOC2TEX_FONT_DIR", ".tools/fonts")));
    }

    PdfStudioService(Path fontDir) {
        this.fontDir = fontDir;
    }

    Inspection inspect(MultipartFile file, String password) throws IOException {
        byte[] bytes = readAndValidatePdfBytes(file);
        String filename = sanitizeDisplayFilename(file.getOriginalFilename(), "document.pdf");
        try (PDDocument doc = loadPdf(bytes, password)) {
            int pageCount = doc.getNumberOfPages();
            int inspectedPages = Math.min(pageCount, MAX_OUTPUT_PAGES);
            Metadata metadata = readMetadata(doc.getDocumentInformation());
            boolean hasMetadata = hasAnyMetadata(doc.getDocumentInformation(), metadata);
            boolean hasXmp = doc.getDocumentCatalog().getMetadata() != null;

            List<OutlineItem> outline = new ArrayList<>();
            PDDocumentOutline rootOutline = doc.getDocumentCatalog().getDocumentOutline();
            if (rootOutline != null) {
                PdfStudioExtractor.collectOutline(
                        doc, rootOutline, 0, outline, Collections.newSetFromMap(new IdentityHashMap<>()));
            }

            Set<String> fonts = new LinkedHashSet<>();
            Set<COSObjectKey> uniqueImageKeys = new LinkedHashSet<>();
            Set<COSBase> uniqueDirectImageObjs = Collections.newSetFromMap(new IdentityHashMap<>());
            List<PageInfo> pages = new ArrayList<>(inspectedPages);
            int totalChars = 0;
            int totalAnnotations = 0;
            PDFTextStripper stripper = new PDFTextStripper();

            for (int i = 0; i < inspectedPages; i++) {
                PDPage page = doc.getPage(i);
                int pageNum = i + 1;
                int rotation = Math.floorMod(page.getRotation(), 360);
                PDRectangle box = PdfStudioStamper.visibleBox(page);
                double widthPt = rotation % 180 == 90 ? box.getHeight() : box.getWidth();
                double heightPt = rotation % 180 == 90 ? box.getWidth() : box.getHeight();

                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                int charCount = countNonWhitespace(stripper.getText(doc));
                totalChars += charCount;

                int annotations = page.getAnnotations().size();
                totalAnnotations += annotations;

                PDResources resources = page.getResources();
                if (resources != null) {
                    for (COSName fontName : resources.getFontNames()) fonts.add(fontName.getName());
                }
                List<PDImageXObject> pageImages = new ArrayList<>();
                PdfStudioExtractor.collectPageImages(resources, 0,
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        new LinkedHashSet<>(),
                        pageImages);
                for (PDImageXObject img : pageImages) {
                    COSObjectKey key = img.getCOSObject().getKey();
                    if (key != null) uniqueImageKeys.add(key);
                    else uniqueDirectImageObjs.add(img.getCOSObject());
                }
                pages.add(new PageInfo(pageNum, round1(widthPt * 25.4 / 72.0), round1(heightPt * 25.4 / 72.0),
                        rotation, charCount, pageImages.size(), annotations));
            }

            int imageCount = uniqueImageKeys.size() + uniqueDirectImageObjs.size();
            List<String> warnings = buildInspectionWarnings(pageCount, totalChars, hasMetadata, hasXmp, totalAnnotations);

            return new Inspection(
                    filename, bytes.length, String.format(Locale.ROOT, "%.1f", doc.getVersion()),
                    pageCount, doc.isEncrypted(), hasMetadata, hasXmp, !outline.isEmpty(),
                    imageCount, totalAnnotations, fonts.size(), metadata, pages, outline, warnings);
        }
    }

    BinaryResult assemble(List<MultipartFile> files, AssemblePlan plan) throws IOException {
        if (files == null || files.isEmpty()) throw bad("Upload at least one PDF file to assemble.");
        if (files.size() > MAX_FILES) throw bad("Choose at most " + MAX_FILES + " PDF files at a time.");
        if (plan == null || plan.pages() == null || plan.pages().isEmpty()) {
            throw bad("Select at least one page to assemble.");
        }
        if (plan.pages().size() > MAX_OUTPUT_PAGES) {
            throw bad("Assembled PDFs are limited to " + MAX_OUTPUT_PAGES + " pages.");
        }

        String watermark = plan.watermarkText() == null ? "" : plan.watermarkText().trim();
        if (watermark.length() > MAX_WATERMARK_LEN || containsControlChars(watermark)) {
            throw bad("Watermark text must be at most " + MAX_WATERMARK_LEN + " characters without control characters.");
        }
        double opacityValue = plan.watermarkOpacity() == null ? 0.18 : plan.watermarkOpacity();
        if (!Double.isFinite(opacityValue)) throw bad("Choose a valid watermark opacity.");
        float opacity = (float) Math.max(0.05, Math.min(0.60, opacityValue));

        long totalBytes = 0;
        List<byte[]> fileBytes = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            byte[] data = readAndValidatePdfBytes(file);
            totalBytes += data.length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Combined PDF uploads cannot exceed 50 MB.");
            }
            fileBytes.add(data);
        }

        for (PageSpec spec : plan.pages()) {
            if (spec == null) throw bad("Invalid page specification.");
            if (spec.fileIndex() < 0 || spec.fileIndex() >= files.size()) {
                throw bad("A selected page refers to an unknown uploaded PDF.");
            }
            if (!Set.of(0, 90, 180, 270).contains(spec.rotation())) {
                throw bad("Page rotation must be 0, 90, 180, or 270 degrees.");
            }
        }

        List<String> passwords = plan.passwords() == null ? List.of() : plan.passwords();
        List<PDDocument> sources = new ArrayList<>(fileBytes.size());
        try {
            for (int i = 0; i < fileBytes.size(); i++) {
                String pw = i < passwords.size() && passwords.get(i) != null ? passwords.get(i) : "";
                sources.add(loadPdf(fileBytes.get(i), pw));
            }
            try (PDDocument target = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Set<COSBase> scrubbed = Collections.newSetFromMap(new IdentityHashMap<>());
                for (PageSpec spec : plan.pages()) {
                    PDDocument source = sources.get(spec.fileIndex());
                    if (spec.pageNumber() < 1 || spec.pageNumber() > source.getNumberOfPages()) {
                        throw bad("Page " + spec.pageNumber() + " does not exist in source PDF #" + (spec.fileIndex() + 1) + ".");
                    }
                    PDPage sourcePage = source.getPage(spec.pageNumber() - 1);
                    PDPage imported = target.importPage(sourcePage);
                    // PDFBox importPage copies the page dictionary shallowly without inherited /Resources or boxes.
                    if (!imported.getCOSObject().containsKey(COSName.RESOURCES) && sourcePage.getResources() != null) {
                        imported.setResources(sourcePage.getResources());
                    }
                    if (!imported.getCOSObject().containsKey(COSName.MEDIA_BOX) && sourcePage.getMediaBox() != null) {
                        imported.setMediaBox(sourcePage.getMediaBox());
                    }
                    if (!imported.getCOSObject().containsKey(COSName.CROP_BOX)
                            && sourcePage.getCropBox() != null) {
                        imported.setCropBox(sourcePage.getCropBox());
                    }
                    if (spec.rotation() != 0) {
                        imported.setRotation(Math.floorMod(imported.getRotation() + spec.rotation(), 360));
                    }
                    if (plan.stripAnnotations()) imported.setAnnotations(List.of());
                    if (plan.scrubMetadata()) {
                        scrubPageAndResourceMetadata(imported, scrubbed);
                    }
                }

                if (plan.scrubMetadata()) {
                    target.setDocumentInformation(new PDDocumentInformation());
                    target.getDocumentCatalog().setMetadata(null);
                    target.getDocument().getTrailer().removeItem(COSName.ID);
                } else if (sources.size() == 1) {
                    target.setDocumentInformation(copyBasicInfo(sources.get(0).getDocumentInformation()));
                }

                if (!watermark.isEmpty() || plan.pageNumbers()) {
                    PdfStudioStamper.stampAllPages(target, fontDir, watermark, opacity, plan.pageNumbers());
                }

                target.save(out);
                String fallback = files.size() == 1
                        ? stripExtension(sanitizeDisplayFilename(files.get(0).getOriginalFilename(), "document.pdf")) + "-edited.pdf"
                        : "assembled.pdf";
                return new BinaryResult(sanitizeDownloadName(plan.outputName(), fallback, ".pdf"), out.toByteArray());
            }
        } finally {
            closeQuietly(sources);
        }
    }

    TextExtract extractText(MultipartFile file, String password) throws IOException {
        byte[] bytes = readAndValidatePdfBytes(file);
        String filename = sanitizeDisplayFilename(file.getOriginalFilename(), "document.pdf");
        try (PDDocument doc = loadPdf(bytes, password)) {
            return PdfStudioExtractor.extractText(doc, filename);
        }
    }

    MarkdownExtract extractMarkdown(MultipartFile file, String password) throws IOException {
        byte[] bytes = readAndValidatePdfBytes(file);
        String filename = sanitizeDisplayFilename(file.getOriginalFilename(), "document.pdf");
        try (PDDocument doc = loadPdf(bytes, password)) {
            return PdfStudioExtractor.extractMarkdown(doc, filename);
        }
    }

    BinaryResult extractImagesZip(MultipartFile file, String password) throws IOException {
        byte[] bytes = readAndValidatePdfBytes(file);
        String filename = sanitizeDisplayFilename(file.getOriginalFilename(), "document.pdf");
        try (PDDocument doc = loadPdf(bytes, password)) {
            return PdfStudioExtractor.extractImagesZip(doc, filename);
        }
    }

    private static void scrubPageAndResourceMetadata(PDPage page, Set<COSBase> visited) {
        page.setMetadata(null);
        page.getCOSObject().removeItem(COSName.PIECE_INFO);
        COSBase resources = page.getCOSObject().getDictionaryObject(COSName.RESOURCES);
        scrubMetadataTree(resources, visited);
    }

    private static void scrubMetadataTree(COSBase root, Set<COSBase> visited) {
        if (root == null) return;
        Deque<COSBase> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            COSBase node = queue.poll();
            if (node instanceof COSObject obj) {
                node = obj.getObject();
                if (node == null) continue;
            }
            if (!visited.add(node)) continue;
            if (node instanceof COSDictionary dict) {
                dict.removeItem(COSName.METADATA);
                dict.removeItem(COSName.PIECE_INFO);
                for (COSName key : List.copyOf(dict.keySet())) {
                    if (COSName.PARENT.equals(key) || COSName.P.equals(key)) continue;
                    COSBase child = dict.getDictionaryObject(key);
                    if (child != null) queue.add(child);
                }
            } else if (node instanceof COSArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    COSBase item = arr.getObject(i);
                    if (item != null) queue.add(item);
                }
            }
        }
    }

    private static List<String> buildInspectionWarnings(
            int pageCount, int totalChars, boolean hasMetadata, boolean hasXmp, int totalAnnotations) {
        List<String> warnings = new ArrayList<>();
        if (pageCount > 0 && totalChars == 0) {
            warnings.add("No selectable text layer was found. This PDF may be scanned or image-only.");
        }
        if (hasMetadata || hasXmp) {
            warnings.add("Document contains metadata (such as Author, Creator, or XMP). Use Privacy Scrub before double-blind sharing.");
        }
        if (totalAnnotations > 0) {
            warnings.add("Document contains " + totalAnnotations + " annotations or comments.");
        }
        if (pageCount > MAX_OUTPUT_PAGES) {
            warnings.add("Only the first " + MAX_OUTPUT_PAGES + " pages were inspected.");
        }
        return warnings;
    }

    static byte[] readAndValidatePdfBytes(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw bad("Choose a non-empty PDF file.");
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Each PDF file must be 20 MB or smaller.");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length == 0) throw bad("Choose a non-empty PDF file.");
        if (bytes.length > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Each PDF file must be 20 MB or smaller.");
        }
        String prefix = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        if (!prefix.contains("%PDF-")) throw bad("Choose a valid PDF file (missing %PDF header).");
        return bytes;
    }

    static PDDocument loadPdf(byte[] bytes, String password) {
        try {
            return Loader.loadPDF(bytes, password == null ? "" : password);
        } catch (InvalidPasswordException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "This PDF is password-protected. Provide the password or unlock it first.");
        } catch (IOException exception) {
            throw bad("This file could not be read as a valid PDF document.");
        }
    }

    private static Metadata readMetadata(PDDocumentInformation info) {
        if (info == null) return new Metadata("", "", "", "", "", "", "", "");
        return new Metadata(
                PdfStudioExtractor.safeField(info.getTitle()),
                PdfStudioExtractor.safeField(info.getAuthor()),
                PdfStudioExtractor.safeField(info.getSubject()),
                PdfStudioExtractor.safeField(info.getKeywords()),
                PdfStudioExtractor.safeField(info.getCreator()),
                PdfStudioExtractor.safeField(info.getProducer()),
                formatDate(info.getCreationDate()),
                formatDate(info.getModificationDate()));
    }

    private static boolean hasAnyMetadata(PDDocumentInformation info, Metadata metadata) {
        if (!metadata.title().isEmpty() || !metadata.author().isEmpty() || !metadata.subject().isEmpty()
                || !metadata.keywords().isEmpty() || !metadata.creator().isEmpty() || !metadata.producer().isEmpty()
                || !metadata.creationDate().isEmpty() || !metadata.modificationDate().isEmpty()) {
            return true;
        }
        if (info == null) return false;
        for (String key : info.getMetadataKeys()) {
            if (!PdfStudioExtractor.safeField(info.getCustomMetadataValue(key)).isEmpty()) return true;
        }
        return false;
    }

    private static PDDocumentInformation copyBasicInfo(PDDocumentInformation source) {
        PDDocumentInformation copy = new PDDocumentInformation();
        if (source == null) return copy;
        if (source.getTitle() != null) copy.setTitle(source.getTitle());
        if (source.getAuthor() != null) copy.setAuthor(source.getAuthor());
        if (source.getSubject() != null) copy.setSubject(source.getSubject());
        if (source.getKeywords() != null) copy.setKeywords(source.getKeywords());
        if (source.getCreator() != null) copy.setCreator(source.getCreator());
        if (source.getProducer() != null) copy.setProducer(source.getProducer());
        return copy;
    }

    private static String formatDate(Calendar calendar) {
        if (calendar == null) return "";
        try {
            ZoneOffset offset = ZoneOffset.UTC;
            if (calendar.getTimeZone() != null) {
                offset = ZoneOffset.ofTotalSeconds(calendar.getTimeZone().getOffset(calendar.getTimeInMillis()) / 1000);
            }
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(calendar.toInstant().atOffset(offset));
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static int countNonWhitespace(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) count++;
        }
        return count;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static boolean containsControlChars(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    static String sanitizeDisplayFilename(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String base = slash >= 0 ? raw.substring(slash + 1) : raw;
        String cleaned = base.replaceAll("[\\p{Cntrl}\"/<>|?*:]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return fallback;
        return cleaned.length() > 140 ? cleaned.substring(0, 140) : cleaned;
    }

    static String sanitizeDownloadName(String raw, String fallback, String extension) {
        String candidate = sanitizeDisplayFilename(raw == null || raw.isBlank() ? fallback : raw, fallback);
        if (!candidate.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
            candidate = stripExtension(candidate) + extension;
        }
        return candidate.length() > 120 ? candidate.substring(0, 120 - extension.length()) + extension : candidate;
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void closeQuietly(List<PDDocument> docs) {
        for (PDDocument doc : docs) {
            if (doc == null) continue;
            try { doc.close(); } catch (IOException ignored) {}
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
