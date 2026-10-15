package dev.doc2tex;

import java.time.Instant;

/** Immutable snapshots keep HTTP reads consistent while workers update a job. */
public record Job(String id, String filename, String template, String status,
                  String previewStatus, String message, String previewMessage,
                  Instant createdAt, Instant updatedAt, long size, int revision) {
    Job withStatus(String next, String detail) {
        return new Job(id, filename, template, next, previewStatus, detail, previewMessage,
                createdAt, Instant.now(), size, revision);
    }

    Job withPreview(String next, String detail) {
        return new Job(id, filename, template, status, next, message, detail,
                createdAt, Instant.now(), size, revision);
    }

    Job edited() {
        return new Job(id, filename, template, status, "NOT_REQUESTED", message, "",
                createdAt, Instant.now(), size, revision + 1);
    }
}
