package dev.doc2tex;

import dev.doc2tex.PdfStudioModels.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/pdf")
class PdfStudioController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PdfStudioService pdfStudio;
    private final PdfToSourceService pdfToSource;

    PdfStudioController(PdfStudioService pdfStudio, PdfToSourceService pdfToSource) {
        this.pdfStudio = pdfStudio;
        this.pdfToSource = pdfToSource;
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Inspection inspect(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String password) throws IOException {
        return pdfStudio.inspect(file, password);
    }

    @PostMapping(value = "/assemble", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<byte[]> assemble(
            @RequestPart("files") List<MultipartFile> files,
            @RequestPart("plan") String planJson) throws IOException {
        AssemblePlan plan;
        try {
            plan = JSON.readValue(planJson, AssemblePlan.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The PDF assemble plan is invalid.");
        }
        BinaryResult result = pdfStudio.assemble(files, plan);
        return attachment(result.bytes(), result.filename(), MediaType.APPLICATION_PDF);
    }

    @PostMapping(value = "/extract/text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TextExtract extractText(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String password) throws IOException {
        return pdfStudio.extractText(file, password);
    }

    @PostMapping(value = "/extract/markdown", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    MarkdownExtract extractMarkdown(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String password) throws IOException {
        return pdfStudio.extractMarkdown(file, password);
    }

    @PostMapping(value = "/extract/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<byte[]> extractImages(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String password) throws IOException {
        BinaryResult result = pdfStudio.extractImagesZip(file, password);
        return attachment(result.bytes(), result.filename(), MediaType.parseMediaType("application/zip"));
    }

    @PostMapping(value = "/to-source", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PdfToSourceService.PdfSourceResult toSource(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "markdown") String targetFormat,
            @RequestParam(defaultValue = "false") boolean aiLayout,
            @RequestParam(required = false) String password) throws IOException {
        return pdfToSource.convertToSource(file, targetFormat, aiLayout, password);
    }

    @PostMapping(value = "/to-source/download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<byte[]> toSourceDownload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "markdown") String targetFormat,
            @RequestParam(defaultValue = "false") boolean aiLayout,
            @RequestParam(required = false) String password) throws IOException {
        PdfToSourceService.PdfSourceDownload dl = pdfToSource.downloadAs(file, targetFormat, aiLayout, password);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(dl.filename(), StandardCharsets.UTF_8)
                .build();
        var builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(dl.mediaType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if (dl.warnings() != null && !dl.warnings().isEmpty()) {
            // Prioritize image/content omission warnings first so earlier informational notes never truncate them.
            java.util.List<String> ordered = new java.util.ArrayList<>(dl.warnings().size());
            for (String w : dl.warnings()) {
                String lower = w.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("omitted") || lower.contains("skipped") || lower.contains("limit")) ordered.add(w);
            }
            for (String w : dl.warnings()) {
                if (!ordered.contains(w)) ordered.add(w);
            }
            String headerWarn = String.join(" | ", ordered).replaceAll("[\\r\\n\\p{Cntrl}]+", " ").trim();
            if (headerWarn.length() > 2048) headerWarn = headerWarn.substring(0, 2045) + "...";
            builder.header("X-Doc2TeX-Warnings", headerWarn);
        }
        return builder.body(dl.bytes());
    }

    private static ResponseEntity<byte[]> attachment(byte[] bytes, String filename, MediaType mediaType) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(bytes);
    }
}
