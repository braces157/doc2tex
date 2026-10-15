package dev.doc2tex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class DocxValidator {
    void validate(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Set<String> names = new HashSet<>();
            long expanded = 0;
            int count = 0;
            var entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (++count > 2000 || !names.add(name) || name.startsWith("/") || name.contains("\\")
                        || name.contains(":") || java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw invalid("The DOCX archive contains unsupported entries.");
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        expanded += read;
                        if (expanded > 100L * 1024 * 1024) throw invalid("The expanded document exceeds 100 MB.");
                    }
                }
            }
            if (!names.contains("[Content_Types].xml") || !names.contains("word/document.xml")
                    || !names.contains("_rels/.rels")) {
                throw invalid("This file is not a valid Word DOCX document.");
            }
            if (names.stream().anyMatch(name -> name.endsWith("vbaProject.bin"))) {
                throw invalid("Macro-enabled documents are not supported. Save a .docx copy first.");
            }
        } catch (IOException exception) {
            throw invalid("This file could not be opened as a DOCX document.");
        }
    }

    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
