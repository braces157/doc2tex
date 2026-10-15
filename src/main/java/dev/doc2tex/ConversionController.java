package dev.doc2tex;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
class ConversionController {
    private final ConversionService service;
    private final JobStore store;
    private final Tools tools;

    ConversionController(ConversionService service, JobStore store, Tools tools) {
        this.service = service;
        this.store = store;
        this.tools = tools;
    }

    @GetMapping("/system") Map<String, Object> system() { return tools.status(); }
    @GetMapping("/jobs") List<Job> list() { return store.list(); }
    @GetMapping("/jobs/{id}") Job job(@PathVariable String id) { return store.get(id); }
    @GetMapping("/jobs/{id}/review") DocumentInspector.Review review(@PathVariable String id) { return service.review(id); }

    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Job> create(@RequestPart MultipartFile file, @RequestParam(defaultValue = "article") String template) throws IOException {
        return ResponseEntity.accepted().body(service.submit(file, template));
    }

    @GetMapping(value = "/jobs/{id}/source", produces = "text/plain;charset=UTF-8")
    String source(@PathVariable String id) throws IOException { return service.source(id); }

    record SourceUpdate(@NotBlank @Size(max = 2 * 1024 * 1024) String source, @PositiveOrZero int revision) {}

    @PutMapping("/jobs/{id}/source")
    Job saveSource(@PathVariable String id, @Valid @RequestBody SourceUpdate request) throws IOException {
        return service.saveSource(id, request.source(), request.revision());
    }

    @PostMapping("/jobs/{id}/preview")
    ResponseEntity<Job> preview(@PathVariable String id) throws IOException { return ResponseEntity.accepted().body(service.preview(id)); }

    @GetMapping(value = "/jobs/{id}/preview/log", produces = "text/plain;charset=UTF-8")
    String compilerLog(@PathVariable String id) throws IOException { return service.compilerLog(id); }

    @GetMapping("/jobs/{id}/preview")
    ResponseEntity<FileSystemResource> pdf(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=preview.pdf").body(new FileSystemResource(service.pdf(id)));
    }

    @GetMapping("/jobs/{id}/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String id) {
        Job job = store.get(id);
        if (!job.status().equals("COMPLETED")) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "The conversion is not ready yet.");
        String filename = job.filename().replaceFirst("(?i)\\.docx$", "") + "-latex.zip";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(output -> service.writeArchive(id, output));
    }

    @GetMapping("/sample.docx")
    ResponseEntity<byte[]> sample() throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=field-notes.docx").body(SampleDocument.create());
    }
}
