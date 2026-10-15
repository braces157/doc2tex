package dev.doc2tex;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class JobStore {
    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private final Path root;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    JobStore(AppProperties properties) { root = Path.of(properties.dataDir()).toAbsolutePath().normalize(); }

    @PostConstruct
    void load() throws IOException {
        Files.createDirectories(root);
        try (var paths = Files.list(root)) {
            for (Path directory : paths.filter(Files::isDirectory).toList()) {
                Path metadata = directory.resolve("job.properties");
                if (!Files.isRegularFile(metadata)) continue;
                try (InputStream input = Files.newInputStream(metadata)) {
                    Properties p = new Properties();
                    p.load(input);
                    String id = UUID.fromString(p.getProperty("id")).toString();
                    if (!directory.getFileName().toString().equals(id)) continue;
                    Job job = new Job(id, p.getProperty("filename"), p.getProperty("template"),
                            p.getProperty("status"), p.getProperty("previewStatus"), p.getProperty("message", ""),
                            p.getProperty("previewMessage", ""), Instant.parse(p.getProperty("createdAt")),
                            Instant.parse(p.getProperty("updatedAt")), Long.parseLong(p.getProperty("size")),
                            Integer.parseInt(p.getProperty("revision", "0")));
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
    }

    Job get(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ApiException(HttpStatus.NOT_FOUND, "This conversion could not be found.");
        return job;
    }

    List<Job> list() {
        return jobs.values().stream().sorted(Comparator.comparing(Job::createdAt).reversed()).limit(30).toList();
    }

    Path directory(String id) {
        String canonical;
        try { canonical = UUID.fromString(id).toString(); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.NOT_FOUND, "Unknown conversion."); }
        Path path = root.resolve(canonical).normalize();
        if (!path.getParent().equals(root)) throw new IllegalArgumentException("Invalid job path");
        return path;
    }

    synchronized void save(Job job) throws IOException {
        Path directory = directory(job.id());
        Files.createDirectories(directory);
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
        Path temporary = directory.resolve("job.properties.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) { p.store(output, "doc2TeX job"); }
        Files.move(temporary, directory.resolve("job.properties"), StandardCopyOption.REPLACE_EXISTING);
        jobs.put(job.id(), job);
    }
}
