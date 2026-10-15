package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreTest {
    @TempDir Path directory;

    private JobStore store() {
        return new JobStore(new AppProperties(directory.toString(), "", "", 1, 1, 10, 10));
    }

    private Job job(String status, String preview) {
        return new Job(UUID.randomUUID().toString(), "résumé.docx", "article", status, preview, "", "",
                Instant.now(), Instant.now(), 1024, 0);
    }

    @Test void completedSourceAndUnicodeMetadataSurviveRestart() throws Exception {
        JobStore first = store(); first.load();
        Job job = job("COMPLETED", "READY"); first.save(job);
        JobStore restored = store(); restored.load();
        assertEquals(job, restored.get(job.id()));
    }

    @Test void interruptedConversionsBecomeActionableFailures() throws Exception {
        JobStore first = store(); first.load();
        Job queued = job("QUEUED", "NOT_REQUESTED"); first.save(queued);
        Job compiling = job("COMPLETED", "COMPILING"); first.save(compiling);
        JobStore restored = store(); restored.load();
        assertEquals("FAILED", restored.get(queued.id()).status());
        assertEquals("COMPLETED", restored.get(compiling.id()).status());
        assertEquals("FAILED", restored.get(compiling.id()).previewStatus());
    }

    @Test void pathsMustBeJobIdentifiers() {
        assertThrows(ApiException.class, () -> store().directory("../../etc"));
    }

    @Test void librarySearchesAll65JobsAfterRestartAndPaginatesInStableOrder() throws Exception {
        JobStore first = store(); first.load();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        List<Job> expected = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            Job job = new Job(new UUID(0, i + 1).toString(), i == 0 ? "OLDER résumé.docx" : "draft-" + i + ".docx",
                    i % 2 == 0 ? "article" : "report", i % 2 == 0 ? "COMPLETED" : "FAILED", "NOT_REQUESTED",
                    "", "", created.plusSeconds(i / 2), created, 1024, 0);
            first.save(job); expected.add(job);
        }
        JobStore restored = store(); restored.load();
        assertEquals(30, restored.list().size());
        assertFalse(restored.list().stream().anyMatch(job -> job.id().equals(expected.getFirst().id())));
        assertEquals(expected.getFirst(), restored.get(expected.getFirst().id()));
        assertEquals(List.of(expected.getFirst()), restored.library(" older RÉSUMÉ ", "COMPLETED", "newest", 0, 20).items());
        assertEquals(0, restored.library("résumé", "FAILED", "newest", 0, 20).total());
        List<Job> actual = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            var result = restored.library("", "", "newest", page, 20);
            assertEquals(65, result.total()); assertEquals(page, result.page()); assertEquals(20, result.size());
            actual.addAll(result.items());
        }
        assertEquals(expected.stream().sorted(Comparator.comparing(Job::createdAt).reversed().thenComparing(Job::id)).toList(), actual);
        assertEquals(expected, restored.library("", "", "oldest", 0, 100).items());
        assertEquals(33, restored.library("", "COMPLETED", "newest", 0, 100).total());
        assertTrue(restored.library("missing", "", "newest", 0, 20).items().isEmpty());
        assertTrue(restored.library("", "", "newest", Integer.MAX_VALUE, 100).items().isEmpty());
    }

    @Test void libraryRejectsInvalidParameters() {
        JobStore store = store();
        assertThrows(ApiException.class, () -> store.library("x".repeat(201), "", "newest", 0, 20));
        assertThrows(ApiException.class, () -> store.library("", "READY", "newest", 0, 20));
        assertThrows(ApiException.class, () -> store.library("", "", "random", 0, 20));
        assertThrows(ApiException.class, () -> store.library("", "", "newest", -1, 20));
        assertThrows(ApiException.class, () -> store.library("", "", "newest", 0, 0));
        assertThrows(ApiException.class, () -> store.library("", "", "newest", 0, 101));
    }
}
