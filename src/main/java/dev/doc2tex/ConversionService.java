package dev.doc2tex;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
class ConversionService {
    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);
    private static final int MAX_SOURCE = 2 * 1024 * 1024;
    private final JobStore store;
    private final Tools tools;
    private final ToolRunner runner;
    private final DocxValidator validator;
    private final AppProperties properties;
    private final DocumentInspector inspector;
    private final FontSupport fonts;
    private final AiLayoutService aiLayout;
    private final ThreadPoolExecutor executor;

    ConversionService(JobStore store, Tools tools, ToolRunner runner, DocxValidator validator, AppProperties properties, DocumentInspector inspector, FontSupport fonts) {
        this(store, tools, runner, validator, properties, inspector, fonts, new AiLayoutService(AiLayoutSettings.disabled(), runner));
    }

    @org.springframework.beans.factory.annotation.Autowired
    ConversionService(JobStore store, Tools tools, ToolRunner runner, DocxValidator validator, AppProperties properties, DocumentInspector inspector,
            FontSupport fonts, AiLayoutService aiLayout) {
        this.store = store;
        this.tools = tools;
        this.runner = runner;
        this.validator = validator;
        this.properties = properties;
        this.inspector = inspector;
        this.fonts = fonts;
        this.aiLayout = aiLayout;
        executor = new ThreadPoolExecutor(properties.workers(), properties.workers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofPlatform().name("conversion-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    AiLayoutService.Status aiLayoutStatus() { return aiLayout.status(); }

    Job submit(MultipartFile file, String template) throws IOException {
        return submit(file, template, LayoutOptions.uploadDefaults());
    }

    Job submit(MultipartFile file, String template, LayoutOptions layout) throws IOException {
        return submit(file.getOriginalFilename(), file.getSize(), template, layout, "", file::transferTo);
    }

    Job deriveLayout(String id, int revision, String template, LayoutOptions layout) throws IOException {
        try (var operation = store.read(id)) {
            Job parent = ready(id);
            if (parent.revision() != revision) throw new ApiException(HttpStatus.CONFLICT, "The source changed. Reopen this document before creating a layout copy.");
            Path original = original(id);
            String name = parent.filename(); if (name.length() > 180) name = name.substring(0, 180);
            return submit(parent.filename(), Files.size(original), template, layout, name + " · layout copy", path -> Files.copy(original, path));
        }
    }

    @FunctionalInterface private interface InputWriter { void write(Path path) throws IOException; }
    private Job submit(String originalFilename, long size, String template, LayoutOptions layout, String displayName, InputWriter writer) throws IOException {
        if (!List.of("article", "report").contains(template)) throw bad("Choose the article or report template.");
        if (layout == null) throw bad("Choose valid layout settings.");
        String filename = cleanFilename(originalFilename);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".docx")) throw bad("Choose a Word .docx file.");
        if (size <= 0 || size > 20L * 1024 * 1024) throw bad("Choose a DOCX file between 1 byte and 20 MB.");
        if (!tools.available(tools.pandoc())) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Pandoc is unavailable. Run scripts/setup-tools.ps1, then restart the app.");
        String id = UUID.randomUUID().toString();
        Path directory = store.directory(id);
        Files.createDirectories(directory);
        Path input = directory.resolve("input.docx");
        writer.write(input);
        try { validator.validate(input); }
        catch (RuntimeException exception) {
            Files.deleteIfExists(input);
            Files.deleteIfExists(directory);
            throw exception;
        }
        Instant now = Instant.now();
        Job job = new Job(id, filename, template, "QUEUED", "NOT_REQUESTED", "Waiting for a conversion worker.", "",
                now, now, size, 0, displayName, false, layout);
        store.save(job);
        try { executor.execute(() -> convert(id)); }
        catch (RejectedExecutionException exception) {
            store.save(job.withStatus("FAILED", "All conversion workers are busy. Try uploading again shortly."));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The conversion queue is full. Try again shortly.");
        }
        return job;
    }

    private void convert(String id) {
        try (var operation = store.worker(id)) { convertLocked(id); }
    }

    private void convertLocked(String id) {
        try {
            Job job = store.get(id);
            store.save(job.withStatus("CONVERTING", "Reading document structure and writing LaTeX."));
            Path directory = store.directory(id);
            Path output = directory.resolve("output");
            Files.createDirectories(output);
            Path conversionInput = WordReferenceSupport.prepare(directory.resolve("input.docx"), directory, output);
            List<String> command = new ArrayList<>(List.of(tools.pandoc(), "--sandbox", "--from=docx", "--to=latex",
                    "--standalone", "--wrap=none", "--track-changes=accept", "--extract-media=media", "--metadata=date:",
                    "--variable=documentclass:" + job.template(),
                    "--output=main.tex", conversionInput.toString()));
            SourceLayout sourceLayout = job.layout().preserveSourceLayout() ? SourceLayout.inspect(directory.resolve("input.docx")) : null;
            if (sourceLayout != null) sourceLayout.save(output);
            job.layout().prepare(output, command, sourceLayout);
            WordReferenceSupport.configure(command);
            AiLayoutReport aiReport = aiLayout.prepare(directory.resolve("input.docx"), directory, output, job.layout(), command);
            fonts.prepare(inspector.review(directory.resolve("input.docx"), null), output, command, job.layout().metadataText());
            ToolRunner.Result result = runner.run(command, output, directory.resolve("conversion.log"),
                    properties.conversionTimeoutSeconds());
            if (result.exitCode() != 0 || !Files.isRegularFile(output.resolve("main.tex"))) {
                throw new IOException("Pandoc could not convert this document. It may be damaged or contain unsupported content.");
            }
            if (Files.size(output.resolve("main.tex")) > MAX_SOURCE) throw new IOException("The generated LaTeX exceeds the 2 MB editor limit.");
            try { aiReport = aiLayout.finish(output, aiReport); }
            catch (IOException | RuntimeException exception) { log.warn("Cannot read the applied AI layout decisions for {}", id); }
            String warnings = result.log().isBlank() ? "" : "\nPandoc messages:\n" + result.log();
            String review = inspector.review(directory.resolve("input.docx"), null).findings().stream()
                    .map(finding -> "\n[" + finding.severity() + "] " + finding.title() + ": " + finding.detail())
                    .collect(java.util.stream.Collectors.joining());
            String pageReview = sourceLayout == null ? "" : sourceLayout.findings().stream()
                    .map(finding -> "\n[" + finding.severity() + "] " + finding.title() + ": " + finding.detail())
                    .collect(java.util.stream.Collectors.joining());
            String aiReview = aiReport == null ? "" : aiReport.findings().stream()
                    .map(finding -> "\n[" + finding.severity() + "] " + finding.title() + ": " + finding.detail())
                    .collect(java.util.stream.Collectors.joining());
            Files.writeString(output.resolve("README.txt"), "Converted with doc2TeX and Pandoc.\n\n"
                    + "main.tex is the editable source. Keep the media folder beside it.\n"
                    + "Compile with Tectonic (tectonic main.tex) or current XeLaTeX (xelatex main.tex), or upload this ZIP to Overleaf.\n"
                    + "If a fonts folder is included, select XeLaTeX as the compiler in Overleaf or your TeX editor.\n"
                    + "Initial conversion layout: " + job.layout().description() + ". See layout-settings.json for structured settings.\n"
                    + "Settings describe conversion; later manual source edits take precedence.\n"
                    + "Review equations, references and complex tables against the original.\n" + aiReview + pageReview + review + warnings);
            store.versions().initialize(store.get(id), directory, false);
            store.save(store.get(id).withStatus("COMPLETED", result.log().isBlank()
                    ? "LaTeX and extracted images are ready." : "LaTeX is ready. See README.txt in the ZIP for conversion messages."));
        } catch (Exception exception) {
            fail(id, false, exception);
        }
    }

    Job saveSource(String id, String source, int revision) throws IOException {
        try (var operation = store.edit(id)) { return saveSourceLocked(id, source, revision); }
    }

    private Job saveSourceLocked(String id, String source, int revision) throws IOException {
        return saveSourceLocked(id, source, revision, -1);
    }

    private Job saveSourceLocked(String id, String source, int revision, int restoredFrom) throws IOException {
        Job job = ready(id);
        if (job.revision() != revision) throw new ApiException(HttpStatus.CONFLICT, "The source changed in another window. Reopen this conversion before saving.");
        if (List.of("QUEUED", "COMPILING").contains(job.previewStatus())) throw new ApiException(HttpStatus.CONFLICT,
                "Wait for PDF compilation to finish before saving changes.");
        if (source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE)
            throw bad("The LaTeX source must contain text and be smaller than 2 MB.");
        return store.versions().commit(job, store.directory(id), source, restoredFrom, store::save);
    }

    SourceVersionStore.History versions(String id) throws IOException {
        try (var operation = store.edit(id)) {
            Job job = ready(id);
            store.versions().recover(job, store.directory(id));
            return store.versions().list(job, store.directory(id));
        }
    }

    String versionSource(String id, int revision) throws IOException {
        try (var operation = store.read(id)) {
            return new String(store.versions().readVersion(ready(id), store.directory(id), revision), StandardCharsets.UTF_8);
        }
    }

    Job restore(String id, int version, int expectedRevision) throws IOException {
        return restoreDocument(id, version, expectedRevision).job();
    }

    record RestoredDocument(Job job, String source) {}

    RestoredDocument restoreDocument(String id, int version, int expectedRevision) throws IOException {
        try (var operation = store.edit(id)) {
            Job job = ready(id);
            if (job.revision() != expectedRevision) throw new ApiException(HttpStatus.CONFLICT,
                    "The source changed in another window. Reopen this conversion before restoring a version.");
            if (List.of("QUEUED", "COMPILING").contains(job.previewStatus())) throw new ApiException(HttpStatus.CONFLICT,
                    "Wait for PDF compilation to finish before restoring a saved version.");
            store.versions().recover(job, store.directory(id));
            String source = new String(store.versions().readVersion(job, store.directory(id), version), StandardCharsets.UTF_8);
            return new RestoredDocument(saveSourceLocked(id, source, expectedRevision, version), source);
        }
    }

    Job preview(String id) throws IOException {
        try (var operation = store.edit(id)) { return previewLocked(id); }
    }

    private Job previewLocked(String id) throws IOException {
        Job job = ready(id);
        if (List.of("QUEUED", "COMPILING", "READY").contains(job.previewStatus())) return job;
        if (!tools.available(tools.tectonic())) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PDF preview needs Tectonic. Close the app and run Install tools.cmd in the Windows package, or scripts/setup-tools.ps1 in a development checkout, then relaunch. Source and ZIP export remain available.");
        Path diagnostics = store.directory(id).resolve("compiler-diagnostics.json");
        JobStore.checkUnlinked(diagnostics);
        Files.deleteIfExists(diagnostics);
        store.versions().recover(job, store.directory(id));
        Job queued = job.withPreview("QUEUED", "Waiting for the PDF compiler.");
        store.save(queued);
        try { executor.execute(() -> compile(id)); }
        catch (RejectedExecutionException exception) {
            store.save(job.withPreview("FAILED", "The worker queue is full. Try building again shortly."));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The worker queue is full. Try again shortly.");
        }
        return queued;
    }

    private void compile(String id) {
        try (var operation = store.worker(id)) { compileLocked(id); }
    }

    private void compileLocked(String id) {
        Job job = store.get(id);
        Path directory = store.directory(id);
        boolean logStarted = false;
        try {
            Path compilerLog = directory.resolve("preview.log");
            JobStore.checkUnlinked(compilerLog);
            Files.writeString(compilerLog, "", StandardCharsets.UTF_8);
            logStarted = true;
            store.save(job.withPreview("COMPILING", "Building the PDF. The first build may download TeX packages."));
            Path output = directory.resolve("output");
            Path preview = directory.resolve("preview-" + job.revision());
            Files.createDirectories(preview);
            ToolRunner.Result result = runner.run(List.of(tools.tectonic(), "--untrusted", "--color", "never", "--outdir", preview.toString(),
                    "main.tex"), output, directory.resolve("preview.log"), properties.previewTimeoutSeconds());
            if (result.exitCode() != 0 || !Files.isRegularFile(preview.resolve("main.pdf")))
                throw new IOException(PreviewFailure.message(result.log()));
            var diagnostics = CompilerDiagnostics.fromLog(compilerLog, job.revision(), "READY", null);
            CompilerDiagnostics.save(directory, diagnostics);
            boolean warnings = !diagnostics.items().isEmpty() || diagnostics.logTruncated();
            store.save(store.get(id).withPreview("READY", warnings
                    ? "PDF built with warnings. Check compiler diagnostics before using it." : "PDF preview is ready."));
        } catch (Exception exception) {
            if (logStarted) try {
                CompilerDiagnostics.save(directory, CompilerDiagnostics.fromLog(directory.resolve("preview.log"), job.revision(), "FAILED", exception.getMessage()));
            } catch (IOException | RuntimeException reportError) { log.warn("Could not retain compiler diagnostics for {}", id, reportError); }
            fail(id, true, exception);
        }
    }

    CompilerDiagnostics.Report diagnostics(String id) throws IOException {
        try (var operation = store.read(id)) {
            Job job = ready(id);
            if (List.of("QUEUED", "COMPILING").contains(job.previewStatus()))
                return new CompilerDiagnostics.Report(job.revision(), job.previewStatus(), false, false, List.of(), "");
            Path directory = store.directory(id);
            try {
                var report = CompilerDiagnostics.load(directory);
                if (report != null) {
                    if (report.revision() > job.revision()) throw new IOException("Compiler diagnostics refer to a future revision.");
                    if (Integer.valueOf(job.revision()).equals(report.revision()) && job.previewStatus().equals("FAILED") && !report.status().equals("FAILED"))
                        return CompilerDiagnostics.parse(report.rawLog(), report.revision(), "FAILED", report.logTruncated(), job.previewMessage());
                    return report;
                }
            } catch (IOException | RuntimeException exception) { log.warn("Cannot read diagnostics snapshot for {}", id, exception); }
            // Legacy or damaged metadata cannot establish which source revision produced this log.
            return CompilerDiagnostics.fromLog(directory.resolve("preview.log"), null, job.previewStatus(), job.previewMessage());
        }
    }

    String source(String id) throws IOException {
        try (var operation = store.read(id)) {
            return new String(store.versions().current(ready(id), store.directory(id)), StandardCharsets.UTF_8);
        }
    }

    DocumentInspector.Review review(String id) {
        try (var operation = store.read(id)) { return reviewLocked(id); }
    }

    private DocumentInspector.Review reviewLocked(String id) {
        Job job = store.get(id);
        Path directory = store.directory(id);
        Path log = List.of("READY", "FAILED").contains(job.previewStatus()) ? directory.resolve("preview.log") : null;
        var detected = inspector.review(directory.resolve("input.docx"), log);
        var findings = new ArrayList<>(detected.findings());
        if (job.layout().aiLayout() && job.status().equals("COMPLETED")) {
            try { findings.addAll(AiLayoutReport.read(directory.resolve("output")).findings()); }
            catch (IOException | RuntimeException exception) {
                findings.add(new DocumentInspector.Finding("ai-layout-review", "warning", "AI layout report unavailable",
                        "The initial AI layout decisions could not be verified. Original text and saved source remain available."));
            }
        }
        if (job.layout().preserveSourceLayout() && job.status().equals("COMPLETED")) {
            try { findings.addAll(SourceLayout.read(directory.resolve("output")).findings()); }
            catch (IOException | RuntimeException exception) {
                findings.add(new DocumentInspector.Finding("source-page-layout-review", "warning", "Page settings could not be verified",
                        "The saved page-layout report is unavailable. Compare the page size and margins with the original."));
            }
        }
        var scripts = new java.util.LinkedHashSet<>(detected.scripts());
        var metadataScripts = new java.util.LinkedHashSet<String>(); DocumentInspector.detectScripts(job.layout().metadataText(), metadataScripts);
        metadataScripts.removeAll(scripts); scripts.addAll(metadataScripts);
        if (!metadataScripts.isEmpty()) findings.add(new DocumentInspector.Finding("metadata-fonts", "warning", "Multilingual layout metadata", "Review title, author and date fonts for " + String.join(", ", metadataScripts) + ". Bundled fonts cover supported scripts; unusual scripts may require manual font settings."));
        var referenceWarnings = WordReferenceSupport.warnings(directory.resolve("input.docx"), directory.resolve("output"));
        if (!referenceWarnings.isEmpty()) findings.add(new DocumentInspector.Finding("references", "warning", "References need manual review", String.join(" ", referenceWarnings.stream().limit(8).toList())));
        String source = null;
        if (job.status().equals("COMPLETED")) try {
            store.requireHistory(id);
            source = new String(store.versions().current(job, directory), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) { /* Original text remains useful when saved source/history is unavailable. */ }
        if (source != null) try {
            if (store.versions().bibliography(job, directory, job.revision()) != null) {
                var bibliography = bibliographyReport(job, directory, source); var notes = new ArrayList<>(bibliography.warnings());
                if (!bibliography.unknownKeys().isEmpty()) notes.add("Unknown citation keys: " + String.join(", ", bibliography.unknownKeys().stream().limit(8).toList()) + ". Import matching entries or correct source keys.");
                if (!notes.isEmpty()) findings.add(new DocumentInspector.Finding("bibliography", "warning", "Bibliography needs review", String.join(" ", notes.stream().limit(8).toList())));
            }
        } catch (IOException | RuntimeException exception) { findings.add(new DocumentInspector.Finding("bibliography", "warning", "Bibliography needs review", "Bibliography metadata could not be verified. Back up project storage before repairing its saved assets.")); }
        var evidence = ReviewEvidence.inspect(directory.resolve("input.docx"), source, job.revision());
        List<ReviewDecisions.Decision> decisions = List.of(); String decisionError = "";
        try { decisions = ReviewDecisions.apply(ReviewDecisions.load(directory), evidence.items(), job.revision()); }
        catch (IOException | RuntimeException exception) { decisionError = "Saved review decisions could not be read. Back up this project's storage before repairing review-decisions.json. Detected risks and original text remain available."; }
        return new DocumentInspector.Review(detected.elements(), findings, detected.compilerWarnings(), List.copyOf(scripts), detected.scope(),
                job.revision(), evidence.paragraphs(), evidence.items(), decisions, evidence.truncated(), decisionError);
    }

    DocumentInspector.Review decide(String id, String findingId, int revision, String status, String fingerprint) throws IOException {
        if (!ReviewDecisions.hex(findingId) || !ReviewDecisions.hex(fingerprint) || status == null || !List.of("reviewed", "needs-work", "unreviewed").contains(status))
            throw bad("Choose a valid review finding and decision.");
        try (var operation = store.edit(id)) {
            Job job = ready(id);
            if (job.revision() != revision) throw new ApiException(HttpStatus.CONFLICT, "The source changed. Reload the review before recording a decision.");
            if (List.of("QUEUED", "COMPILING").contains(job.previewStatus())) throw new ApiException(HttpStatus.CONFLICT, "Wait for the PDF build before recording a review decision.");
            store.versions().current(job, store.directory(id)); // Decisions require verified saved-source evidence, even for an unmapped original excerpt.
            var review = reviewLocked(id);
            var item = review.items().stream().filter(finding -> finding.id().equals(findingId)).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "This finding is unavailable. Reload the conversion review."));
            if (!item.fingerprint().equals(fingerprint)) throw new ApiException(HttpStatus.CONFLICT, "The finding evidence changed. Reload the review before recording a decision.");
            Path directory = store.directory(id); var stored = ReviewDecisions.load(directory);
            if (status.equals("unreviewed")) stored.remove(findingId);
            else stored.put(findingId, new ReviewDecisions.Stored(findingId, status, revision, fingerprint, Instant.now()));
            ReviewDecisions.save(directory, stored);
            return new DocumentInspector.Review(review.elements(), review.findings(), review.compilerWarnings(), review.scripts(), review.scope(), review.revision(),
                    review.originalText(), review.items(), ReviewDecisions.apply(stored, review.items(), revision), review.originalTruncated(), "");
        }
    }

    CaptionRepairs.Plan captionRepairs(String id) throws IOException {
        try (var operation = store.read(id)) {
            Job job = ready(id); Path directory = store.directory(id);
            String source = new String(store.versions().current(job, directory), StandardCharsets.UTF_8);
            return CaptionRepairs.preview(source, job.revision(), WordReferenceSupport.warnings(directory.resolve("input.docx"), directory.resolve("output")));
        }
    }

    RestoredDocument applyCaptionRepairs(String id, int revision, String sourceHash, List<String> proposalIds) throws IOException {
        try (var operation = store.edit(id)) {
            Job job = ready(id);
            if (job.revision() != revision) throw new ApiException(HttpStatus.CONFLICT, "The source changed. Preview caption repairs again before applying.");
            if (List.of("QUEUED", "COMPILING").contains(job.previewStatus())) throw new ApiException(HttpStatus.CONFLICT, "Wait for PDF compilation before repairing captions.");
            Path directory = store.directory(id);
            String source = new String(store.versions().current(job, directory), StandardCharsets.UTF_8);
            String repaired = CaptionRepairs.apply(source, revision, sourceHash, proposalIds, WordReferenceSupport.warnings(directory.resolve("input.docx"), directory.resolve("output")));
            return new RestoredDocument(saveSourceLocked(id, repaired, revision), repaired);
        }
    }

    record BibliographyReport(int revision, String filename, long bytes, String sha256, List<BibtexLibrary.Entry> entries, List<String> unknownKeys, List<String> warnings) {}

    BibliographyReport bibliography(String id) throws IOException {
        try (var operation = store.read(id)) {
            Job job = ready(id); Path directory = store.directory(id);
            return bibliographyReport(job, directory, source(id));
        }
    }

    private BibliographyReport bibliographyReport(Job job, Path directory, String source) throws IOException {
        var assets = store.versions().bibliography(job, directory, job.revision());
        var parsed = assets == null ? new BibtexLibrary.Parsed(List.of(), List.of()) : BibtexLibrary.parse(assets.bibliography());
        var keys = parsed.entries().stream().map(BibtexLibrary.Entry::key).collect(java.util.stream.Collectors.toSet());
        var inspection = LatexBibliography.inspect(source, keys); var warnings = new java.util.LinkedHashSet<>(parsed.warnings()); warnings.addAll(inspection.warnings());
        if (assets != null) {
            var scripts = new java.util.LinkedHashSet<String>(); DocumentInspector.detectScripts(new String(assets.bibliography(), StandardCharsets.UTF_8), scripts);
            scripts.removeAll(List.of("greek", "cyrillic"));
            if (!scripts.isEmpty()) warnings.add("Check bibliography fonts/reading direction for " + String.join(", ", scripts) + ". The bundled bibliography font supports Latin, Greek and Cyrillic; other scripts require manual font settings.");
            if (assets.font() == null) warnings.add("The bundled bibliography font is unavailable. Install project fonts or verify your current source fonts before building.");
        }
        return new BibliographyReport(job.revision(), assets == null ? "" : assets.filename(), assets == null ? 0 : assets.bibliography().length,
                assets == null ? "" : SourceVersionStore.digest(assets.bibliography()), parsed.entries(), inspection.unknownKeys(), warnings.stream().limit(32).toList());
    }

    RestoredDocument importBibliography(String id, int revision, MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > BibliographyAssets.MAX_BIB) throw bad("Choose a non-empty .bib file up to 1 MiB.");
        String filename = cleanFilename(file.getOriginalFilename()); if (!filename.toLowerCase(Locale.ROOT).endsWith(".bib")) throw bad("Choose a UTF-8 .bib file.");
        byte[] bytes; try (var input = file.getInputStream()) { bytes = input.readNBytes(BibliographyAssets.MAX_BIB + 1); }
        BibtexLibrary.parse(bytes);
        try (var operation = store.edit(id)) {
            Job job = ready(id); requireBibliographyMutation(job, revision); Path directory = store.directory(id);
            String source = new String(store.versions().current(job, directory), StandardCharsets.UTF_8);
            var assets = BibliographyAssets.replace(filename, bytes, store.versions().bibliography(job, directory, job.revision()));
            Job next = store.versions().commit(job, directory, source, -1, store::save, assets);
            return new RestoredDocument(next, source);
        }
    }

    private void requireBibliographyMutation(Job job, int revision) {
        if (revision < 0) throw bad("Provide the expected saved source revision.");
        if (job.revision() != revision) throw new ApiException(HttpStatus.CONFLICT, "The source or bibliography changed. Reopen this document before importing or setting up bibliography assets.");
        if (List.of("QUEUED", "COMPILING").contains(job.previewStatus())) throw new ApiException(HttpStatus.CONFLICT, "Wait for PDF compilation before changing bibliography assets.");
    }

    LatexBibliography.Plan bibliographySetup(String id) throws IOException {
        try (var operation = store.read(id)) {
            Job job = ready(id); Path directory = store.directory(id);
            return LatexBibliography.plan(source(id), job.revision(), store.versions().bibliography(job, directory, job.revision()));
        }
    }

    RestoredDocument applyBibliographySetup(String id, int revision, String hash) throws IOException {
        try (var operation = store.edit(id)) {
            Job job = ready(id); requireBibliographyMutation(job, revision); Path directory = store.directory(id);
            String source = new String(store.versions().current(job, directory), StandardCharsets.UTF_8);
            String prepared = LatexBibliography.apply(source, revision, hash, store.versions().bibliography(job, directory, revision));
            return new RestoredDocument(saveSourceLocked(id, prepared, revision), prepared);
        }
    }

    void writeBibliography(String id, Integer revision, OutputStream output) throws IOException {
        try (var operation = store.read(id)) {
            output.write(requiredBibliography(id, revision).bibliography());
        }
    }

    void requireBibliography(String id, Integer revision) throws IOException {
        try (var operation = store.read(id)) { requiredBibliography(id, revision); }
    }
    private BibliographyAssets requiredBibliography(String id, Integer revision) throws IOException {
        Job job = ready(id); var assets = store.versions().bibliography(job, store.directory(id), revision == null ? job.revision() : revision);
        if (assets == null) throw new ApiException(HttpStatus.NOT_FOUND, "This saved version has no attached bibliography.");
        return assets;
    }

    Path pdf(String id) {
        try (var operation = store.read(id)) { return pdfLocked(id); }
    }

    private Path pdfLocked(String id) {
        Job job = ready(id);
        if (!job.previewStatus().equals("READY")) throw new ApiException(HttpStatus.CONFLICT, "Build the PDF preview first.");
        Path pdf = store.directory(id).resolve("preview-" + job.revision()).resolve("main.pdf");
        if (!Files.isRegularFile(pdf)) throw new ApiException(HttpStatus.NOT_FOUND, "The preview file is missing. Save the source and rebuild.");
        return pdf;
    }

    void writePdf(String id, OutputStream stream) throws IOException {
        try (var operation = store.read(id)) { Files.copy(pdfLocked(id), stream); }
    }

    Path original(String id) {
        try (var operation = store.read(id)) {
            Path input = store.directory(id).resolve("input.docx");
            JobStore.checkUnlinked(input);
            if (!Files.isRegularFile(input)) throw new ApiException(HttpStatus.NOT_FOUND, "The original Word document is missing.");
            return input;
        }
    }

    void writeOriginal(String id, OutputStream stream) throws IOException {
        try (var operation = store.read(id)) { Files.copy(original(id), stream); }
    }

    String compilerLog(String id) throws IOException {
        try (var operation = store.read(id)) { return compilerLogLocked(id); }
    }

    private String compilerLogLocked(String id) throws IOException {
        Job job = store.get(id);
        if (!List.of("QUEUED", "COMPILING").contains(job.previewStatus())) {
            try {
                var report = CompilerDiagnostics.load(store.directory(id));
                if (report != null) return report.rawLog() + (report.logTruncated() ? "\n[Log truncated after 64 KiB. Full tool output is retained in project storage: preview.log.]\n" : "");
            } catch (IOException | RuntimeException ignored) { /* The original log remains readable if snapshot metadata is damaged. */ }
        }
        Path log = store.directory(id).resolve("preview.log");
        if (!Files.isRegularFile(log)) return "The compiler has not produced a log yet.";
        var report = CompilerDiagnostics.fromLog(log, null, job.previewStatus(), null);
        return report.rawLog() + (report.logTruncated() ? "\n[Log truncated after 64 KiB. Full tool output is retained in project storage: preview.log.]\n" : "");
    }

    void writeArchive(String id, OutputStream stream) throws IOException {
        try (var operation = store.read(id)) { writeArchiveLocked(id, stream); }
    }

    private void writeArchiveLocked(String id, OutputStream stream) throws IOException {
        Job job = ready(id);
        Path output = store.directory(id).resolve("output");
        SourceLayout sourceLayout = null;
        AiLayoutReport aiReport = null;
        if (job.layout().aiLayout()) try { aiReport = AiLayoutReport.read(output); }
        catch (IOException | RuntimeException exception) { log.warn("Cannot export the AI layout report for {}", id); }
        if (job.layout().preserveSourceLayout()) try { sourceLayout = SourceLayout.read(output); }
        catch (IOException | RuntimeException exception) { log.warn("Cannot export the page-layout report for {}", id); }
        try (ZipOutputStream zip = new ZipOutputStream(stream); var paths = Files.walk(output)) {
            zip.putNextEntry(new ZipEntry("main.tex"));
            zip.write(store.versions().current(job, store.directory(id)));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("conversion-review.json"));
            zip.write(JsonMapper.builder().build().writeValueAsBytes(review(id)));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("layout-settings.json"));
            zip.write(JsonMapper.builder().build().writeValueAsBytes(job.layout()));
            zip.closeEntry();
            if (aiReport != null) {
                zip.putNextEntry(new ZipEntry("ai-layout.json"));
                zip.write(JsonMapper.builder().build().writeValueAsBytes(aiReport)); zip.closeEntry();
            }
            if (sourceLayout != null) {
                zip.putNextEntry(new ZipEntry("source-layout.json"));
                zip.write(JsonMapper.builder().build().writeValueAsBytes(sourceLayout));
                zip.closeEntry();
            }
            var bibliography = store.versions().bibliography(job, store.directory(id), job.revision());
            if (bibliography != null) for (var entry : bibliography.files().entrySet()) {
                zip.putNextEntry(new ZipEntry(BibliographyAssets.FOLDER + "/" + entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
            Path readme = output.resolve("README.txt"); JobStore.checkUnlinked(readme);
            zip.putNextEntry(new ZipEntry("README.txt"));
            if (Files.isRegularFile(readme)) Files.copy(readme, zip); else zip.write("Exported with doc2TeX. Keep project folders beside main.tex.\n".getBytes(StandardCharsets.UTF_8));
            if (bibliography != null) {
                String instructions = "\nBibliography: uploaded UTF-8 bytes are preserved as bibliography/references.bib.\n"
                    + "Tectonic runs BibTeX automatically: tectonic main.tex\n"
                    + "For a TeX distribution/Overleaf, use XeLaTeX + BibTeX: xelatex main.tex; bibtex main; xelatex main.tex; xelatex main.tex\n"
                    + "Keep the bibliography folder, bundled Noto font and OFL license with the source. This export uses this saved revision's bibliography assets.\n";
                zip.write(instructions.getBytes(StandardCharsets.UTF_8));
            }
            zip.closeEntry();
            for (Path file : paths.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path)).sorted().toList()) {
                String name = output.relativize(file).toString().replace('\\', '/');
                if (!(name.startsWith("media/") || name.startsWith("fonts/"))) continue;
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void fail(String id, boolean preview, Exception exception) {
        log.warn("{} failed for {}", preview ? "Preview" : "Conversion", id, exception);
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        String message = exception instanceof IOException && exception.getMessage() != null ? exception.getMessage() : "The operation could not finish. Try again.";
        try {
            Job job = store.get(id);
            store.save(preview ? job.withPreview("FAILED", message) : job.withStatus("FAILED", message));
        } catch (IOException persistenceError) { log.error("Could not save failed job {}", id, persistenceError); }
    }

    private Job ready(String id) {
        Job job = store.get(id);
        if (!job.status().equals("COMPLETED")) throw new ApiException(HttpStatus.CONFLICT, "The conversion is not ready yet.");
        store.requireHistory(id);
        return job;
    }

    private String cleanFilename(String name) {
        if (name == null) return "document.docx";
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }

    @PreDestroy
    void stop() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    }
}
