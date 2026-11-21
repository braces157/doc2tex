package dev.doc2tex;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Computes bounded page-by-page line diffs and visual pixel diffs between two PDFs. */
@Service
class PdfDiffService {
    private static final int MAX_DIFF_PAGES = 50;
    private static final int MAX_LINES_PER_PAGE = 400;
    private static final float RENDER_DPI = 96f;

    record DiffLine(String type, String text) {}
    record PageDiff(int page, boolean identical, int added, int removed, List<DiffLine> lines) {}
    record PdfDiffReport(
            String leftName,
            String rightName,
            int leftPages,
            int rightPages,
            int comparedPages,
            int matchingPages,
            int changedPages,
            int addedLines,
            int removedLines,
            boolean truncated,
            List<PageDiff> pages) {}

    record VisualPageDiff(
            int page,
            double pixelDiffRatio,
            int width,
            int height,
            String leftPngBase64,
            String rightPngBase64,
            String diffPngBase64) {}

    PdfDiffReport compare(MultipartFile leftFile, MultipartFile rightFile) throws IOException {
        byte[] leftBytes = PdfStudioService.readAndValidatePdfBytes(leftFile);
        byte[] rightBytes = PdfStudioService.readAndValidatePdfBytes(rightFile);
        String leftName = PdfStudioService.sanitizeDisplayFilename(leftFile.getOriginalFilename(), "left.pdf");
        String rightName = PdfStudioService.sanitizeDisplayFilename(rightFile.getOriginalFilename(), "right.pdf");

        try (PDDocument leftDoc = PdfStudioService.loadPdf(leftBytes, "");
             PDDocument rightDoc = PdfStudioService.loadPdf(rightBytes, "")) {
            int leftPages = leftDoc.getNumberOfPages();
            int rightPages = rightDoc.getNumberOfPages();
            int total = Math.max(leftPages, rightPages);
            int compared = Math.min(total, MAX_DIFF_PAGES);
            boolean truncated = total > MAX_DIFF_PAGES;

            List<PageDiff> pages = new ArrayList<>(compared);
            int matching = 0;
            int changed = 0;
            int totalAdded = 0;
            int totalRemoved = 0;
            PDFTextStripper stripper = new PDFTextStripper();

            for (int p = 1; p <= compared; p++) {
                List<String> fullLeft = p <= leftPages ? extractAllPageLines(leftDoc, stripper, p) : List.of();
                List<String> fullRight = p <= rightPages ? extractAllPageLines(rightDoc, stripper, p) : List.of();
                boolean pageTruncated = fullLeft.size() > MAX_LINES_PER_PAGE || fullRight.size() > MAX_LINES_PER_PAGE;
                if (pageTruncated) truncated = true;
                List<String> leftLines = fullLeft.size() > MAX_LINES_PER_PAGE ? fullLeft.subList(0, MAX_LINES_PER_PAGE) : fullLeft;
                List<String> rightLines = fullRight.size() > MAX_LINES_PER_PAGE ? fullRight.subList(0, MAX_LINES_PER_PAGE) : fullRight;
                List<DiffLine> diff = computeLineDiff(leftLines, rightLines);
                int added = 0;
                int removed = 0;
                for (DiffLine line : diff) {
                    if (line.type().equals("add")) added++;
                    else if (line.type().equals("del")) removed++;
                }
                boolean fullEqual = p <= leftPages && p <= rightPages && fullLeft.equals(fullRight);
                boolean identical = added == 0 && removed == 0 && fullEqual;
                if (!identical && added == 0 && removed == 0) {
                    // Differences exist past MAX_LINES_PER_PAGE; reflect at least one change past the display cap.
                    int tailDiff = Math.max(1, Math.abs(fullRight.size() - fullLeft.size()));
                    if (fullRight.size() >= fullLeft.size()) added += tailDiff;
                    else removed += tailDiff;
                }
                if (identical) matching++;
                else changed++;
                totalAdded += added;
                totalRemoved += removed;
                pages.add(new PageDiff(p, identical, added, removed, diff));
            }

            return new PdfDiffReport(
                    leftName, rightName, leftPages, rightPages, compared,
                    matching, changed, totalAdded, totalRemoved, truncated, pages);
        }
    }

    VisualPageDiff renderPageDiff(MultipartFile leftFile, MultipartFile rightFile, int pageNumber) throws IOException {
        if (pageNumber < 1 || pageNumber > MAX_DIFF_PAGES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a page number between 1 and " + MAX_DIFF_PAGES + ".");
        }
        byte[] leftBytes = PdfStudioService.readAndValidatePdfBytes(leftFile);
        byte[] rightBytes = PdfStudioService.readAndValidatePdfBytes(rightFile);

        try (PDDocument leftDoc = PdfStudioService.loadPdf(leftBytes, "");
             PDDocument rightDoc = PdfStudioService.loadPdf(rightBytes, "")) {
            int maxPages = Math.max(leftDoc.getNumberOfPages(), rightDoc.getNumberOfPages());
            if (maxPages == 0 || pageNumber > maxPages) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Page " + pageNumber + " exceeds both documents.");
            }
            BufferedImage leftImg = pageNumber <= leftDoc.getNumberOfPages()
                    ? renderPageBounded(leftDoc, pageNumber - 1)
                    : null;
            BufferedImage rightImg = pageNumber <= rightDoc.getNumberOfPages()
                    ? renderPageBounded(rightDoc, pageNumber - 1)
                    : null;

            int width = Math.min(2000, Math.max(leftImg != null ? leftImg.getWidth() : 1, rightImg != null ? rightImg.getWidth() : 1));
            int height = Math.min(2400, Math.max(leftImg != null ? leftImg.getHeight() : 1, rightImg != null ? rightImg.getHeight() : 1));

            BufferedImage leftCanvas = normalizeCanvas(leftImg, width, height);
            BufferedImage rightCanvas = normalizeCanvas(rightImg, width, height);
            BufferedImage diffCanvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

            long diffPixels = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgbL = leftCanvas.getRGB(x, y);
                    int rgbR = rightCanvas.getRGB(x, y);
                    int dr = Math.abs(((rgbL >> 16) & 0xff) - ((rgbR >> 16) & 0xff));
                    int dg = Math.abs(((rgbL >> 8) & 0xff) - ((rgbR >> 8) & 0xff));
                    int db = Math.abs((rgbL & 0xff) - (rgbR & 0xff));
                    if (dr + dg + db > 36) {
                        diffPixels++;
                        diffCanvas.setRGB(x, y, 0xB91C1C);
                    } else {
                        int r = (((rgbR >> 16) & 0xff) + 510) / 3;
                        int g = (((rgbR >> 8) & 0xff) + 510) / 3;
                        int b = ((rgbR & 0xff) + 510) / 3;
                        diffCanvas.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }
            }
            double ratio = Math.round(((double) diffPixels / (width * (double) height)) * 10000.0) / 10000.0;
            return new VisualPageDiff(
                    pageNumber,
                    ratio,
                    width,
                    height,
                    toPngBase64(leftCanvas),
                    toPngBase64(rightCanvas),
                    toPngBase64(diffCanvas));
        }
    }

    private static List<String> extractAllPageLines(PDDocument doc, PDFTextStripper stripper, int page) throws IOException {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(doc).replace("\r\n", "\n").replace('\r', '\n');
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    static List<DiffLine> computeLineDiff(List<String> left, List<String> right) {
        int m = left.size();
        int n = right.size();
        if (m == 0 && n == 0) return List.of();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                dp[i][j] = left.get(i).equals(right.get(j))
                        ? 1 + dp[i + 1][j + 1]
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<DiffLine> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (left.get(i).equals(right.get(j))) {
                out.add(new DiffLine("same", left.get(i)));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new DiffLine("del", left.get(i)));
                i++;
            } else {
                out.add(new DiffLine("add", right.get(j)));
                j++;
            }
        }
        while (i < m) out.add(new DiffLine("del", left.get(i++)));
        while (j < n) out.add(new DiffLine("add", right.get(j++)));
        return Collections.unmodifiableList(out);
    }

    private static BufferedImage renderPageBounded(PDDocument doc, int pageIndex) throws IOException {
        var page = doc.getPage(pageIndex);
        PDRectangle cropBox = page.getCropBox();
        PDRectangle visibleBox = PdfStudioStamper.visibleBox(page);
        // PDFBox PDFRenderer sizes the bitmap directly from the raw crop box without applying /UserUnit.
        float widthPt = Math.max(1f, Math.max(cropBox.getWidth(), visibleBox.getWidth()));
        float heightPt = Math.max(1f, Math.max(cropBox.getHeight(), visibleBox.getHeight()));
        float longestPt = Math.max(widthPt, heightPt);
        float dpi = Math.min(RENDER_DPI, 1800f * 72f / longestPt);
        if (dpi < 0.5f) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Page " + (pageIndex + 1) + " dimensions exceed the visual diff rendering limit.");
        }
        return new PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi);
    }

    private static BufferedImage normalizeCanvas(BufferedImage source, int width, int height) {
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        if (source != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.min(1.0, Math.min((double) width / source.getWidth(), (double) height / source.getHeight()));
            int drawW = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int drawH = Math.max(1, (int) Math.round(source.getHeight() * scale));
            g.drawImage(source, 0, 0, drawW, drawH, null);
        }
        g.dispose();
        return canvas;
    }

    private static String toPngBase64(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
