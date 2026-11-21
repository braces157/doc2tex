package dev.doc2tex;

import dev.doc2tex.PdfStudioModels.AssemblePlan;
import dev.doc2tex.PdfStudioModels.BinaryResult;
import dev.doc2tex.PdfStudioModels.Inspection;
import dev.doc2tex.PdfStudioModels.PageSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** Executes isolated per-file batch conversions or PDF merges and packages results into a ZIP. */
@Service
class BatchService {
    private static final int MAX_BATCH_FILES = 20;
    private static final long MAX_BATCH_BYTES = 50L * 1024 * 1024;
    private static final long MAX_BATCH_OUTPUT_BYTES = 100L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record ItemReport(String sourceFilename, String status, String outputEntry, String message) {}
    record BatchResult(String filename, byte[] bytes, int total, int succeeded, int failed, List<ItemReport> items) {}

    private final MultiFormatService multiFormat;
    private final PdfStudioService pdfStudio;

    BatchService(MultiFormatService multiFormat, PdfStudioService pdfStudio) {
        this.multiFormat = multiFormat;
        this.pdfStudio = pdfStudio;
    }

    BatchResult runBatch(List<MultipartFile> files, String targetFormat, boolean toc) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose at least one file for batch processing.");
        }
        if (files.size() > MAX_BATCH_FILES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Batch processing supports up to " + MAX_BATCH_FILES + " files at a time.");
        }
        long totalBytes = 0;
        for (MultipartFile f : files) {
            totalBytes += Math.max(0L, f.getSize());
            if (totalBytes > MAX_BATCH_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Combined batch uploads cannot exceed 50 MB.");
            }
        }

        String mode = targetFormat == null ? "markdown" : targetFormat.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("pdf-merge")) {
            return runPdfMergeBatch(files);
        }
        if (!MultiFormatService.isSupportedOutputFormat(mode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported batch target format \"" + mode + "\".");
        }

        List<ItemReport> reports = new ArrayList<>(files.size());
        Set<String> usedNames = new LinkedHashSet<>();
        int succeeded = 0;
        int failed = 0;
        long outputBytes = 0;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (MultipartFile file : files) {
                String sourceName = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document");
                try {
                    MultiFormatService.ConvertedFile converted = multiFormat.convert(file, mode, toc);
                    if (outputBytes + converted.bytes().length > MAX_BATCH_OUTPUT_BYTES) {
                        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "Combined batch output exceeds the 100 MB ZIP limit.");
                    }
                    outputBytes += converted.bytes().length;
                    String entryName = uniqueEntryName("outputs/" + converted.filename(), usedNames);
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(converted.bytes());
                    zip.closeEntry();
                    succeeded++;
                    reports.add(new ItemReport(sourceName, "OK", entryName, converted.warnings()));
                } catch (ApiException | IOException exception) {
                    failed++;
                    reports.add(new ItemReport(sourceName, "FAILED", "", exception.getMessage()));
                }
            }

            if (succeeded == 0) {
                String firstError = reports.isEmpty() ? "All files failed." : reports.getFirst().message();
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Batch conversion failed for all files: " + firstError);
            }

            writeManifest(zip, mode, files.size(), succeeded, failed, reports);
            zip.finish();
            return new BatchResult("batch-" + mode + ".zip", baos.toByteArray(), files.size(), succeeded, failed, reports);
        }
    }

    private BatchResult runPdfMergeBatch(List<MultipartFile> files) throws IOException {
        List<MultipartFile> validFiles = new ArrayList<>();
        List<PageSpec> specs = new ArrayList<>();
        List<ItemReport> reports = new ArrayList<>(files.size());
        int succeeded = 0;
        int failed = 0;

        for (MultipartFile file : files) {
            String sourceName = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document.pdf");
            try {
                Inspection inspection = pdfStudio.inspect(file, "");
                int fileIdx = validFiles.size();
                validFiles.add(file);
                for (int p = 1; p <= inspection.pageCount(); p++) {
                    specs.add(new PageSpec(fileIdx, p, 0));
                }
                succeeded++;
                reports.add(new ItemReport(sourceName, "OK", "outputs/merged.pdf",
                        inspection.pageCount() + " page(s) included."));
            } catch (ApiException | IOException exception) {
                failed++;
                reports.add(new ItemReport(sourceName, "FAILED", "", exception.getMessage()));
            }
        }

        if (validFiles.isEmpty() || specs.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "No valid PDF pages were available to merge.");
        }

        BinaryResult merged = pdfStudio.assemble(
                validFiles, new AssemblePlan(specs, true, false, "", 0.18, false, "merged.pdf"));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("outputs/" + merged.filename()));
            zip.write(merged.bytes());
            zip.closeEntry();
            writeManifest(zip, "pdf-merge", files.size(), succeeded, failed, reports);
            zip.finish();
            return new BatchResult("batch-pdf-merge.zip", baos.toByteArray(), files.size(), succeeded, failed, reports);
        }
    }

    private static void writeManifest(
            ZipOutputStream zip, String mode, int total, int succeeded, int failed, List<ItemReport> items)
            throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("targetFormat", mode);
        manifest.put("total", total);
        manifest.put("succeeded", succeeded);
        manifest.put("failed", failed);
        manifest.put("items", items);
        zip.putNextEntry(new ZipEntry("batch-report.json"));
        zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        zip.closeEntry();
    }

    private static String uniqueEntryName(String candidate, Set<String> used) {
        if (used.add(candidate)) return candidate;
        int dot = candidate.lastIndexOf('.');
        String stem = dot > 0 ? candidate.substring(0, dot) : candidate;
        String ext = dot > 0 ? candidate.substring(dot) : "";
        int i = 2;
        while (!used.add(stem + "-" + i + ext)) i++;
        return stem + "-" + i + ext;
    }
}
