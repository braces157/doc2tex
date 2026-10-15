package dev.doc2tex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** User decisions are separate from immutable detected risks and source-version storage. */
final class ReviewDecisions {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int LIMIT = 200;
    record Stored(String findingId, String status, int revision, String fingerprint, Instant updatedAt) {}
    record Decision(String findingId, String status, int revision, String fingerprint, Instant updatedAt,
                    boolean applicable, boolean carried, int applicableRevision, String reason) {}
    private record State(int schema, List<Stored> decisions) {}

    static Map<String, Stored> load(Path directory) throws IOException {
        Path file = directory.resolve("review-decisions.json"); JobStore.checkUnlinked(file);
        var result = new LinkedHashMap<String, Stored>();
        if (!Files.exists(file)) return result;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("Review decision metadata is too large.");
            State state = JSON.readValue(bytes, State.class);
            if (state == null || state.schema() != 1 || state.decisions() == null || state.decisions().size() > LIMIT) throw new IOException("Review decision metadata is invalid.");
            for (var entry : state.decisions()) {
                if (entry == null || !hex(entry.findingId()) || !hex(entry.fingerprint()) || entry.revision() < 0 || entry.updatedAt() == null
                        || entry.status() == null || !List.of("reviewed", "needs-work").contains(entry.status()) || result.putIfAbsent(entry.findingId(), entry) != null)
                    throw new IOException("Review decision metadata is invalid.");
            }
        } catch (RuntimeException exception) { throw new IOException("Review decision metadata could not be read.", exception); }
        return result;
    }

    static void save(Path directory, Map<String, Stored> decisions) throws IOException {
        if (decisions.size() > LIMIT) throw new IOException("Review decision storage limit reached.");
        Path file = directory.resolve("review-decisions.json"), temporary = directory.resolve("review-decisions.json.tmp");
        JobStore.checkUnlinked(file); JobStore.checkUnlinked(temporary);
        byte[] bytes = JSON.writeValueAsBytes(new State(1, List.copyOf(decisions.values())));
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        SourceVersionStore.forceDirectory(directory);
    }

    static List<Decision> apply(Map<String, Stored> stored, List<ReviewEvidence.Item> items, int revision) {
        Map<String, ReviewEvidence.Item> current = new LinkedHashMap<>(); items.forEach(item -> current.put(item.id(), item));
        return stored.values().stream().map(entry -> {
            var item = current.get(entry.findingId());
            boolean matches = item != null && entry.fingerprint().equals(item.fingerprint());
            boolean applicable = matches && entry.revision() <= revision && (entry.revision() == revision || item.mapping().status().equals("matched"));
            String reason = applicable ? entry.revision() == revision ? "Reviewed against this revision." : "Carried because the original finding and mapped source region are unchanged."
                    : item == null ? "The original finding is unavailable; review again." : "Relevant source evidence changed or cannot be mapped; review again.";
            return new Decision(entry.findingId(), entry.status(), entry.revision(), entry.fingerprint(), entry.updatedAt(), applicable,
                    applicable && entry.revision() != revision, revision, reason);
        }).toList();
    }
    static boolean hex(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
