package dev.doc2tex;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    @GetMapping("/library")
    JobStore.LibraryPage library(@RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return store.library(query, status, sort, page, size);
    }
    @GetMapping("/jobs/{id}") Job job(@PathVariable String id) { return store.get(id); }
    record MetadataUpdate(@NotNull @Size(max = 200) String displayName) {}

    @PatchMapping("/jobs/{id}")
    Job rename(@PathVariable String id, @Valid @RequestBody MetadataUpdate request) throws IOException {
        return store.rename(id, request.displayName());
    }

    @DeleteMapping("/jobs/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        store.delete(id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/jobs/{id}/review") DocumentInspector.Review review(@PathVariable String id) { return service.review(id); }

    @GetMapping("/jobs/{id}/caption-repairs")
    CaptionRepairs.Plan captionRepairs(@PathVariable String id) throws IOException { return service.captionRepairs(id); }

    record CaptionRepairRequest(@NotNull @PositiveOrZero Integer revision, @NotBlank @Size(max = 64) String sourceHash,
                                @jakarta.validation.constraints.NotEmpty @Size(max = 100) java.util.List<@NotBlank String> proposalIds) {}
    @PostMapping("/jobs/{id}/caption-repairs")
    ConversionService.RestoredDocument applyCaptionRepairs(@PathVariable String id, @Valid @RequestBody CaptionRepairRequest request) throws IOException {
        return service.applyCaptionRepairs(id, request.revision(), request.sourceHash(), request.proposalIds());
    }

    record ReviewDecision(@NotNull @PositiveOrZero Integer revision, @NotBlank String status, @NotBlank String fingerprint) {}
    @PutMapping("/jobs/{id}/review/decisions/{findingId}")
    DocumentInspector.Review decide(@PathVariable String id, @PathVariable String findingId, @Valid @RequestBody ReviewDecision decision) throws IOException {
        return service.decide(id, findingId, decision.revision(), decision.status(), decision.fingerprint());
    }

    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Job> create(@RequestPart MultipartFile file, @RequestParam(defaultValue = "article") String template,
            @RequestParam(required = false) String layout) throws IOException {
        return ResponseEntity.accepted().body(service.submit(file, template, LayoutOptions.parse(layout)));
    }

    record LayoutCopy(@NotNull @PositiveOrZero Integer revision, @NotBlank String template, @NotNull @Valid LayoutOptions layout) {}
    @PostMapping("/jobs/{id}/layout-copy")
    ResponseEntity<Job> layoutCopy(@PathVariable String id, @Valid @RequestBody LayoutCopy request) throws IOException {
        return ResponseEntity.accepted().body(service.deriveLayout(id, request.revision(), request.template(), request.layout()));
    }

    @GetMapping(value = "/jobs/{id}/source", produces = "text/plain;charset=UTF-8")
    String source(@PathVariable String id) throws IOException { return service.source(id); }

    record SourceUpdate(@NotBlank @Size(max = 2 * 1024 * 1024) String source, @PositiveOrZero int revision) {}

    @PutMapping("/jobs/{id}/source")
    Job saveSource(@PathVariable String id, @Valid @RequestBody SourceUpdate request) throws IOException {
        return service.saveSource(id, request.source(), request.revision());
    }

    @GetMapping("/jobs/{id}/versions")
    SourceVersionStore.History versions(@PathVariable String id) throws IOException { return service.versions(id); }

    @GetMapping(value = "/jobs/{id}/versions/{revision}/source", produces = "text/plain;charset=UTF-8")
    String versionSource(@PathVariable String id, @PathVariable int revision) throws IOException { return service.versionSource(id, revision); }

    record VersionRestore(@NotNull @PositiveOrZero Integer revision) {}

    @PostMapping("/jobs/{id}/versions/{version}/restore")
    ConversionService.RestoredDocument restore(@PathVariable String id, @PathVariable int version, @Valid @RequestBody VersionRestore request) throws IOException {
        return service.restoreDocument(id, version, request.revision());
    }

    @PostMapping("/jobs/{id}/preview")
    ResponseEntity<Job> preview(@PathVariable String id) throws IOException { return ResponseEntity.accepted().body(service.preview(id)); }

    @GetMapping(value = "/jobs/{id}/preview/log", produces = "text/plain;charset=UTF-8")
    String compilerLog(@PathVariable String id) throws IOException { return service.compilerLog(id); }

    @GetMapping("/jobs/{id}/diagnostics")
    CompilerDiagnostics.Report diagnostics(@PathVariable String id) throws IOException { return service.diagnostics(id); }

    @GetMapping("/jobs/{id}/preview")
    ResponseEntity<StreamingResponseBody> pdf(@PathVariable String id) {
        service.pdf(id); // Validate before sending headers; the stream acquires its own lifetime lease.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=preview.pdf").body(output -> service.writePdf(id, output));
    }

    @GetMapping("/jobs/{id}/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String id) {
        Job job = store.get(id);
        if (!job.status().equals("COMPLETED")) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "The conversion is not ready yet.");
        store.requireHistory(id);
        String filename = job.filename().replaceFirst("(?i)\\.docx$", "") + "-latex.zip";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(output -> service.writeArchive(id, output));
    }

    @GetMapping("/jobs/{id}/original")
    ResponseEntity<StreamingResponseBody> original(@PathVariable String id) {
        Job job = store.get(id);
        service.original(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(job.filename(), StandardCharsets.UTF_8).build().toString())
                .body(output -> service.writeOriginal(id, output));
    }

    @GetMapping("/sample.docx")
    ResponseEntity<byte[]> sample() throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=field-notes.docx").body(SampleDocument.create());
    }
}
