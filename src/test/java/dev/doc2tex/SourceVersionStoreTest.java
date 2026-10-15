package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class SourceVersionStoreTest {
    @TempDir Path temporary;
    private AppProperties properties() { return new AppProperties(temporary.resolve("jobs").toString(), "", "", 1, 1, 10, 10); }
    private JobStore store(SourceVersionStore versions) throws IOException { JobStore store = new JobStore(properties(), versions); store.load(); return store; }
    private Job create(JobStore store, int revision, String source) throws IOException {
        Job job = new Job(UUID.randomUUID().toString(), "history.docx", "article", "COMPLETED", "NOT_REQUESTED", "", "",
                Instant.now(), Instant.now(), 100, revision);
        store.save(job);
        Path directory = store.directory(job.id()); Files.createDirectories(directory.resolve("output"));
        Files.write(directory.resolve("input.docx"), SampleDocument.create());
        Files.writeString(directory.resolve("output/main.tex"), source);
        return job;
    }
    private ConversionService service(JobStore store) {
        return new ConversionService(store, new Tools(properties()), new ToolRunner(), new DocxValidator(), properties(), new DocumentInspector(), new FontSupport());
    }
    private String current(JobStore store, Job job) throws IOException {
        return new String(store.versions().current(job, store.directory(job.id())), StandardCharsets.UTF_8);
    }
    private String archiveSource(ConversionService service, String id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); service.writeArchive(id, bytes);
        String source = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertFalse(entry.getName().contains(".versions"));
                if (entry.getName().equals("main.tex")) { assertNull(source); source = new String(zip.readAllBytes(), StandardCharsets.UTF_8); }
            }
        }
        assertNotNull(source); return source;
    }

    @Test void immutableOriginalSavesAndRestoreSurviveRestartAndExportCurrentSourceOnly() throws Exception {
        SourceVersionStore versions = new SourceVersionStore(); JobStore store = store(versions);
        Job job = create(store, 0, "Original ภาษาไทย"); Path directory = store.directory(job.id());
        versions.initialize(job, directory, false);
        byte[] original = Files.readAllBytes(directory.resolve(".versions/0/main.tex"));
        ConversionService service = service(store);
        try {
            Job first = service.saveSource(job.id(), "First edit", 0);
            Job second = service.saveSource(job.id(), "Second edit", first.revision());
            Job restored = service.restore(job.id(), 0, second.revision());
            assertEquals(3, restored.revision()); assertEquals("NOT_REQUESTED", restored.previewStatus());
            assertEquals("Original ภาษาไทย", service.source(job.id()));
            assertEquals("First edit", service.versionSource(job.id(), 1));
            assertEquals("Second edit", service.versionSource(job.id(), 2));
            var history = service.versions(job.id());
            assertEquals(List.of(3, 2, 1, 0), history.items().stream().map(SourceVersionStore.Version::revision).toList());
            assertFalse(history.legacyHistoryUnavailable()); assertEquals(0, history.firstRevision());
            assertEquals("restored", history.items().getFirst().origin()); assertEquals(0, history.items().getFirst().restoredFrom());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve(".versions/0/main.tex")));
            assertEquals("Original ภาษาไทย", archiveSource(service, job.id()));
            JobStore restarted = store(new SourceVersionStore());
            assertEquals(restored, restarted.get(job.id()));
            assertEquals("Original ภาษาไทย", current(restarted, restarted.get(job.id())));
            assertEquals(4, restarted.versions().list(restored, directory).items().size());
        } finally { service.stop(); }
    }

    @Test void legacyHistoryStartsAtCurrentRevisionAndInterruptedBaselineCanResume() throws Exception {
        JobStore store = store(new SourceVersionStore()); Job job = create(store, 5, "Legacy current source");
        Path directory = store.directory(job.id());
        store.versions().initialize(job, directory, true);
        byte[] baseline = Files.readAllBytes(directory.resolve(".versions/5/main.tex"));
        Files.delete(directory.resolve(".versions/history.properties")); // Crash after baseline publication, before history record.
        JobStore restarted = store(new SourceVersionStore());
        var history = restarted.versions().list(job, directory);
        assertTrue(history.legacyHistoryUnavailable()); assertEquals(5, history.firstRevision());
        assertArrayEquals(baseline, Files.readAllBytes(directory.resolve(".versions/5/main.tex")));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> restarted.versions().readVersion(job, directory, 4)).status);
        Job next = restarted.versions().commit(job, directory, "New saved source", -1, restarted::save);
        assertEquals(6, next.revision()); assertEquals("Legacy current source", new String(baseline, StandardCharsets.UTF_8));
    }

    @Test void interruptedSaveAlwaysExposesTheCommittedPairAndSafelyRetriesAfterRestart() throws Exception {
        for (var stage : SourceVersionStore.Stage.values()) {
            SourceVersionStore failing = new SourceVersionStore() {
                @Override void checkpoint(Stage reached) throws IOException { if (reached == stage) throw new IOException("Simulated interruption at " + stage); }
            };
            JobStore first = store(failing); Job job = create(first, 0, "Before save");
            Path directory = first.directory(job.id()); failing.initialize(job, directory, false);
            assertThrows(IOException.class, () -> failing.commit(job, directory, "After save", -1, first::save));
            boolean committed = stage == SourceVersionStore.Stage.METADATA_COMMITTED;
            int revision = committed ? 1 : 0; String expected = committed ? "After save" : "Before save";
            assertEquals(revision, first.get(job.id()).revision());
            assertEquals(expected, current(first, first.get(job.id())));
            ConversionService service = service(first);
            try { assertEquals(expected, archiveSource(service, job.id())); }
            finally { service.stop(); }
            JobStore restarted = store(new SourceVersionStore()); Job recovered = restarted.get(job.id());
            assertEquals(revision, recovered.revision());
            assertEquals(expected, Files.readString(directory.resolve("output/main.tex")));
            assertEquals("Before save", Files.readString(directory.resolve(".versions/0/main.tex")));
            if (!committed) assertFalse(Files.exists(directory.resolve(".versions/1")));
            Job retry = restarted.versions().commit(recovered, directory, "Safe retry", -1, restarted::save);
            assertEquals(revision + 1, retry.revision()); assertEquals("Safe retry", current(restarted, retry));
        }
    }

    @Test void staleRestoreAndSaveNeverOverwriteCommittedSourceOrHistory() throws Exception {
        JobStore store = store(new SourceVersionStore()); Job job = create(store, 0, "Initial");
        store.versions().initialize(job, store.directory(job.id()), false);
        ConversionService service = service(store);
        try {
            service.saveSource(job.id(), "Other tab saved", 0);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.restore(job.id(), 0, 0)).status);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.saveSource(job.id(), "Stale tab", 0)).status);
            assertEquals("Other tab saved", service.source(job.id()));
            assertEquals(2, service.versions(job.id()).items().size());
            store.save(store.get(job.id()).withPreview("QUEUED", ""));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.restore(job.id(), 0, 1)).status);
            store.save(store.get(job.id()).withPreview("COMPILING", ""));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.restore(job.id(), 0, 1)).status);
        } finally { service.stop(); }
    }

    @Test void versionCountLimitPreservesAllProtectedSnapshots() throws Exception {
        JobStore store = store(new SourceVersionStore(3, 1000)); Job job = create(store, 0, "Original");
        var versions = store.versions(); Path directory = store.directory(job.id()); versions.initialize(job, directory, false);
        Job first = versions.commit(job, directory, "One", -1, store::save);
        Job second = versions.commit(first, directory, "Two", -1, store::save);
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, assertThrows(ApiException.class,
                () -> versions.commit(second, directory, "Three", 0, store::save)).status);
        assertEquals(3, versions.list(second, directory).items().size());
        assertEquals("Original", new String(versions.readVersion(second, directory, 0), StandardCharsets.UTF_8));
        assertEquals("Two", current(store, second)); assertFalse(Files.exists(directory.resolve(".versions/3")));
    }

    @Test void byteLimitAndOversizeSourceFailBeforePublishingNewHistory() throws Exception {
        JobStore store = store(new SourceVersionStore(100, 10)); Job job = create(store, 0, "12345");
        var versions = store.versions(); Path directory = store.directory(job.id()); versions.initialize(job, directory, false);
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, assertThrows(ApiException.class,
                () -> versions.commit(job, directory, "123456", -1, store::save)).status);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> versions.commit(job, directory, "x".repeat(SourceVersionStore.MAX_SOURCE + 1), -1, store::save)).status);
        assertEquals(1, versions.list(job, directory).items().size()); assertEquals("12345", current(store, job));
    }

    @Test void cacheRepairUsesCommittedSnapshotAndDetectsCorruptHistory() throws Exception {
        JobStore store = store(new SourceVersionStore()); Job job = create(store, 0, "Trusted original");
        Path directory = store.directory(job.id()); var versions = store.versions(); versions.initialize(job, directory, false);
        Files.writeString(directory.resolve("output/main.tex"), "x".repeat(SourceVersionStore.MAX_SOURCE + 1));
        versions.recover(job, directory);
        assertEquals("Trusted original", Files.readString(directory.resolve("output/main.tex")));
        Files.createDirectories(directory.resolve("preview-0")); Files.writeString(directory.resolve("preview-0/main.tex"), "Do not infer history");
        assertEquals(1, versions.list(job, directory).items().size());
        Files.writeString(directory.resolve(".versions/0/main.tex"), "Corrupted source");
        assertThrows(IOException.class, () -> versions.current(job, directory));
        assertEquals("Trusted original", Files.readString(directory.resolve("output/main.tex")));
    }

    @Test void partialStagingAndUncommittedSnapshotsAreRemovedWithoutTouchingCommittedHistory() throws Exception {
        JobStore store = store(new SourceVersionStore()); Job job = create(store, 0, "Committed source");
        Path directory = store.directory(job.id()); store.versions().initialize(job, directory, false);
        Path pending = directory.resolve(".versions/.pending-" + UUID.randomUUID());
        Files.createDirectories(pending); Files.writeString(pending.resolve("main.tex"), "Interrupted staged bytes");
        Path future = directory.resolve(".versions/1"); Files.createDirectories(future);
        Files.writeString(future.resolve("main.tex"), "Uncommitted source");
        JobStore restarted = store(new SourceVersionStore());
        assertFalse(Files.exists(pending)); assertFalse(Files.exists(future));
        assertEquals("Committed source", current(restarted, restarted.get(job.id())));
        assertEquals("Committed source", Files.readString(directory.resolve(".versions/0/main.tex")));
    }

    @Test void uncommittedCleanupRejectsLinkedDescendantsAndPreservesOutsideFiles() throws Exception {
        JobStore store = store(new SourceVersionStore()); Job job = create(store, 0, "Original");
        Path directory = store.directory(job.id()); store.versions().initialize(job, directory, false);
        Path pending = directory.resolve(".versions/.pending-" + UUID.randomUUID()); Files.createDirectories(pending);
        Path outside = temporary.resolve("outside"); Files.createDirectories(outside); Files.writeString(outside.resolve("keep.txt"), "Keep");
        Path link = pending.resolve("linked");
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } else Files.createSymbolicLink(link, outside);
        try {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> store.versions().recover(job, directory)).status);
            assertEquals("Keep", Files.readString(outside.resolve("keep.txt")));
        } finally { Files.delete(link); }
    }

    @Test void corruptHistoryStaysListedAndAllowsOriginalDownloadAndDeletionAfterRestart() throws Exception {
        JobStore first = store(new SourceVersionStore()); Job job = create(first, 0, "Trusted source");
        Path directory = first.directory(job.id()); first.versions().initialize(job, directory, false);
        byte[] original = Files.readAllBytes(directory.resolve("input.docx"));
        Files.writeString(directory.resolve(".versions/0/main.tex"), "Damaged snapshot");
        JobStore restarted = store(new SourceVersionStore());
        assertEquals(job, restarted.get(job.id()));
        assertTrue(restarted.list().stream().anyMatch(item -> item.id().equals(job.id())));
        assertEquals(1, restarted.library("history", "", "newest", 0, 20).total());
        ConversionService service = service(restarted);
        try {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.source(job.id())).status);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.versions(job.id())).status);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.saveSource(job.id(), "Unsafe overwrite", 0)).status);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.restore(job.id(), 0, 0)).status);
            ConversionController controller = new ConversionController(service, restarted, new Tools(properties()));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> controller.download(job.id())).status);
            var response = controller.original(job.id());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertNotNull(response.getBody()); response.getBody().writeTo(bytes);
            assertArrayEquals(original, bytes.toByteArray());
            assertEquals("Damaged snapshot", Files.readString(directory.resolve(".versions/0/main.tex")));
            assertEquals("Trusted source", Files.readString(directory.resolve("output/main.tex")));
            assertEquals(HttpStatus.NO_CONTENT, controller.delete(job.id()).getStatusCode());
            assertFalse(Files.exists(directory));
        } finally { service.stop(); }
    }
}
