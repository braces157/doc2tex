package dev.doc2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
@RequestMapping("/api")
class CompareAndCleanController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PdfDiffService pdfDiff;
    private final DocxCleanerService docxCleaner;

    CompareAndCleanController(PdfDiffService pdfDiff, DocxCleanerService docxCleaner) {
        this.pdfDiff = pdfDiff;
        this.docxCleaner = docxCleaner;
    }

    @PostMapping(value = "/diff/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PdfDiffService.PdfDiffReport diffPdf(
            @RequestPart("left") MultipartFile left,
            @RequestPart("right") MultipartFile right) throws IOException {
        return pdfDiff.compare(left, right);
    }

    @PostMapping(value = "/diff/pdf/page", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PdfDiffService.VisualPageDiff diffPdfPage(
            @RequestPart("left") MultipartFile left,
            @RequestPart("right") MultipartFile right,
            @RequestParam(defaultValue = "1") int page) throws IOException {
        return pdfDiff.renderPageDiff(left, right, page);
    }

    @PostMapping(value = "/docx/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    DocxCleanerService.DocxInspection inspectDocx(@RequestPart("file") MultipartFile file) throws IOException {
        return docxCleaner.inspect(file);
    }

    @PostMapping(value = "/docx/clean", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<byte[]> cleanDocx(
            @RequestPart("file") MultipartFile file,
            @RequestPart("options") String optionsJson) throws IOException {
        DocxCleanerService.CleanOptions options;
        try {
            options = JSON.readValue(optionsJson, DocxCleanerService.CleanOptions.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The DOCX clean options are invalid.");
        }
        DocxCleanerService.CleanResult result = docxCleaner.clean(file, options);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Doc2tex-Original-Bytes", Long.toString(result.originalBytes()))
                .header("X-Doc2tex-Cleaned-Bytes", Long.toString(result.cleanedBytes()))
                .header("X-Doc2tex-Images-Optimized", Integer.toString(result.imagesOptimized()))
                .body(result.bytes());
    }
}
