package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class JobLifecycleTest {
    @TempDir Path temporary;

    private AppProperties properties() { return new AppProperties(temporary.resolve("jobs").toString(), "", "", 1, 1, 10, 10); }
    private JobStore store() throws IOException { JobStore store = new JobStore(properties()); store.load(); return store; }
    private Job create(JobStore store) throws IOException {
        Job job = new Job(UUID.randomUUID().toString(), "original.docx", "article", "COMPLETED", "NOT_REQUESTED", "", "",
                Instant.now(), Instant.now(), 100, 2);
        store.save(job);
        Path root = store.directory(job.id());
        Files.createDirectories(root.resolve("output/media"));
        Files.createDirectories(root.resolve("preview-2"));
        Files.writeString(root.resolve("input.docx"), "synthetic original");
        Files.writeString(root.resolve("output/main.tex"), "Saved Unicode text ภาษาไทย");
        Files.writeString(root.resolve("output/media/image.png"), "synthetic image");
        Files.writeString(root.resolve("preview-2/main.pdf"), "synthetic PDF");
        return job;
    }

    @Test void renamePersistsAndKeepsOriginalIdentityAndSource() throws Exception {
        JobStore first = store(); Job job = create(first);
        Job renamed = first.rename(job.id(), "  Research ภาษาไทย  ");
        assertEquals("Research ภาษาไทย", renamed.displayName());
        assertEquals(job.filename(), renamed.filename()); assertEquals(job.revision(), renamed.revision());
        assertEquals(job.createdAt(), renamed.createdAt()); assertEquals(job.template(), renamed.template());
        JobStore restored = store();
        assertEquals(renamed, restored.get(job.id()));
        assertEquals(1, restored.library("research", "", "newest", 0, 20).total());
        assertEquals(1, restored.library("original", "", "newest", 0, 20).total());
        assertEquals("Saved Unicode text ภาษาไทย", Files.readString(restored.directory(job.id()).resolve("output/main.tex")));
        assertEquals("", restored.rename(job.id(), " ").displayName());
        assertThrows(ApiException.class, () -> restored.rename(job.id(), "bad\nname"));
        assertThrows(ApiException.class, () -> restored.rename(job.id(), "x".repeat(201)));
        assertThrows(ApiException.class, () -> restored.rename(job.id(), null));
    }

    @Test void legacyMetadataLoadsWithFilenameFallback() throws Exception {
        JobStore first = store(); Job job = create(first);
        Path metadata = first.directory(job.id()).resolve("job.properties");
        Files.writeString(metadata, Files.readString(metadata).replace("displayName=\n", "").replace("displayName=\r\n", ""));
        assertEquals("", store().get(job.id()).displayName());
    }

    @Test void deletionRemovesAllOwnedFilesAndSurvivesRestart() throws Exception {
        JobStore first = store(); Job job = create(first); Job other = create(first);
        Path directory = first.directory(job.id());
        first.delete(job.id());
        assertFalse(Files.exists(directory));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> first.get(job.id())).status);
        assertEquals(List.of(other), store().list());
    }

    @Test void cleanupFailureIsUnavailableAndRetryableAfterRestart() throws Exception {
        JobStore first = new JobStore(properties()) {
            @Override void removePath(Path path) throws IOException {
                if (path.getFileName().toString().equals("main.tex")) throw new IOException("Injected cleanup failure");
                super.removePath(path);
            }
        };
        first.load(); Job job = create(first);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, () -> first.delete(job.id())).status);
        assertEquals(HttpStatus.GONE, assertThrows(ApiException.class, () -> first.get(job.id())).status);
        assertTrue(first.list().getFirst().deletionPending());
        JobStore restored = store();
        assertTrue(restored.list().getFirst().deletionPending());
        assertEquals(HttpStatus.GONE, assertThrows(ApiException.class, () -> restored.rename(job.id(), "cannot reopen")).status);
        restored.delete(job.id());
        assertTrue(store().list().isEmpty());
        assertFalse(Files.exists(first.directory(job.id())));
    }

    @Test void markerCleanupFailureAlsoSurvivesRestartWithoutJobDirectory() throws Exception {
        JobStore first = new JobStore(properties()) {
            @Override void removePath(Path path) throws IOException {
                if (path.getParent().getFileName().toString().equals(".deletions")) throw new IOException("Injected marker failure");
                super.removePath(path);
            }
        };
        first.load(); Job job = create(first);
        assertThrows(ApiException.class, () -> first.delete(job.id()));
        assertFalse(Files.exists(first.directory(job.id())));
        JobStore restored = store();
        assertTrue(restored.list().getFirst().deletionPending());
        assertEquals(HttpStatus.GONE, assertThrows(ApiException.class, () -> restored.read(job.id())).status);
        restored.delete(job.id());
        assertTrue(store().list().isEmpty());
    }

    @Test void queuedJobsAndActiveLeasesRejectDeletion() throws Exception {
        JobStore store = store(); Job job = create(store);
        for (String status : List.of("QUEUED", "CONVERTING")) {
            store.save(job.withStatus(status, ""));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> store.delete(job.id())).status);
        }
        for (String status : List.of("QUEUED", "COMPILING")) {
            store.save(job.withPreview(status, ""));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> store.delete(job.id())).status);
        }
        store.save(job);
        try (var operation = store.read(job.id())) {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> store.delete(job.id())).status);
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try (var operation = store.edit(job.id())) { entered.countDown(); release.await(10, TimeUnit.SECONDS); }
            catch (Throwable exception) { failure.set(exception); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> store.delete(job.id())).status);
        } finally { release.countDown(); worker.join(5000); }
        assertNull(failure.get()); assertFalse(worker.isAlive());
        store.delete(job.id());
        assertThrows(ApiException.class, () -> store.edit(job.id()));
        assertThrows(ApiException.class, () -> store.delete("../../outside"));
    }

    @Test void unreadableTombstoneCannotExposePartiallyDeletedJob() throws Exception {
        JobStore first = store(); Job job = create(first);
        Path markers = temporary.resolve("jobs/.deletions"); Files.createDirectories(markers);
        Files.writeString(markers.resolve(job.id() + ".properties"), "damaged marker");
        assertThrows(IOException.class, this::store);
    }

    private void linkDirectory(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            // Directory junction creation needs no Windows symlink privilege. Both paths are test-owned.
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } else Files.createSymbolicLink(link, target);
    }

    @Test void linkedChildCannotRemoveExternalFiles() throws Exception {
        JobStore store = store(); Job job = create(store);
        Path outside = temporary.resolve("outside"); Files.createDirectories(outside);
        Path sentinel = outside.resolve("keep.txt"); Files.writeString(sentinel, "Keep outside data");
        Path link = store.directory(job.id()).resolve("output/linked");
        linkDirectory(link, outside);
        try {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> store.delete(job.id())).status);
            assertEquals("Keep outside data", Files.readString(sentinel));
            assertFalse(store.list().getFirst().deletionPending());
        } finally { Files.delete(link); }
        store.delete(job.id());
        assertEquals("Keep outside data", Files.readString(sentinel));
    }

    @Test void linkedJobRootCannotRemoveExternalFiles() throws Exception {
        JobStore store = store(); Job job = create(store);
        Path root = store.directory(job.id()), moved = temporary.resolve("moved-job");
        Files.move(root, moved);
        linkDirectory(root, moved);
        try {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> store.delete(job.id())).status);
            assertEquals("Saved Unicode text ภาษาไทย", Files.readString(moved.resolve("output/main.tex")));
        } finally { Files.delete(root); }
    }
}
