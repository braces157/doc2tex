package dev.doc2tex;

import dev.doc2tex.PdfStudioModels.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** Extracts bounded text, Markdown, outlines, and deduplicated embedded images from PDFs. */
final class PdfStudioExtractor {
    private static final int MAX_FORM_DEPTH = 16;
    private static final int MAX_OUTLINE_DEPTH = 8;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PdfStudioExtractor() {}

    static TextExtract extractText(PDDocument doc, String filename) throws IOException {
        int pageCount = doc.getNumberOfPages();
        int limit = Math.min(pageCount, PdfStudioService.MAX_OUTPUT_PAGES);
        boolean pageLimitTruncated = pageCount > PdfStudioService.MAX_OUTPUT_PAGES;
        boolean budgetExhausted = false;
        List<PageText> pages = new ArrayList<>(limit);
        int totalChars = 0;
        PDFTextStripper stripper = new PDFTextStripper();

        for (int p = 1; p <= limit; p++) {
            stripper.setStartPage(p);
            stripper.setEndPage(p);
            String text = stripper.getText(doc).replace("\r\n", "\n").replace('\r', '\n').trim();
            int remaining = PdfStudioService.MAX_TEXT_CHARS - totalChars;
            if (remaining <= 0) {
                budgetExhausted = true;
                break;
            }
            if (text.length() > remaining) {
                text = text.substring(0, remaining);
                budgetExhausted = true;
            }
            totalChars += text.length();
            pages.add(new PageText(p, text));
            if (budgetExhausted) break;
        }
        return new TextExtract(filename, pageCount, totalChars, pageLimitTruncated || budgetExhausted, pages);
    }

    static MarkdownExtract extractMarkdown(PDDocument doc, String filename) throws IOException {
        TextExtract extract = extractText(doc, filename);
        String base = PdfStudioService.stripExtension(extract.filename());
        StringBuilder md = new StringBuilder();
        md.append("# ").append(base).append("\n\n");
        for (PageText page : extract.pages()) {
            md.append("## Page ").append(page.pageNumber()).append("\n\n");
            if (page.text().isEmpty()) {
                md.append("*[No selectable text on this page]*\n\n");
            } else {
                md.append(page.text().replaceAll("\\n{3,}", "\n\n").trim()).append("\n\n");
            }
        }
        String result = md.toString().trim() + "\n";
        boolean truncated = extract.truncated();
        if (result.length() > PdfStudioService.MAX_TEXT_CHARS) {
            result = result.substring(0, PdfStudioService.MAX_TEXT_CHARS);
            truncated = true;
        }
        return new MarkdownExtract(extract.filename(), extract.pageCount(), result.length(), truncated, result);
    }

    static BinaryResult extractImagesZip(PDDocument doc, String filename) throws IOException {
        String base = PdfStudioService.stripExtension(filename);
        try (ByteArrayOutputStream zipBytes = new ByteArrayOutputStream()) {
            int pageCount = doc.getNumberOfPages();
            int limit = Math.min(pageCount, PdfStudioService.MAX_OUTPUT_PAGES);
            Set<COSBase> seenGlobalObjs = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<COSObjectKey> seenGlobalKeys = new LinkedHashSet<>();
            List<Map<String, Object>> manifestEntries = new ArrayList<>();
            long totalImageBytes = 0;
            boolean pageLimitTruncated = pageCount > PdfStudioService.MAX_OUTPUT_PAGES;
            boolean budgetExhausted = false;

            try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
                for (int i = 0; i < limit; i++) {
                    int pageNum = i + 1;
                    PDPage page = doc.getPage(i);
                    List<PDImageXObject> pageImages = new ArrayList<>();
                    collectPageImages(page.getResources(), 0,
                            Collections.newSetFromMap(new IdentityHashMap<>()),
                            seenGlobalObjs, seenGlobalKeys, pageImages);

                    int imgIndex = 0;
                    for (PDImageXObject image : pageImages) {
                        if (manifestEntries.size() >= PdfStudioService.MAX_IMAGES) {
                            budgetExhausted = true;
                            break;
                        }
                        if ((long) image.getWidth() * image.getHeight() > MAX_IMAGE_PIXELS) continue;
                        BufferedImage buffered;
                        try {
                            buffered = image.getImage();
                        } catch (IOException | RuntimeException ignored) {
                            continue;
                        }
                        if (buffered == null) continue;
                        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                        if (!ImageIO.write(buffered, "png", pngOut)) continue;
                        byte[] png = pngOut.toByteArray();
                        if (totalImageBytes + png.length > PdfStudioService.MAX_ZIP_IMAGE_BYTES) {
                            budgetExhausted = true;
                            break;
                        }
                        totalImageBytes += png.length;
                        imgIndex++;
                        String entryName = String.format(Locale.ROOT, "page-%03d-img-%02d.png", pageNum, imgIndex);
                        zip.putNextEntry(new ZipEntry(entryName));
                        zip.write(png);
                        zip.closeEntry();
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", entryName);
                        item.put("page", pageNum);
                        item.put("width", buffered.getWidth());
                        item.put("height", buffered.getHeight());
                        item.put("bytes", png.length);
                        manifestEntries.add(item);
                    }
                    if (budgetExhausted) break;
                }

                if (manifestEntries.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "No embedded raster images were found in this PDF.");
                }

                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("filename", filename);
                manifest.put("pageCount", pageCount);
                manifest.put("imageCount", manifestEntries.size());
                manifest.put("truncated", pageLimitTruncated || budgetExhausted);
                manifest.put("images", manifestEntries);
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zip.closeEntry();
            }

            return new BinaryResult(
                    PdfStudioService.sanitizeDownloadName(base + "-images.zip", "images.zip", ".zip"),
                    zipBytes.toByteArray());
        }
    }

    static void collectPageImages(
            PDResources resources,
            int depth,
            Set<COSBase> visitedForms,
            Set<COSBase> seenObjs,
            Set<COSObjectKey> seenKeys,
            List<PDImageXObject> out) throws IOException {
        if (resources == null || depth > MAX_FORM_DEPTH) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                COSObjectKey key = image.getCOSObject().getKey();
                boolean freshObj = seenObjs.add(image.getCOSObject());
                boolean freshKey = key == null || seenKeys.add(key);
                if (freshObj && freshKey) out.add(image);
            } else if (xObject instanceof PDFormXObject form && visitedForms.add(form.getCOSObject())) {
                collectPageImages(form.getResources(), depth + 1, visitedForms, seenObjs, seenKeys, out);
            }
        }
    }

    static void collectOutline(
            PDDocument doc,
            PDOutlineNode node,
            int level,
            List<OutlineItem> out,
            Set<COSBase> visited) {
        if (node == null || level > MAX_OUTLINE_DEPTH || out.size() >= PdfStudioService.MAX_OUTLINE_ITEMS) return;
        for (PDOutlineItem item : node.children()) {
            if (out.size() >= PdfStudioService.MAX_OUTLINE_ITEMS) break;
            if (!visited.add(item.getCOSObject())) continue;
            String title = safeField(item.getTitle());
            if (title.isEmpty()) title = "(Untitled section)";
            Integer pageNumber = null;
            try {
                PDPage dest = item.findDestinationPage(doc);
                if (dest != null) {
                    int index = doc.getPages().indexOf(dest);
                    if (index >= 0) pageNumber = index + 1;
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep outline item with null page when destination cannot be resolved.
            }
            out.add(new OutlineItem(title, level, pageNumber));
            collectOutline(doc, item, level + 1, out, visited);
        }
    }

    static String safeField(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }
}
