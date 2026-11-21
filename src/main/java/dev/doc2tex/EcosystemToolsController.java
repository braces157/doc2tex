package dev.doc2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api")
class EcosystemToolsController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AcademicTemplateService templates;
    private final ReferenceExtractorService references;
    private final BatchService batch;

    EcosystemToolsController(
            AcademicTemplateService templates,
            ReferenceExtractorService references,
            BatchService batch) {
        this.templates = templates;
        this.references = references;
        this.batch = batch;
    }

    @GetMapping("/templates")
    List<AcademicTemplateService.TemplateDescriptor> listTemplates() {
        return templates.list();
    }

    @PostMapping("/templates/generate")
    AcademicTemplateService.GeneratedTemplate generateTemplate(
            @RequestBody AcademicTemplateService.TemplateRequest request) {
        return templates.generate(request);
    }

    @PostMapping("/templates/compile")
    ResponseEntity<byte[]> compileTemplate(
            @RequestBody AcademicTemplateService.TemplateRequest request) throws IOException {
        AcademicTemplateService.CompiledTemplate compiled = templates.compilePdf(request);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(compiled.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(compiled.bytes());
    }

    @PostMapping(value = "/references/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ReferenceExtractorService.ExtractionReport extractReferences(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text) throws IOException {
        return references.extract(file, text);
    }

    @PostMapping(value = "/batch/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<byte[]> batchConvert(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(defaultValue = "markdown") String targetFormat,
            @RequestParam(defaultValue = "false") boolean toc) throws IOException {
        BatchService.BatchResult result = batch.runBatch(files, targetFormat, toc);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8)
                .build();
        String summary = JSON.writeValueAsString(Map.of(
                "total", result.total(),
                "succeeded", result.succeeded(),
                "failed", result.failed()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Doc2tex-Batch-Summary", summary)
                .body(result.bytes());
    }
}
