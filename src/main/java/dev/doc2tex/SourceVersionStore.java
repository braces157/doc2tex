package dev.doc2tex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Immutable snapshots; job.properties revision is the sole committed-source pointer. */
class SourceVersionStore {
    static final int MAX_SOURCE = 2 * 1024 * 1024;
    private final int maxVersions;
    private final long maxBytes;

    SourceVersionStore() { this(100, 100L * 1024 * 1024); }
    SourceVersionStore(int maxVersions, long maxBytes) { this.maxVersions = maxVersions; this.maxBytes = maxBytes; }

    record Version(int revision, Instant createdAt, long bytes, String origin, int restoredFrom) {}
    record History(List<Version> items, int currentRevision, int firstRevision, boolean legacyHistoryUnavailable,
                   int maxVersions, long maxBytes, long bytesUsed) {}
    enum Stage { SNAPSHOT_PUBLISHED, CACHE_REPLACED, METADATA_COMMITTED }
    @FunctionalInterface interface Commit { void save(Job job) throws IOException; }

    // Failure-injection seam; production never throws after a successful metadata commit.
    void checkpoint(Stage stage) throws IOException {}

    private Path root(Path directory) { return directory.resolve(".versions"); }
    private Path historyFile(Path directory) { return root(directory).resolve("history.properties"); }
    private Path snapshot(Path directory, int revision) {
        if (revision < 0) throw new ApiException(HttpStatus.NOT_FOUND, "This saved version could not be found.");
        Path path = root(directory).resolve(Integer.toString(revision)); JobStore.checkUnlinked(path); return path;
    }
    boolean initialized(Path directory) { JobStore.checkUnlinked(historyFile(directory)); return Files.isRegularFile(historyFile(directory)); }

    void initialize(Job job, Path directory, boolean legacy) throws IOException {
        if (initialized(directory)) return;
        Path source = directory.resolve("output/main.tex"); JobStore.checkUnlinked(source);
        byte[] bytes = readBounded(source);
        Files.createDirectories(root(directory));
        Path initial = snapshot(directory, job.revision());
        if (Files.exists(initial, LinkOption.NOFOLLOW_LINKS)) {
            // An interrupted baseline initialization may have published its snapshot already.
            if (!MessageDigest.isEqual(bytes, readSnapshot(directory, job.revision())))
                throw new IOException("The initial saved snapshot does not match the source. Back up this project before repairing history.");
        } else writeSnapshot(directory, job.revision(), bytes, legacy ? "legacy" : "converted", -1);
        Properties properties = new Properties();
        properties.setProperty("firstRevision", Integer.toString(job.revision()));
        properties.setProperty("legacy", Boolean.toString(legacy));
        atomicProperties(historyFile(directory), properties);
    }

    void recover(Job job, Path directory) throws IOException {
        if (!initialized(directory)) {
            Path source = directory.resolve("output/main.tex"); JobStore.checkUnlinked(source);
            if (!Files.isRegularFile(source)) return; // Metadata-only jobs still expose the existing source-load error.
            initialize(job, directory, true);
        }
        byte[] committed = readVersion(job, directory, job.revision());
        try (var paths = Files.list(root(directory))) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                boolean staged = name.matches("\\.pending-[0-9a-f-]{36}");
                boolean future = name.matches("[0-9]+") && Long.parseLong(name) > job.revision();
                if (staged || future) removeUncommitted(path);
            }
        }
        replaceCache(directory, committed);
    }

    History list(Job job, Path directory) throws IOException {
        Properties history = properties(historyFile(directory));
        int first = integer(history, "firstRevision");
        if (first < 0 || first > job.revision()) throw new IOException("Saved history has an invalid baseline revision.");
        List<Version> items;
        try (var paths = Files.list(root(directory))) {
            items = paths.filter(path -> path.getFileName().toString().matches("[0-9]+"))
                    .map(path -> Integer.parseInt(path.getFileName().toString()))
                    .filter(revision -> revision >= first && revision <= job.revision()).sorted(Comparator.reverseOrder())
                    .map(revision -> {
                        try { return metadata(directory, revision); }
                        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                    }).toList();
        } catch (java.io.UncheckedIOException exception) { throw exception.getCause(); }
        if (items.isEmpty() || items.getFirst().revision() != job.revision() || items.getLast().revision() != first)
            throw new IOException("Saved history is missing its committed or initial revision.");
        return new History(items, job.revision(), first, Boolean.parseBoolean(history.getProperty("legacy")),
                maxVersions, maxBytes, items.stream().mapToLong(Version::bytes).sum());
    }

    byte[] current(Job job, Path directory) throws IOException {
        // Synthetic/legacy callers may create metadata directly. Startup and first mutation initialize history.
        return initialized(directory) ? readVersion(job, directory, job.revision()) : readBounded(directory.resolve("output/main.tex"));
    }

    byte[] readVersion(Job job, Path directory, int revision) throws IOException {
        if (!initialized(directory)) {
            if (revision == job.revision()) return current(job, directory);
            throw new ApiException(HttpStatus.NOT_FOUND, "Earlier saved history is unavailable for this legacy project.");
        }
        int first = integer(properties(historyFile(directory)), "firstRevision");
        if (revision < first || revision > job.revision()) throw new ApiException(HttpStatus.NOT_FOUND, "This saved version could not be found.");
        return readSnapshot(directory, revision);
    }

    Job commit(Job current, Path directory, String source, int restoredFrom, Commit persist) throws IOException {
        recover(current, directory);
        if (!initialized(directory)) throw new IOException("The current source is unavailable; history cannot be initialized.");
        History history = list(current, directory);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SOURCE) throw new ApiException(HttpStatus.BAD_REQUEST, "The source exceeds the 2 MB limit.");
        if (history.items().size() >= maxVersions || history.bytesUsed() + bytes.length > maxBytes)
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE,
                    "Saved history is full (" + maxVersions + " versions or " + maxBytes + " bytes). Download this source and export the project before starting a new document. Existing versions have been preserved.");
        if (current.revision() == Integer.MAX_VALUE) throw new ApiException(HttpStatus.CONFLICT, "This project's revision limit has been reached.");
        Job next = current.edited();
        writeSnapshot(directory, next.revision(), bytes, restoredFrom < 0 ? "saved" : "restored", restoredFrom);
        checkpoint(Stage.SNAPSHOT_PUBLISHED);
        replaceCache(directory, bytes);
        checkpoint(Stage.CACHE_REPLACED);
        persist.save(next); // Atomic commit pointer update; prior snapshots remain immutable.
        checkpoint(Stage.METADATA_COMMITTED);
        return next;
    }

    private void writeSnapshot(Path directory, int revision, byte[] source, String origin, int restoredFrom) throws IOException {
        Path stage = root(directory).resolve(".pending-" + UUID.randomUUID());
        JobStore.checkUnlinked(stage); Files.createDirectories(stage);
        writeForced(stage.resolve("main.tex"), source);
        Properties metadata = new Properties();
        metadata.setProperty("revision", Integer.toString(revision));
        metadata.setProperty("createdAt", Instant.now().toString());
        metadata.setProperty("bytes", Integer.toString(source.length));
        metadata.setProperty("sha256", digest(source));
        metadata.setProperty("origin", origin); metadata.setProperty("restoredFrom", Integer.toString(restoredFrom));
        writeForced(stage.resolve("version.properties"), propertyBytes(metadata));
        forceDirectory(stage);
        Files.move(stage, snapshot(directory, revision), StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(root(directory));
    }

    private Version metadata(Path directory, int revision) throws IOException {
        Properties p = properties(snapshot(directory, revision).resolve("version.properties"));
        try {
            if (integer(p, "revision") != revision) throw new IOException("Saved version identity does not match its directory.");
            long bytes = Long.parseLong(p.getProperty("bytes"));
            if (bytes < 0 || bytes > MAX_SOURCE) throw new IOException("Saved version size is invalid.");
            return new Version(revision, Instant.parse(p.getProperty("createdAt")), bytes, p.getProperty("origin"), integer(p, "restoredFrom"));
        } catch (RuntimeException exception) { throw new IOException("Saved version metadata is unreadable.", exception); }
    }

    private byte[] readSnapshot(Path directory, int revision) throws IOException {
        Version version = metadata(directory, revision);
        byte[] bytes = readBounded(snapshot(directory, revision).resolve("main.tex"));
        String expected = properties(snapshot(directory, revision).resolve("version.properties")).getProperty("sha256");
        if (version.bytes() != bytes.length || !digest(bytes).equals(expected)) throw new IOException("Saved version integrity check failed. Back up this project before repairing history.");
        return bytes;
    }

    private byte[] readBounded(Path path) throws IOException {
        JobStore.checkUnlinked(path);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_SOURCE + 1);
            if (bytes.length > MAX_SOURCE) throw new IOException("Saved source exceeds the 2 MB limit.");
            return bytes;
        }
    }

    private void replaceCache(Path directory, byte[] source) throws IOException {
        Path cache = directory.resolve("output/main.tex"), temporary = cache.resolveSibling("main.tex.version-tmp");
        JobStore.checkUnlinked(cache); JobStore.checkUnlinked(temporary);
        if (Files.isRegularFile(cache) && Files.size(cache) <= MAX_SOURCE && MessageDigest.isEqual(source, readBounded(cache))) return;
        Files.createDirectories(cache.getParent()); writeForced(temporary, source);
        Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(cache.getParent());
    }

    private void removeUncommitted(Path path) throws IOException {
        JobStore.checkUnlinked(path);
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                JobStore.checkUnlinked(child); Files.delete(child);
            }
        }
    }

    private Properties properties(Path path) throws IOException {
        JobStore.checkUnlinked(path);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192) throw new IOException("Saved history metadata is too large.");
            Properties p = new Properties(); p.load(new java.io.ByteArrayInputStream(bytes)); return p;
        }
    }
    private int integer(Properties p, String name) throws IOException {
        try { return Integer.parseInt(p.getProperty(name)); }
        catch (RuntimeException exception) { throw new IOException("Saved history metadata is invalid.", exception); }
    }
    private void atomicProperties(Path path, Properties properties) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        writeForced(temporary, propertyBytes(properties));
        JobStore.checkUnlinked(path); Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(path.getParent());
    }

    static void forceDirectory(Path directory) {
        // POSIX needs directory-entry persistence as well as forced file contents.
        // Windows providers generally deny opening a directory as a FileChannel.
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { /* Best effort where directory fsync is unsupported. */ }
    }
    private byte[] propertyBytes(Properties properties) throws IOException {
        var output = new ByteArrayOutputStream(); properties.store(output, "doc2TeX saved source version"); return output.toByteArray();
    }
    private void writeForced(Path path, byte[] bytes) throws IOException {
        JobStore.checkUnlinked(path);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
    }
    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
