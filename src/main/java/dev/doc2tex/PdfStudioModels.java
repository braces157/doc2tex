package dev.doc2tex;

import java.util.List;

/** Immutable DTOs and request plans for Local PDF Studio operations. */
final class PdfStudioModels {
    private PdfStudioModels() {}

    record Metadata(
            String title,
            String author,
            String subject,
            String keywords,
            String creator,
            String producer,
            String creationDate,
            String modificationDate) {}

    record PageInfo(
            int number,
            double widthMm,
            double heightMm,
            int rotation,
            int charCount,
            int imageCount,
            int annotationCount) {}

    record OutlineItem(String title, int level, Integer pageNumber) {}

    record Inspection(
            String filename,
            long bytes,
            String pdfVersion,
            int pageCount,
            boolean encrypted,
            boolean hasMetadata,
            boolean hasXmpMetadata,
            boolean hasOutline,
            int embeddedImageCount,
            int annotationCount,
            int fontCount,
            Metadata metadata,
            List<PageInfo> pages,
            List<OutlineItem> outline,
            List<String> warnings) {}

    record PageSpec(int fileIndex, int pageNumber, int rotation) {}

    record AssemblePlan(
            List<PageSpec> pages,
            boolean scrubMetadata,
            boolean stripAnnotations,
            String watermarkText,
            Double watermarkOpacity,
            boolean pageNumbers,
            String outputName,
            List<String> passwords) {
        AssemblePlan(
                List<PageSpec> pages,
                boolean scrubMetadata,
                boolean stripAnnotations,
                String watermarkText,
                Double watermarkOpacity,
                boolean pageNumbers,
                String outputName) {
            this(pages, scrubMetadata, stripAnnotations, watermarkText, watermarkOpacity, pageNumbers, outputName, List.of());
        }
    }

    record PageText(int pageNumber, String text) {}

    record TextExtract(
            String filename,
            int pageCount,
            int totalChars,
            boolean truncated,
            List<PageText> pages) {}

    record MarkdownExtract(
            String filename,
            int pageCount,
            int totalChars,
            boolean truncated,
            String markdown) {}

    record BinaryResult(String filename, byte[] bytes) {}
}
