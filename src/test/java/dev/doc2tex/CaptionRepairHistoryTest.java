package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class CaptionRepairHistoryTest {
    @TempDir Path directory;
    private static final String SOURCE = "\\begin{document}\nOrdinary prose: Figure 1 is unchanged.\n\\begin{figure}\n\\caption{Figure 1 Original observations}\n\\end{figure}\n\\end{document}\n";

    @Test void previewsAreReadOnlyAcceptedRepairsUseVersionedSavesAndStalePlansCannotOverwrite() throws Exception {
        var properties = new AppProperties(directory.toString(), "", "", 1, 2, 10, 10);
        var store = new JobStore(properties); store.load();
        Job job = new Job(UUID.randomUUID().toString(), "caption.docx", "article", "COMPLETED", "NOT_REQUESTED", "", "", Instant.now(), Instant.now(), 100, 0);
        store.save(job); Path project = store.directory(job.id()); Files.createDirectories(project.resolve("output"));
        Files.write(project.resolve("input.docx"), SampleDocument.create()); Files.writeString(project.resolve("output/main.tex"), SOURCE);
        store.versions().initialize(job, project, false);
        var service = new ConversionService(store, new Tools(properties), new ToolRunner(), new DocxValidator(), properties, new DocumentInspector(), new FontSupport());
        try {
            var plan = service.captionRepairs(job.id()); assertEquals(1, plan.proposals().size());
            assertEquals(SOURCE, service.source(job.id())); assertEquals(0, store.get(job.id()).revision()); assertEquals(1, service.versions(job.id()).items().size());
            assertEquals(plan, service.captionRepairs(job.id()), "Cancel/re-preview must not write source or create versions");
            var repaired = service.applyCaptionRepairs(job.id(), 0, plan.sourceHash(), List.of(plan.proposals().getFirst().id()));
            assertEquals(1, repaired.job().revision()); assertTrue(repaired.source().contains("\\caption{Original observations}"));
            assertTrue(repaired.source().contains("Ordinary prose: Figure 1 is unchanged.")); assertEquals(SOURCE, service.versionSource(job.id(), 0));
            assertEquals(2, service.versions(job.id()).items().size());
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.applyCaptionRepairs(job.id(), 0, plan.sourceHash(), List.of(plan.proposals().getFirst().id()))).status);
            assertEquals(repaired.source(), service.source(job.id()));
            Job restored = service.restore(job.id(), 0, 1); assertEquals(2, restored.revision()); assertEquals(SOURCE, service.source(job.id()));
            var current = service.captionRepairs(job.id());
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.applyCaptionRepairs(job.id(), 2, "0".repeat(64), List.of(current.proposals().getFirst().id()))).status);
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.applyCaptionRepairs(job.id(), 2, current.sourceHash(), List.of())).status);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.applyCaptionRepairs(job.id(), 2, current.sourceHash(), List.of("unknown"))).status);
            store.save(restored.withPreview("QUEUED", "Waiting"));
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.applyCaptionRepairs(job.id(), 2, current.sourceHash(), List.of(current.proposals().getFirst().id()))).status);
            assertEquals(SOURCE, service.source(job.id())); assertEquals(2, store.get(job.id()).revision());
        } finally { service.stop(); }
    }
}
