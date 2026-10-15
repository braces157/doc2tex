package dev.doc2tex;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class JobStore {
    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private final Path root;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    // Retain lock identities for this process lifetime, including after deletion.
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    JobStore(AppProperties properties) { root = Path.of(properties.dataDir()).toAbsolutePath().normalize(); }

    @PostConstruct
    void load() throws IOException {
        checkUnlinked(root);
        Files.createDirectories(root);
        try (var paths = Files.list(root)) {
            for (Path directory : paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path metadata = directory.resolve("job.properties");
                if (!Files.isRegularFile(metadata)) continue;
                try {
                    Job job = readMetadata(metadata);
                    String id = job.id();
                    if (!directory.getFileName().toString().equals(id)) continue;
                    if (List.of("QUEUED", "CONVERTING").contains(job.status()))
                        job = job.withStatus("FAILED", "The app restarted during conversion. Upload the document again.");
                    if (List.of("QUEUED", "COMPILING").contains(job.previewStatus()))
                        job = job.withPreview("FAILED", "The app restarted during compilation. Build the preview again.");
                    save(job);
                } catch (Exception exception) {
                    log.warn("Skipping unreadable job metadata in {}", directory.getFileName());
                }
            }
        }
        Path deletions = root.resolve(".deletions");
        checkUnlinked(deletions);
        if (Files.isDirectory(deletions)) {
            try (var markers = Files.list(deletions)) {
                for (Path marker : markers.filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
                    // An unreadable tombstone must fail startup rather than expose a partially removed job.
                    try {
                        Job job = readMetadata(marker).pendingDeletion();
                        if (!marker.getFileName().toString().equals(job.id() + ".properties"))
                            throw new IOException("Invalid deletion marker name");
                        jobs.put(job.id(), job);
                    } catch (RuntimeException exception) {
                        throw new IOException("Unreadable deletion marker. Back up storage and repair the marker before restarting.", exception);
                    }
                }
            }
        }
    }

    private Job readMetadata(Path path) throws IOException {
        checkUnlinked(path);
        try (InputStream input = Files.newInputStream(path)) {
            Properties p = new Properties(); p.load(input);
            return new Job(UUID.fromString(p.getProperty("id")).toString(), p.getProperty("filename"), p.getProperty("template"),
                    p.getProperty("status"), p.getProperty("previewStatus"), p.getProperty("message", ""),
                    p.getProperty("previewMessage", ""), Instant.parse(p.getProperty("createdAt")),
                    Instant.parse(p.getProperty("updatedAt")), Long.parseLong(p.getProperty("size")),
                    Integer.parseInt(p.getProperty("revision", "0")), p.getProperty("displayName", ""), false);
        }
    }

    Job get(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ApiException(HttpStatus.NOT_FOUND, "This conversion could not be found.");
        if (job.deletionPending()) throw new ApiException(HttpStatus.GONE,
                "This document is being removed. Cleanup is incomplete; retry Delete in All documents. Download any unsaved text before leaving this tab.");
        return job;
    }

    static final class Operation implements AutoCloseable {
        private final Lock lock;
        Operation(Lock lock) { this.lock = lock; }
        @Override public void close() { lock.unlock(); }
    }

    private ReentrantReadWriteLock lockFor(String id) {
        directory(id); // Validate the identifier and reject linked storage paths before accessing it.
        return locks.computeIfAbsent(id, ignored -> new ReentrantReadWriteLock(true));
    }

    Operation read(String id) { return acquire(id, false); }
    Operation edit(String id) { return acquire(id, true); }

    private Operation acquire(String id, boolean edit) {
        var lifecycle = lockFor(id);
        Lock lock = edit ? lifecycle.writeLock() : lifecycle.readLock();
        lock.lock();
        try { get(id); return new Operation(lock); }
        catch (RuntimeException exception) { lock.unlock(); throw exception; }
    }

    Job rename(String id, String name) throws IOException {
        if (name == null || name.length() > 200 || name.codePoints().anyMatch(Character::isISOControl))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use a display name up to 200 characters without control characters.");
        try (var operation = edit(id)) {
            Job renamed = get(id).renamed(name.strip());
            save(renamed);
            return renamed;
        }
    }

    void delete(String id) throws IOException {
        var lifecycle = lockFor(id);
        Lock lock = lifecycle.writeLock();
        // A read lease held by this same thread is also busy; never attempt a lock upgrade.
        if (lifecycle.isWriteLockedByCurrentThread() || lifecycle.getReadHoldCount() > 0 || !lock.tryLock())
            throw new ApiException(HttpStatus.CONFLICT, "This document is busy. Wait for its operation or download to finish, then retry Delete.");
        try {
            Job job = jobs.get(id);
            if (job == null) throw new ApiException(HttpStatus.NOT_FOUND, "This conversion could not be found.");
            if (!job.deletionPending() && (List.of("QUEUED", "CONVERTING").contains(job.status())
                    || List.of("QUEUED", "COMPILING").contains(job.previewStatus())))
                throw new ApiException(HttpStatus.CONFLICT, "Wait for conversion or PDF compilation to finish, then retry Delete.");
            Path directory = directory(id);
            // Preflight the whole tree before marking deletion, including Windows junctions.
            visitTree(directory, false);
            Path marker = root.resolve(".deletions").resolve(id + ".properties");
            if (!job.deletionPending()) {
                writeMetadata(job.pendingDeletion(), marker);
                jobs.put(id, job.pendingDeletion());
            }
            try {
                visitTree(directory, true);
                checkUnlinked(marker);
                removePath(marker);
                jobs.remove(id);
            } catch (IOException exception) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Deletion could not finish. The document is unavailable until cleanup completes. Retry Delete in All documents.");
            }
        } finally { lock.unlock(); }
    }

    private void visitTree(Path directory, boolean remove) throws IOException {
        checkUnlinked(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            private void check(Path path, BasicFileAttributes attrs) throws IOException {
                checkUnlinked(path);
                if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Linked job paths cannot be removed.");
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                check(path, attrs); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                check(path, attrs); if (remove) removePath(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException exception) throws IOException {
                if (exception != null) throw exception;
                checkUnlinked(path); if (remove) removePath(path); return FileVisitResult.CONTINUE;
            }
        });
    }

    // Overridden only by temporary-directory failure-injection tests.
    void removePath(Path path) throws IOException { Files.deleteIfExists(path); }

    private void checkUnlinked(Path path) {
        Path current = path.getRoot();
        try {
            for (Path part : path) {
                current = current.resolve(part);
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
                var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) throw new ApiException(HttpStatus.CONFLICT,
                        "Linked or reparse-point storage paths are not supported. Remove the link before retrying.");
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Could not validate the document storage path. Check permissions and retry.");
        }
    }

    List<Job> list() {
        return jobs.values().stream().sorted(newestFirst()).limit(30).toList();
    }

    private Comparator<Job> newestFirst() {
        return Comparator.comparing(Job::createdAt).reversed().thenComparing(Job::id);
    }

    record LibraryPage(List<Job> items, int page, int size, long total) {}

    LibraryPage library(String query, String status, String sort, int page, int size) {
        if (query.length() > 200 || page < 0 || size < 1 || size > 100
                || !List.of("", "QUEUED", "CONVERTING", "COMPLETED", "FAILED").contains(status)
                || !List.of("newest", "oldest").contains(sort)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use a query up to 200 characters, a valid conversion status, newest/oldest sort, a non-negative page, and size 1–100.");
        }
        String term = query.strip().toLowerCase(Locale.ROOT);
        Comparator<Job> order = sort.equals("newest") ? newestFirst()
                : Comparator.comparing(Job::createdAt).thenComparing(Job::id);
        // Capture one collection snapshot so the count and page describe the same results.
        List<Job> matches = jobs.values().stream()
                .filter(job -> job.filename().toLowerCase(Locale.ROOT).contains(term) || job.displayName().toLowerCase(Locale.ROOT).contains(term))
                .filter(job -> status.isEmpty() || job.status().equals(status))
                .sorted(order).toList();
        List<Job> items = matches.stream().skip((long) page * size).limit(size).toList();
        return new LibraryPage(items, page, size, matches.size());
    }

    Path directory(String id) {
        String canonical;
        try { canonical = UUID.fromString(id).toString(); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.NOT_FOUND, "Unknown conversion."); }
        Path path = root.resolve(canonical).normalize();
        if (!path.getParent().equals(root)) throw new IllegalArgumentException("Invalid job path");
        checkUnlinked(path);
        return path;
    }

    synchronized void save(Job job) throws IOException {
        Path directory = directory(job.id());
        Files.createDirectories(directory);
        writeMetadata(job, directory.resolve("job.properties"));
        jobs.put(job.id(), job);
    }

    private void writeMetadata(Job job, Path metadata) throws IOException {
        checkUnlinked(metadata);
        Files.createDirectories(metadata.getParent());
        Properties p = new Properties();
        p.setProperty("id", job.id());
        p.setProperty("filename", job.filename());
        p.setProperty("template", job.template());
        p.setProperty("status", job.status());
        p.setProperty("previewStatus", job.previewStatus());
        p.setProperty("message", job.message());
        p.setProperty("previewMessage", job.previewMessage());
        p.setProperty("createdAt", job.createdAt().toString());
        p.setProperty("updatedAt", job.updatedAt().toString());
        p.setProperty("size", Long.toString(job.size()));
        p.setProperty("revision", Integer.toString(job.revision()));
        p.setProperty("displayName", job.displayName());
        Path temporary = metadata.resolveSibling(metadata.getFileName() + ".tmp");
        checkUnlinked(temporary);
        try (OutputStream output = Files.newOutputStream(temporary)) { p.store(output, "doc2TeX job"); }
        Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
