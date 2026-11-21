package dev.doc2tex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Reconstructs visual lines, column reading order, and running margins from PDFBox glyph coordinates. */
final class PdfLayoutExtractor {
    private static final Pattern BOLD_TEX_FONT = Pattern.compile(".*(?:cmbx|sfbx|ecbx|lmbx|ptmb|bchb|nimbusromno9l-medi|nimbussanl-bold).*");

    record TextLine(float x, float y, float width, float height, float fontSize, boolean bold, String text, int column) {
        TextLine withColumn(int nextColumn) {
            return new TextLine(x, y, width, height, fontSize, bold, text, nextColumn);
        }
    }

    record PageLines(int pageNumber, float widthPt, float heightPt, List<TextLine> lines) {}

    private record Glyph(float x, float y, float width, float height, float fontSize, float spaceWidth, int dir, boolean bold, String unicode) {}

    private PdfLayoutExtractor() {}

    static List<TextLine> extractLines(PDDocument doc, int pageNumber, float pageWidthPt) throws IOException {
        List<Glyph> glyphs = new ArrayList<>();
        PDFTextStripper collector = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                if (textPositions == null || textPositions.isEmpty()) return;
                for (TextPosition pos : textPositions) {
                    String uni = pos.getUnicode();
                    if (uni == null || uni.isEmpty()) continue;
                    int dir = Math.floorMod(Math.round(pos.getDir() / 90f) * 90, 360);
                    float pt = pos.getFontSizeInPt();
                    if (!Float.isFinite(pt) || pt <= 0f) pt = pos.getHeightDir() > 0f ? pos.getHeightDir() * 1.2f : 10f;
                    float spaceW = pos.getWidthOfSpace();
                    if (!Float.isFinite(spaceW) || spaceW <= 0f) spaceW = pt * 0.28f;
                    glyphs.add(new Glyph(
                            pos.getXDirAdj(),
                            pos.getYDirAdj(),
                            Math.max(0f, pos.getWidthDirAdj()),
                            Math.max(1f, pos.getHeightDir()),
                            pt,
                            spaceW,
                            dir,
                            isBoldFont(pos.getFont() == null ? null : pos.getFont().getName()),
                            uni));
                }
            }
        };
        collector.setSortByPosition(false);
        collector.setStartPage(pageNumber);
        collector.setEndPage(pageNumber);
        collector.getText(doc);
        if (glyphs.isEmpty()) return List.of();

        int dominantDir = findDominantDirection(glyphs);
        List<Glyph> horizontal = new ArrayList<>(glyphs.size());
        for (Glyph g : glyphs) {
            if (g.dir() != dominantDir) {
                // Skip rotated vertical edge stamps (e.g. arXiv left-margin identifier strips).
                continue;
            }
            horizontal.add(g);
        }
        if (horizontal.isEmpty()) horizontal = glyphs;

        horizontal.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));

        List<List<Glyph>> rows = new ArrayList<>();
        List<Float> rowYs = new ArrayList<>();
        List<Float> rowFonts = new ArrayList<>();

        for (Glyph g : horizontal) {
            if (g.unicode().isBlank()) continue;
            int bestRow = -1;
            float bestDist = Float.MAX_VALUE;
            for (int i = rows.size() - 1; i >= 0; i--) {
                float refY = rowYs.get(i);
                float tol = Math.max(2.6f, Math.max(rowFonts.get(i), g.fontSize()) * 0.45f);
                float dist = Math.abs(g.y() - refY);
                if (dist <= tol && dist < bestDist) {
                    bestDist = dist;
                    bestRow = i;
                }
                if (g.y() - refY > 28f) break;
            }
            if (bestRow >= 0) {
                List<Glyph> row = rows.get(bestRow);
                row.add(g);
                if (g.fontSize() > rowFonts.get(bestRow)) rowFonts.set(bestRow, g.fontSize());
            } else {
                List<Glyph> row = new ArrayList<>();
                row.add(g);
                rows.add(row);
                rowYs.add(g.y());
                rowFonts.add(g.fontSize());
            }
        }

        List<TextLine> lines = new ArrayList<>(rows.size());
        for (List<Glyph> row : rows) {
            row.sort(Comparator.comparingDouble(Glyph::x));
            List<List<Glyph>> segments = splitRowOnColumnGutter(row, pageWidthPt);
            for (List<Glyph> seg : segments) {
                TextLine line = buildLine(seg);
                if (line != null) lines.add(line);
            }
        }
        lines.sort(Comparator.comparingDouble(TextLine::y).thenComparingDouble(TextLine::x));
        return lines;
    }

    private static List<List<Glyph>> splitRowOnColumnGutter(List<Glyph> row, float pageWidthPt) {
        List<List<Glyph>> segments = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        for (Glyph g : row) {
            if (!current.isEmpty()) {
                Glyph prev = current.get(current.size() - 1);
                float prevEnd = prev.x() + prev.width();
                float gap = g.x() - prevEnd;
                float refFont = Math.max(prev.fontSize(), g.fontSize());
                float gutterThreshold = Math.max(18f, Math.min(pageWidthPt * 0.055f, refFont * 2.2f));
                if (gap > gutterThreshold) {
                    segments.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(g);
        }
        if (!current.isEmpty()) segments.add(current);
        return segments;
    }

    private static TextLine buildLine(List<Glyph> seg) {
        if (seg.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        float minX = Float.MAX_VALUE;
        float maxX = 0f;
        float ySum = 0f;
        float maxHeight = 0f;
        List<Float> fontSizes = new ArrayList<>(seg.size());
        int boldChars = 0;
        int totalChars = 0;
        Glyph prev = null;

        for (Glyph g : seg) {
            if (prev != null) {
                float gap = g.x() - (prev.x() + prev.width());
                float spaceThreshold = Math.max(0.9f, Math.min(prev.spaceWidth(), g.spaceWidth()) * 0.32f);
                if (gap > spaceThreshold && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
            }
            sb.append(g.unicode());
            minX = Math.min(minX, g.x());
            maxX = Math.max(maxX, g.x() + g.width());
            ySum += g.y();
            maxHeight = Math.max(maxHeight, g.height());
            if (g.fontSize() > 4f) fontSizes.add(g.fontSize());
            int nonWs = countNonWhitespace(g.unicode());
            totalChars += nonWs;
            if (g.bold()) boldChars += nonWs;
            prev = g;
        }

        String text = sb.toString().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return null;
        float fontSize = medianFontSize(fontSizes);
        boolean bold = totalChars > 0 && ((double) boldChars / totalChars) >= 0.65;
        return new TextLine(minX, ySum / seg.size(), Math.max(1f, maxX - minX), maxHeight, fontSize, bold, text, 0);
    }

    static Set<String> detectRepeatedMargins(List<PageLines> pages) {
        if (pages.size() < 2) return Set.of();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PageLines page : pages) {
            Set<String> seenOnPage = new LinkedHashSet<>();
            float topCut = page.heightPt() * 0.09f;
            float bottomCut = page.heightPt() * 0.91f;
            for (TextLine line : page.lines()) {
                if (line.y() <= topCut || line.y() >= bottomCut) {
                    String norm = normalizeMarginKey(line.text());
                    if (!norm.isEmpty()) seenOnPage.add(norm);
                }
            }
            for (String key : seenOnPage) counts.merge(key, 1, Integer::sum);
        }
        Set<String> repeated = new LinkedHashSet<>();
        for (var entry : counts.entrySet()) {
            if (entry.getValue() >= 2) repeated.add(entry.getKey());
        }
        return repeated;
    }

    static List<TextLine> filterMarginLines(
            PageLines page, Set<String> repeatedMargins, List<DocLayoutModel.Region> regions) {
        float topCut = page.heightPt() * 0.09f;
        float bottomCut = page.heightPt() * 0.91f;
        List<TextLine> out = new ArrayList<>();
        for (TextLine line : page.lines()) {
            if ((line.y() <= topCut || line.y() >= bottomCut) && repeatedMargins.contains(normalizeMarginKey(line.text()))) {
                continue;
            }
            if (regions != null && insideRegion(line, page, regions, Set.of("header", "footer"))) continue;
            out.add(line);
        }
        return out;
    }

    static boolean isTwoColumnPage(List<TextLine> lines, float pageWidthPt) {
        if (lines.size() < 6) return false;
        float mid = pageWidthPt * 0.5f;
        float margin = pageWidthPt * 0.025f;
        List<TextLine> left = new ArrayList<>();
        List<TextLine> right = new ArrayList<>();
        int spanning = 0;

        for (TextLine line : lines) {
            float endX = line.x() + line.width();
            if (line.x() < mid * 0.85f && endX > mid * 1.15f) {
                spanning++;
            } else if (endX <= mid + margin && line.width() <= pageWidthPt * 0.48f) {
                left.add(line);
            } else if (line.x() >= mid - margin && line.width() <= pageWidthPt * 0.48f) {
                right.add(line);
            }
        }

        int minPerColumn = lines.size() <= 14 ? 3 : 4;
        if (left.size() < minPerColumn || right.size() < minPerColumn) return false;
        if ((left.size() + right.size()) < spanning * 2) return false;

        // Require genuine vertical overlap between left and right columns.
        float rightMinY = right.stream().map(TextLine::y).min(Float::compare).orElse(0f);
        float rightMaxY = right.stream().map(TextLine::y).max(Float::compare).orElse(0f);
        long overlappingLeft = left.stream()
                .filter(l -> l.y() >= rightMinY - 12f && l.y() <= rightMaxY + 12f)
                .count();
        return overlappingLeft >= Math.min(3, minPerColumn);
    }

    static List<TextLine> orderTwoColumns(List<TextLine> lines, float pageWidthPt) {
        float mid = pageWidthPt * 0.5f;
        float margin = pageWidthPt * 0.025f;
        List<TextLine> spanning = new ArrayList<>();
        List<TextLine> leftCol = new ArrayList<>();
        List<TextLine> rightCol = new ArrayList<>();

        for (TextLine line : lines) {
            float endX = line.x() + line.width();
            if (line.x() < mid * 0.85f && endX > mid * 1.15f) {
                spanning.add(line.withColumn(0));
            } else if (endX <= mid + margin || line.x() + line.width() * 0.5f <= mid) {
                leftCol.add(line.withColumn(1));
            } else {
                rightCol.add(line.withColumn(2));
            }
        }

        Comparator<TextLine> byY = Comparator.comparingDouble(TextLine::y).thenComparingDouble(TextLine::x);
        leftCol.sort(byY);
        rightCol.sort(byY);
        spanning.sort(byY);

        float colTopY = Float.MAX_VALUE;
        if (!leftCol.isEmpty()) colTopY = Math.min(colTopY, leftCol.get(0).y());
        if (!rightCol.isEmpty()) colTopY = Math.min(colTopY, rightCol.get(0).y());

        List<TextLine> topFull = new ArrayList<>();
        List<TextLine> bottomFull = new ArrayList<>();
        for (TextLine line : spanning) {
            if (line.y() < colTopY - 4f) topFull.add(line);
            else bottomFull.add(line);
        }

        List<TextLine> combined = new ArrayList<>(lines.size());
        combined.addAll(topFull);
        combined.addAll(leftCol);
        combined.addAll(rightCol);
        combined.addAll(bottomFull);
        return combined;
    }

    static int classifyHeading(
            TextLine line, float bodyFontSize, PageLines page, List<DocLayoutModel.Region> regions) {
        String text = line.text();
        if (text.length() > 120) return 0;
        if (!regions.isEmpty()) {
            if (insideRegion(line, page, regions, Set.of("doc_title"))) return 1;
            if (insideRegion(line, page, regions, Set.of("paragraph_title"))) return 2;
        }
        if (line.fontSize() >= bodyFontSize * 1.45f) return 1;
        if (line.fontSize() >= bodyFontSize * 1.18f) return 2;
        if (line.bold() && text.length() <= 75 && text.length() >= 3
                && !text.endsWith(".") && !text.endsWith(",") && !text.endsWith(";")) {
            return 3;
        }
        return 0;
    }

    static boolean insideRegion(
            TextLine line, PageLines page, List<DocLayoutModel.Region> regions, Set<String> labels) {
        float cx = (line.x() + line.width() / 2f) / Math.max(1f, page.widthPt());
        float cy = line.y() / Math.max(1f, page.heightPt());
        for (DocLayoutModel.Region r : regions) {
            if (r.confidence() >= 0.55f && labels.contains(r.label())
                    && cx >= r.left() && cx <= r.right() && cy >= r.top() && cy <= r.bottom()) {
                return true;
            }
        }
        return false;
    }

    static float medianFontSize(List<Float> sizes) {
        if (sizes.isEmpty()) return 10f;
        List<Float> sorted = new ArrayList<>(sizes);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static int findDominantDirection(List<Glyph> glyphs) {
        int[] counts = new int[4];
        for (Glyph g : glyphs) {
            if (!g.unicode().isBlank()) counts[(g.dir() / 90) % 4]++;
        }
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        return best * 90;
    }

    private static boolean isBoldFont(String fontName) {
        if (fontName == null || fontName.isBlank()) return false;
        String lower = fontName.toLowerCase(Locale.ROOT);
        return lower.contains("bold")
                || lower.contains("demi")
                || lower.contains("heavy")
                || lower.contains("black")
                || BOLD_TEX_FONT.matcher(lower).matches();
    }

    private static int countNonWhitespace(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) count++;
        }
        return count;
    }

    private static String normalizeMarginKey(String text) {
        return text.replaceAll("\\d+", "#").trim().toLowerCase(Locale.ROOT);
    }
}
