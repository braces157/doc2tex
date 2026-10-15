package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
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
}
