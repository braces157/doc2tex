package dev.doc2tex;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.http.HttpStatus;

/** Stamps diagonal watermarks and rotation-aware footer page numbers onto PDF pages. */
final class PdfStudioStamper {
    private PdfStudioStamper() {}

    static void stampAllPages(PDDocument target, Path fontDir, String watermark, float opacity, boolean pageNumbers)
            throws IOException {
        PDFont watermarkFont = watermark.isEmpty() ? null : resolveWatermarkFont(target, fontDir, watermark);
        PDType1Font numberFont = pageNumbers ? new PDType1Font(Standard14Fonts.FontName.HELVETICA) : null;
        int totalPages = target.getNumberOfPages();

        for (int i = 0; i < totalPages; i++) {
            PDPage page = target.getPage(i);
            PDRectangle box = visibleBox(page);
            int rot = Math.floorMod(page.getRotation(), 360);
            float llx = box.getLowerLeftX();
            float lly = box.getLowerLeftY();
            float w = box.getWidth();
            float h = box.getHeight();

            try (PDPageContentStream content = new PDPageContentStream(
                    target, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                if (watermarkFont != null) {
                    drawWatermark(content, watermarkFont, watermark, opacity, rot, llx, lly, w, h);
                }
                if (numberFont != null) {
                    drawPageNumber(content, numberFont, (i + 1) + " / " + totalPages, rot, llx, lly, w, h);
                }
            }
        }
    }

    private static void drawWatermark(
            PDPageContentStream content,
            PDFont font,
            String watermark,
            float opacity,
            int rot,
            float llx,
            float lly,
            float w,
            float h) throws IOException {
        float visW = (rot == 90 || rot == 270) ? h : w;
        float visH = (rot == 90 || rot == 270) ? w : h;
        float diagonal = (float) Math.hypot(visW, visH);
        float unitWidth = font.getStringWidth(watermark) / 1000f;
        float fontSize = unitWidth > 0 ? Math.max(14f, Math.min(54f, (diagonal * 0.65f) / unitWidth)) : 36f;
        float textWidth = unitWidth * fontSize;

        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant(opacity);
        content.saveGraphicsState();
        content.setGraphicsStateParameters(gs);
        content.setNonStrokingColor(new Color(0x4a, 0x47, 0x42));
        content.beginText();
        content.setFont(font, fontSize);
        Matrix matrix = new Matrix();
        matrix.translate(llx + w / 2f, lly + h / 2f);
        matrix.rotate(Math.toRadians(rot + 38.0));
        matrix.translate(-textWidth / 2f, -(fontSize * 0.3f));
        content.setTextMatrix(matrix);
        content.showText(watermark);
        content.endText();
        content.restoreGraphicsState();
    }

    private static void drawPageNumber(
            PDPageContentStream content,
            PDFont font,
            String label,
            int rot,
            float llx,
            float lly,
            float w,
            float h) throws IOException {
        float fontSize = 9.5f;
        float labelWidth = (font.getStringWidth(label) / 1000f) * fontSize;
        float inset = 18f;
        float anchorX;
        float anchorY;
        switch (rot) {
            case 90 -> { anchorX = llx + Math.max(0f, w - inset); anchorY = lly + h / 2f; }
            case 180 -> { anchorX = llx + w / 2f; anchorY = lly + Math.max(0f, h - inset); }
            case 270 -> { anchorX = llx + Math.min(w, inset); anchorY = lly + h / 2f; }
            default -> { anchorX = llx + w / 2f; anchorY = lly + Math.min(h, inset); }
        }
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant(0.80f);
        content.saveGraphicsState();
        content.setGraphicsStateParameters(gs);
        content.setNonStrokingColor(new Color(0x4a, 0x47, 0x42));
        content.beginText();
        content.setFont(font, fontSize);
        Matrix matrix = new Matrix();
        matrix.translate(anchorX, anchorY);
        matrix.rotate(Math.toRadians(rot));
        matrix.translate(-labelWidth / 2f, 0f);
        content.setTextMatrix(matrix);
        content.showText(label);
        content.endText();
        content.restoreGraphicsState();
    }

    private static PDFont resolveWatermarkFont(PDDocument target, Path fontDir, String text) throws IOException {
        PDType1Font standard = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try {
            standard.encode(text);
            return standard;
        } catch (IllegalArgumentException unsupported) {
            Path noto = fontDir.resolve("NotoSerif-Regular.ttf");
            if (Files.isRegularFile(noto) && Files.isReadable(noto)) {
                PDType0Font unicode = PDType0Font.load(target, noto.toFile());
                try {
                    unicode.encode(text);
                    return unicode;
                } catch (IllegalArgumentException ignored) {
                    // Fall through to standard error below.
                }
            }
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Watermark text uses characters outside standard PDF fonts. Use Latin characters or install the bundled Noto fonts.");
        }
    }

    static PDRectangle visibleBox(PDPage page) {
        PDRectangle box = page.getCropBox();
        if (box == null) box = page.getMediaBox();
        return box == null ? PDRectangle.LETTER : box;
    }
}
