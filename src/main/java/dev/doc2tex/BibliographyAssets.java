package dev.doc2tex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Fixed project paths and integrity metadata; uploaded names are display metadata only. */
record BibliographyAssets(String filename, byte[] bibliography, byte[] font, byte[] license) {
    static final int MAX_BIB = 1024 * 1024, MAX_FONT = 2 * 1024 * 1024, MAX_LICENSE = 64 * 1024;
    static final String BIB = "references.bib", FONT = "NotoSerif-Regular.ttf", LICENSE = "OFL-Noto.txt";
    static final String FOLDER = "bibliography";

    BibliographyAssets {
        if (!validName(filename) || bibliography == null || bibliography.length == 0 || bibliography.length > MAX_BIB
                || (font == null) != (license == null) || font != null && (font.length == 0 || font.length > MAX_FONT || license.length == 0 || license.length > MAX_LICENSE))
            throw new IllegalArgumentException("Bibliography asset metadata is invalid.");
        bibliography = bibliography.clone(); font = font == null ? null : font.clone(); license = license == null ? null : license.clone();
    }

    static BibliographyAssets uploaded(String filename, byte[] bytes) throws IOException {
        Path fonts = Path.of(System.getenv().getOrDefault("DOC2TEX_FONT_DIR", ".tools/fonts")).toAbsolutePath();
        Path font = fonts.resolve(FONT), license = fonts.resolve(LICENSE);
        return Files.isRegularFile(font) && Files.isRegularFile(license)
                ? new BibliographyAssets(filename, bytes, bounded(font, MAX_FONT), bounded(license, MAX_LICENSE))
                : new BibliographyAssets(filename, bytes, null, null);
    }

    static BibliographyAssets replace(String filename, byte[] bytes, BibliographyAssets previous) throws IOException {
        return previous != null && previous.font() != null
                ? new BibliographyAssets(filename, bytes, previous.font(), previous.license()) : uploaded(filename, bytes);
    }

    Map<String, byte[]> files() {
        var files = new LinkedHashMap<String, byte[]>(); files.put(BIB, bibliography);
        if (font != null) { files.put(FONT, font); files.put(LICENSE, license); }
        return files;
    }
    long totalBytes() { return bibliography.length + (font == null ? 0L : (long) font.length + license.length); }

    void metadata(Properties properties) {
        properties.setProperty("bib.present", "true"); properties.setProperty("bib.filename", filename);
        properties.setProperty("bib.font", Boolean.toString(font != null));
        files().forEach((name, bytes) -> {
            properties.setProperty("bib." + name + ".bytes", Integer.toString(bytes.length));
            properties.setProperty("bib." + name + ".sha256", SourceVersionStore.digest(bytes));
        });
    }

    static long declaredBytes(Properties properties) throws IOException {
        if (!flag(properties, "bib.present")) return 0;
        if (!validName(properties.getProperty("bib.filename"))) throw new IOException("Saved bibliography filename is invalid.");
        long bytes = size(properties, BIB, MAX_BIB);
        if (flag(properties, "bib.font")) bytes += size(properties, FONT, MAX_FONT) + size(properties, LICENSE, MAX_LICENSE);
        return bytes;
    }

    static BibliographyAssets read(Path snapshot, Properties properties) throws IOException {
        declaredBytes(properties); Path folder = snapshot.resolve(FOLDER); JobStore.checkUnlinked(folder);
        if (!flag(properties, "bib.present")) {
            if (Files.exists(folder.resolve(BIB))) throw new IOException("Saved bibliography metadata is missing. Back up this project before repairing history.");
            return null;
        }
        byte[] bib = verified(folder, BIB, MAX_BIB, properties); boolean font = flag(properties, "bib.font");
        return new BibliographyAssets(properties.getProperty("bib.filename"), bib, font ? verified(folder, FONT, MAX_FONT, properties) : null,
                font ? verified(folder, LICENSE, MAX_LICENSE, properties) : null);
    }

    private static byte[] verified(Path folder, String name, int limit, Properties properties) throws IOException {
        byte[] bytes = bounded(folder.resolve(name), limit);
        if (bytes.length != size(properties, name, limit) || !SourceVersionStore.digest(bytes).equals(properties.getProperty("bib." + name + ".sha256")))
            throw new IOException("Saved bibliography integrity check failed. Back up this project before repairing history.");
        return bytes;
    }
    private static long size(Properties properties, String name, int limit) throws IOException {
        try {
            long bytes = Long.parseLong(properties.getProperty("bib." + name + ".bytes")); String hash = properties.getProperty("bib." + name + ".sha256", "");
            if (bytes <= 0 || bytes > limit || !hash.matches("[a-f0-9]{64}")) throw new IOException("Saved bibliography metadata is invalid.");
            return bytes;
        } catch (NumberFormatException exception) { throw new IOException("Saved bibliography size is invalid.", exception); }
    }
    private static boolean flag(Properties properties, String name) throws IOException {
        String value = properties.getProperty(name, "false");
        if (!value.equals("true") && !value.equals("false")) throw new IOException("Saved bibliography flag is invalid.");
        return Boolean.parseBoolean(value);
    }
    private static boolean validName(String name) {
        return name != null && name.length() <= 200 && name.toLowerCase(java.util.Locale.ROOT).endsWith(".bib")
                && !name.contains("/") && !name.contains("\\") && name.codePoints().noneMatch(Character::isISOControl);
    }
    static byte[] bounded(Path path, int limit) throws IOException {
        JobStore.checkUnlinked(path);
        try (var input = Files.newInputStream(path)) { byte[] bytes = input.readNBytes(limit + 1); if (bytes.length > limit) throw new IOException("Bibliography asset exceeds its size limit."); return bytes; }
    }
}
