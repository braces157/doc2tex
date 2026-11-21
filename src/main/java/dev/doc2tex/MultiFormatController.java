package dev.doc2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
class MultiFormatController {
    private final MultiFormatService multiFormat;

    MultiFormatController(MultiFormatService multiFormat) {
        this.multiFormat = multiFormat;
    }

    @GetMapping("/convert/formats")
    MultiFormatService.FormatCatalog formats() {
        return multiFormat.formats();
    }

    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<byte[]> convert(
            @RequestPart("file") MultipartFile file,
            @RequestParam("targetFormat") String targetFormat,
            @RequestParam(defaultValue = "false") boolean toc) throws IOException {
        MultiFormatService.ConvertedFile result = multiFormat.convert(file, targetFormat, toc);
        return responseFor(result);
    }

    @GetMapping("/jobs/{id}/export/{format}")
    ResponseEntity<byte[]> exportJob(
            @PathVariable String id,
            @PathVariable String format) throws IOException {
        MultiFormatService.ConvertedFile result = multiFormat.exportJob(id, format);
        return responseFor(result);
    }

    private static ResponseEntity<byte[]> responseFor(MultiFormatService.ConvertedFile file) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        var builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if (!file.warnings().isEmpty()) {
            builder.header("X-Doc2tex-Warnings", file.warnings());
        }
        return builder.body(file.bytes());
    }
}
