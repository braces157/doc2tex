package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxValidatorTest {
    @TempDir Path directory;
    private final DocxValidator validator = new DocxValidator();

    @Test void acceptsStructuredWordDocument() throws Exception {
        Path file = directory.resolve("sample.docx");
        Files.write(file, SampleDocument.create());
        assertDoesNotThrow(() -> validator.validate(file));
    }

    @Test void rejectsRenamedTextFile() throws Exception {
        Path file = directory.resolve("fake.docx");
        Files.writeString(file, "This is not a ZIP archive");
        assertThrows(ApiException.class, () -> validator.validate(file));
    }

    @Test void rejectsTraversalBeforeExtraction() throws Exception {
        Path file = directory.resolve("traversal.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("word/../../escape.txt"));
            zip.write(new byte[]{1});
        }
        assertThrows(ApiException.class, () -> validator.validate(file));
        assertFalse(Files.exists(directory.resolve("escape.txt")));
    }

    @Test void rejectsZipWithoutWordParts() throws Exception {
        Path file = directory.resolve("wrong.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
        }
        assertThrows(ApiException.class, () -> validator.validate(file));
    }

    @Test void rejectsCompressedExpansionBombs() throws Exception {
        Path file = directory.resolve("expansion.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] block = new byte[8192];
            for (int i = 0; i <= 12800; i++) zip.write(block);
        }
        assertTrue(Files.size(file) < 1024 * 1024, "Fixture should remain small on disk");
        assertThrows(ApiException.class, () -> validator.validate(file));
    }

    @Test void refusesMacroPayloadInsideDocxArchive() throws Exception {
        Path file = directory.resolve("macro.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : java.util.List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/vbaProject.bin")) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1}); zip.closeEntry();
            }
        }
        var exception = assertThrows(ApiException.class, () -> validator.validate(file));
        assertTrue(exception.getMessage().contains("Macro-enabled"));
    }
}
