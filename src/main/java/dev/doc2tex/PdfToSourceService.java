package dev.doc2tex;

import dev.doc2tex.PdfLayoutExtractor.PageLines;
import dev.doc2tex.PdfLayoutExtractor.TextLine;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
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
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Converts digital PDFs into structured Markdown, editable LaTeX, or DOCX drafts. */
@Service
class PdfToSourceService {
    private static final int MAX_CONVERT_PAGES = 120;
    private static final int MAX_AI_PAGES = 6;
    private static final int MAX_IMAGES = 60;
    private static final long MAX_IMAGE_BYTES = 30L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final int MAX_CHARS = 1_500_000;

    record PdfSourceResult(
            String filename,
            String format,
            int pageCount,
            int headingsFound,
            int imagesExtracted,
            boolean twoColumnDetected,
            boolean aiLayoutUsed,
            List<String> warnings,
            String content) {}

    record PdfSourceDownload(String filename, String mediaType, byte[] bytes, List<String> warnings) {}

    private record ExtractedAsset(int page, String relativePath, byte[] pngBytes) {}
    private record ImageExtraction(List<ExtractedAsset> assets, List<String> warnings) {}
    private record ConversionData(
            String baseName,
            int pageCount,
            int headingsFound,
            boolean twoColumnDetected,
            boolean aiLayoutUsed,
            List<String> warnings,
            String markdown,
            List<ExtractedAsset> assets) {}

    private final Tools tools;
    private final ToolRunner runner;
    private final AppProperties properties;
    private final AiLayoutService aiLayout;

    PdfToSourceService(Tools tools, ToolRunner runner, AppProperties properties, AiLayoutService aiLayout) {
        this.tools = tools;
        this.runner = runner;
        this.properties = properties;
        this.aiLayout = aiLayout;
    }

    PdfSourceResult convertToSource(MultipartFile file, String targetFormat, boolean useAiLayout, String password)
            throws IOException {
        String format = normalizePreviewFormat(targetFormat);
        byte[] bytes = PdfStudioService.readAndValidatePdfBytes(file);
        String filename = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document.pdf");
        try (PDDocument doc = PdfStudioService.loadPdf(bytes, password)) {
            ConversionData data = buildConversion(doc, filename, useAiLayout);
            String content = format.equals("latex")
                    ? convertMarkdownWithPandoc(data.markdown(), data.assets(), "latex", true)
                    : data.markdown();
            return new PdfSourceResult(
                    filename,
                    format,
                    data.pageCount(),
                    data.headingsFound(),
                    data.assets().size(),
                    data.twoColumnDetected(),
                    data.aiLayoutUsed(),
                    data.warnings(),
                    content);
        }
    }

    PdfSourceDownload downloadAs(MultipartFile file, String targetFormat, boolean useAiLayout, String password)
            throws IOException {
        String format = normalizeDownloadFormat(targetFormat);
        byte[] bytes = PdfStudioService.readAndValidatePdfBytes(file);
        String filename = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document.pdf");
        try (PDDocument doc = PdfStudioService.loadPdf(bytes, password)) {
            ConversionData data = buildConversion(doc, filename, useAiLayout);
            return switch (format) {
                case "markdown" -> new PdfSourceDownload(
                        PdfStudioService.sanitizeDownloadName(data.baseName() + ".md", "converted.md", ".md"),
                        "text/markdown;charset=UTF-8",
                        inlineAssetsAsDataUris(data.markdown(), data.assets()).getBytes(StandardCharsets.UTF_8),
                        data.warnings());
                case "latex-zip" -> {
                    String latex = convertMarkdownWithPandoc(data.markdown(), data.assets(), "latex", true);
                    byte[] zipBytes = buildLatexZip(data.baseName(), latex, data.assets(), data.warnings());
                    yield new PdfSourceDownload(
                            PdfStudioService.sanitizeDownloadName(data.baseName() + "-latex.zip", "latex-export.zip", ".zip"),
                            "application/zip",
                            zipBytes,
                            data.warnings());
                }
                case "docx" -> {
                    byte[] docxBytes = convertMarkdownToDocx(data.markdown(), data.assets());
                    yield new PdfSourceDownload(
                            PdfStudioService.sanitizeDownloadName(data.baseName() + ".docx", "converted.docx", ".docx"),
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            docxBytes,
                            data.warnings());
                }
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported target download format.");
            };
        }
    }

    private ConversionData buildConversion(PDDocument doc, String filename, boolean useAiLayout) throws IOException {
        String baseName = PdfStudioService.stripExtension(filename);
        int pageCount = doc.getNumberOfPages();
        int limit = Math.min(pageCount, MAX_CONVERT_PAGES);
        List<String> warnings = new ArrayList<>();
        if (pageCount > MAX_CONVERT_PAGES) {
            warnings.add("Only the first " + MAX_CONVERT_PAGES + " pages were converted.");
        }

        List<PageLines> allPages = new ArrayList<>(limit);
        List<Float> fontSizes = new ArrayList<>();
        int emptyPages = 0;
        for (int i = 0; i < limit; i++) {
            PDPage page = doc.getPage(i);
            PDRectangle box = PdfStudioStamper.visibleBox(page);
            int rot = Math.floorMod(page.getRotation(), 360);
            float w = rot % 180 == 90 ? box.getHeight() : box.getWidth();
            float h = rot % 180 == 90 ? box.getWidth() : box.getHeight();
            List<TextLine> lines = PdfLayoutExtractor.extractLines(doc, i + 1, w);
            if (lines.isEmpty()) emptyPages++;
            for (TextLine line : lines) {
                if (line.fontSize() > 4f) fontSizes.add(line.fontSize());
            }
            allPages.add(new PageLines(i + 1, w, h, lines));
        }

        if (emptyPages == limit && limit > 0) {
            warnings.add("No selectable text layer was found. Scanned PDFs require OCR before conversion.");
        } else if (emptyPages > 0) {
            warnings.add(emptyPages + " page(s) contained no selectable text and may be image-only.");
        }

        Set<String> repeatedMarginTexts = PdfLayoutExtractor.detectRepeatedMargins(allPages);
        if (!repeatedMarginTexts.isEmpty()) {
            warnings.add("Stripped " + repeatedMarginTexts.size() + " repeated running header/footer pattern(s).");
        }

        float bodyFontSize = PdfLayoutExtractor.medianFontSize(fontSizes);
        Map<Integer, List<DocLayoutModel.Region>> aiRegions = new HashMap<>();
        boolean aiUsed = false;
        if (useAiLayout) {
            if (aiLayout.modelReady()) {
                PDFRenderer renderer = new PDFRenderer(doc);
                int aiPages = Math.min(limit, MAX_AI_PAGES);
                for (int i = 0; i < aiPages; i++) {
                    PDPage page = doc.getPage(i);
                    PDRectangle crop = PdfStudioStamper.visibleBox(page);
                    float longest = Math.max(crop.getWidth(), crop.getHeight());
                    float dpi = Math.min(100f, 1400f * 72f / Math.max(1f, longest));
                    BufferedImage img = renderer.renderImageWithDPI(i, dpi);
                    List<DocLayoutModel.Region> detected = aiLayout.detectPage(img, 20);
                    if (!detected.isEmpty()) {
                        aiUsed = true;
                        aiRegions.put(i + 1, detected);
                    }
                }
            }
            if (!aiUsed) {
                warnings.add("Local AI layout model unavailable or inactive; used deterministic font and column heuristics.");
            }
        }

        ImageExtraction imageExtraction = extractImages(doc, limit);
        List<ExtractedAsset> assets = imageExtraction.assets();
        warnings.addAll(imageExtraction.warnings());
        Map<Integer, List<ExtractedAsset>> assetsByPage = new LinkedHashMap<>();
        for (ExtractedAsset asset : assets) {
            assetsByPage.computeIfAbsent(asset.page(), k -> new ArrayList<>()).add(asset);
        }

        StringBuilder md = new StringBuilder();
        md.append("# ").append(baseName).append("\n\n");
        int headingsFound = 0;
        boolean twoColumnDetected = false;

        for (PageLines page : allPages) {
            List<TextLine> cleaned = PdfLayoutExtractor.filterMarginLines(
                    page, repeatedMarginTexts, aiRegions.get(page.pageNumber()));
            boolean isTwoCol = PdfLayoutExtractor.isTwoColumnPage(cleaned, page.widthPt());
            if (isTwoCol) twoColumnDetected = true;
            List<TextLine> ordered = isTwoCol ? PdfLayoutExtractor.orderTwoColumns(cleaned, page.widthPt()) : cleaned;
            List<DocLayoutModel.Region> regions = aiRegions.getOrDefault(page.pageNumber(), List.of());

            float prevY = -1f;
            int prevCol = -1;
            int openHeadingLevel = 0;
            StringBuilder headingBuf = new StringBuilder();
            StringBuilder paragraph = new StringBuilder();

            for (TextLine line : ordered) {
                int headingLevel = PdfLayoutExtractor.classifyHeading(line, bodyFontSize, page, regions);
                boolean columnSwitch = prevCol >= 0 && line.column() != prevCol;
                boolean upwardJump = prevY >= 0 && line.y() < prevY - Math.max(6f, line.fontSize() * 0.5f);
                boolean verticalGap = prevY >= 0 && (line.y() - prevY) > Math.max(14f, line.fontSize() * 1.65f);

                if (headingLevel > 0) {
                    flushParagraph(md, paragraph);
                    boolean continueHeading = openHeadingLevel == headingLevel
                            && !columnSwitch && !upwardJump && !verticalGap
                            && headingBuf.length() + line.text().length() <= 160;
                    if (continueHeading) {
                        headingBuf.append(' ').append(line.text());
                    } else {
                        if (flushHeading(md, openHeadingLevel, headingBuf)) headingsFound++;
                        openHeadingLevel = headingLevel;
                        headingBuf.append(line.text());
                    }
                    prevY = line.y();
                    prevCol = line.column();
                    continue;
                }

                if (flushHeading(md, openHeadingLevel, headingBuf)) {
                    headingsFound++;
                    openHeadingLevel = 0;
                }

                if (columnSwitch || upwardJump || verticalGap) {
                    flushParagraph(md, paragraph);
                }
                if (paragraph.length() > 0) {
                    if (paragraph.charAt(paragraph.length() - 1) == '-' && !line.text().isEmpty()
                            && Character.isLowerCase(line.text().charAt(0))) {
                        paragraph.deleteCharAt(paragraph.length() - 1);
                    } else {
                        paragraph.append(' ');
                    }
                }
                paragraph.append(line.text());
                prevY = line.y();
                prevCol = line.column();
                if (md.length() + paragraph.length() >= MAX_CHARS) break;
            }
            if (flushHeading(md, openHeadingLevel, headingBuf)) headingsFound++;
            flushParagraph(md, paragraph);

            for (ExtractedAsset asset : assetsByPage.getOrDefault(page.pageNumber(), List.of())) {
                md.append("![Figure on page ").append(page.pageNumber()).append("](")
                        .append(asset.relativePath()).append(")\n\n");
            }
            if (md.length() >= MAX_CHARS) {
                warnings.add("Output reached the 1.5M character limit.");
                break;
            }
        }

        String markdown = md.toString().trim() + "\n";
        if (markdown.length() > MAX_CHARS) markdown = markdown.substring(0, MAX_CHARS);
        return new ConversionData(baseName, pageCount, headingsFound, twoColumnDetected, aiUsed, warnings, markdown, assets);
    }

    private String convertMarkdownWithPandoc(
            String markdown, List<ExtractedAsset> assets, String toFormat, boolean standalone) throws IOException {
        if (!tools.available(tools.pandoc())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Pandoc is unavailable. Run setup tools before converting.");
        }
        Path tempDir = Files.createTempDirectory("doc2tex-pdftosrc-");
        try {
            Files.writeString(tempDir.resolve("input.md"), markdown, StandardCharsets.UTF_8);
            writeAssets(tempDir, assets);
            List<String> cmd = new ArrayList<>(List.of(
                    tools.pandoc(), "--sandbox", "--from=markdown", "--to=" + toFormat, "--wrap=none"));
            if (standalone) cmd.add("--standalone");
            cmd.add("-o");
            cmd.add("output.tex");
            cmd.add("input.md");
            try {
                var res = runner.run(cmd, tempDir, tempDir.resolve("convert.log"), properties.conversionTimeoutSeconds());
                if (res.exitCode() != 0 || !Files.isRegularFile(tempDir.resolve("output.tex"))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Pandoc could not emit LaTeX from the extracted PDF.");
                }
                return Files.readString(tempDir.resolve("output.tex"), StandardCharsets.UTF_8);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("PDF-to-LaTeX conversion was interrupted.", exception);
            }
        } finally {
            SourceVersionStore.removeTreeQuietly(tempDir);
        }
    }

    private byte[] convertMarkdownToDocx(String markdown, List<ExtractedAsset> assets) throws IOException {
        if (!tools.available(tools.pandoc())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Pandoc is unavailable. Run setup tools before exporting DOCX.");
        }
        Path tempDir = Files.createTempDirectory("doc2tex-pdftodocx-");
        try {
            // Inline extracted PNG figures as data URIs so Pandoc embeds them into word/media/ under --sandbox.
            String inlinedMarkdown = inlineAssetsAsDataUris(markdown, assets);
            Files.writeString(tempDir.resolve("input.md"), inlinedMarkdown, StandardCharsets.UTF_8);
            List<String> cmd = List.of(
                    tools.pandoc(), "--sandbox", "--from=markdown", "--to=docx",
                    "-o", "output.docx", "input.md");
            try {
                var res = runner.run(cmd, tempDir, tempDir.resolve("convert.log"), properties.conversionTimeoutSeconds());
                if (res.exitCode() != 0 || !Files.isRegularFile(tempDir.resolve("output.docx"))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Pandoc could not build a DOCX file from this PDF.");
                }
                return Files.readAllBytes(tempDir.resolve("output.docx"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("PDF-to-DOCX export was interrupted.", exception);
            }
        } finally {
            SourceVersionStore.removeTreeQuietly(tempDir);
        }
    }

    private static String inlineAssetsAsDataUris(String markdown, List<ExtractedAsset> assets) {
        if (assets.isEmpty()) return markdown;
        String out = markdown;
        for (ExtractedAsset asset : assets) {
            String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(asset.pngBytes());
            out = out.replace("](" + asset.relativePath() + ")", "](" + uri + ")");
        }
        return out;
    }

    private static byte[] buildLatexZip(
            String baseName, String latex, List<ExtractedAsset> assets, List<String> warnings) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("main.tex"));
            zip.write(latex.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (ExtractedAsset asset : assets) {
                zip.putNextEntry(new ZipEntry(asset.relativePath()));
                zip.write(asset.pngBytes());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("README.txt"));
            String readme = "Converted from PDF (" + baseName + ".pdf) using doc2TeX.\n"
                    + "Keep the media/ folder beside main.tex.\n"
                    + (warnings.isEmpty() ? "" : "\nNotes:\n- " + String.join("\n- ", warnings) + "\n");
            zip.write(readme.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return baos.toByteArray();
        }
    }

    private static void writeAssets(Path root, List<ExtractedAsset> assets) throws IOException {
        if (assets.isEmpty()) return;
        Path media = root.resolve("media");
        Files.createDirectories(media);
        for (ExtractedAsset asset : assets) {
            Path target = root.resolve(asset.relativePath()).normalize();
            if (target.startsWith(media)) Files.write(target, asset.pngBytes());
        }
    }

    private static ImageExtraction extractImages(PDDocument doc, int pageLimit) throws IOException {
        Set<COSBase> seenObjs = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSObjectKey> seenKeys = new LinkedHashSet<>();
        List<ExtractedAsset> assets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long totalBytes = 0;
        int figIndex = 0;
        boolean imageLimitReached = false;
        boolean byteLimitReached = false;
        int oversizedSkipped = 0;
        int decodeFailed = 0;
        outer:
        for (int i = 0; i < pageLimit; i++) {
            List<PDImageXObject> pageImages = new ArrayList<>();
            PdfStudioExtractor.collectPageImages(doc.getPage(i).getResources(), 0,
                    Collections.newSetFromMap(new IdentityHashMap<>()), seenObjs, seenKeys, pageImages);
            for (PDImageXObject image : pageImages) {
                if (assets.size() >= MAX_IMAGES) {
                    imageLimitReached = true;
                    break outer;
                }
                if ((long) image.getWidth() * image.getHeight() > MAX_IMAGE_PIXELS) {
                    oversizedSkipped++;
                    continue;
                }
                BufferedImage buffered;
                try {
                    buffered = image.getImage();
                } catch (IOException | RuntimeException ignored) {
                    decodeFailed++;
                    continue;
                }
                if (buffered == null) {
                    decodeFailed++;
                    continue;
                }
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (!ImageIO.write(buffered, "png", png)) {
                    decodeFailed++;
                    continue;
                }
                byte[] bytes = png.toByteArray();
                if (totalBytes + bytes.length > MAX_IMAGE_BYTES) {
                    byteLimitReached = true;
                    break outer;
                }
                totalBytes += bytes.length;
                figIndex++;
                assets.add(new ExtractedAsset(i + 1, String.format(Locale.ROOT, "media/fig-%03d.png", figIndex), bytes));
            }
        }
        if (imageLimitReached) {
            warnings.add("Image extraction stopped after the " + MAX_IMAGES + "-figure limit; later figures were omitted.");
        }
        if (byteLimitReached) {
            warnings.add("Image extraction stopped after reaching the 30 MB media budget; later figures were omitted.");
        }
        if (oversizedSkipped > 0) {
            warnings.add("Skipped " + oversizedSkipped + " oversized image(s) exceeding the 40-megapixel safety limit.");
        }
        if (decodeFailed > 0) {
            warnings.add("Skipped " + decodeFailed + " image(s) that could not be decoded cleanly.");
        }
        return new ImageExtraction(assets, warnings);
    }

    private static boolean flushHeading(StringBuilder md, int level, StringBuilder headingBuf) {
        String text = headingBuf.toString().trim();
        headingBuf.setLength(0);
        if (level <= 0 || text.isEmpty()) return false;
        md.append("#".repeat(level)).append(' ').append(text).append("\n\n");
        return true;
    }

    private static void flushParagraph(StringBuilder md, StringBuilder paragraph) {
        String text = paragraph.toString().trim();
        if (!text.isEmpty()) {
            md.append(text).append("\n\n");
        }
        paragraph.setLength(0);
    }

    private static String normalizePreviewFormat(String raw) {
        String fmt = raw == null ? "markdown" : raw.trim().toLowerCase(Locale.ROOT);
        if (!List.of("markdown", "latex").contains(fmt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose markdown or latex as preview target.");
        }
        return fmt;
    }

    private static String normalizeDownloadFormat(String raw) {
        String fmt = raw == null ? "markdown" : raw.trim().toLowerCase(Locale.ROOT);
        if (!List.of("markdown", "latex-zip", "docx").contains(fmt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose markdown, latex-zip, or docx download format.");
        }
        return fmt;
    }
}
