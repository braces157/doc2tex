package dev.doc2tex;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Expires old demo jobs and enforces total workspace capacity when public demo mode is enabled. */
@Component
class DemoCleanupService {
    private static final Logger log = LoggerFactory.getLogger(DemoCleanupService.class);
    private final JobStore store;
    private final HostingProperties hosting;
    private ScheduledExecutorService scheduler;

    DemoCleanupService(JobStore store, HostingProperties hosting) {
        this.store = store;
        this.hosting = hosting;
    }

    @PostConstruct
    void start() {
        if (!hosting.demoMode()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "doc2tex-demo-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sweepSafely, 5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void sweepSafely() {
        try {
            sweepExpired();
        } catch (RuntimeException exception) {
            log.warn("Demo cleanup sweep failed: {}", exception.getMessage());
        }
    }

    int sweepExpired() {
        return sweepExpired(Instant.now());
    }

    synchronized int sweepExpired(Instant now) {
        Instant cutoff = now.minus(Duration.ofMinutes(hosting.retentionMinutes()));
        int removed = 0;
        for (Job job : store.snapshotAll()) {
            if (!isBusy(job) && job.updatedAt().isBefore(cutoff)) {
                if (deleteQuietly(job.id())) removed++;
            }
        }
        return removed;
    }

    synchronized boolean makeRoomForUpload() {
        sweepExpired(Instant.now());
        if (store.totalCount() < hosting.maxJobs()) return true;
        List<Job> candidates = store.snapshotAll().stream()
                .filter(job -> !isBusy(job))
                .sorted(Comparator.comparing(Job::updatedAt).thenComparing(Job::id))
                .toList();
        for (Job candidate : candidates) {
            if (store.totalCount() < hosting.maxJobs()) break;
            deleteQuietly(candidate.id());
        }
        return store.totalCount() < hosting.maxJobs();
    }

    private boolean deleteQuietly(String id) {
        try {
            store.delete(id);
            return true;
        } catch (ApiException exception) {
            return false;
        } catch (IOException exception) {
            log.warn("Could not delete expired demo job {}: {}", id, exception.getMessage());
            return false;
        }
    }

    private static boolean isBusy(Job job) {
        return List.of("QUEUED", "CONVERTING").contains(job.status())
                || List.of("QUEUED", "COMPILING").contains(job.previewStatus());
    }
}
