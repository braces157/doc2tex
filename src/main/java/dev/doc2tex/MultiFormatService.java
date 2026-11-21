package dev.doc2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class MultiFormatService {
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final long MAX_OUTPUT_BYTES = 50L * 1024 * 1024;
    private static final long MAX_INLINED_MEDIA_BYTES = 30L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> EMBED_MEDIA_TARGETS = Set.of("docx", "html", "epub", "pptx");

    record FormatOption(String id, String label, String extension) {}
    record FormatCatalog(boolean pandocReady, boolean legacyDocReady, List<FormatOption> inputs, List<FormatOption> outputs) {}
    record ConvertedFile(String filename, String mediaType, byte[] bytes, boolean textPreviewable, String warnings) {}

    private record OutputSpec(String id, String label, String pandocFormat, String extension,
                              String mediaType, boolean standalone, boolean embedResources, boolean textPreviewable) {}

    private static final Map<String, String> INPUT_FORMATS = Map.ofEntries(
            Map.entry(".docx", "docx"),
            Map.entry(".md", "markdown"),
            Map.entry(".markdown", "markdown"),
            Map.entry(".html", "html"),
            Map.entry(".htm", "html"),
            Map.entry(".epub", "epub"),
            Map.entry(".odt", "odt"),
            Map.entry(".rtf", "rtf"),
            Map.entry(".tex", "latex"),
            Map.entry(".latex", "latex"),
            Map.entry(".rst", "rst"),
            Map.entry(".typ", "typst"));

    private static final List<FormatOption> INPUT_OPTIONS = List.of(
            new FormatOption("docx", "Word Document", ".docx"),
            new FormatOption("doc", "Legacy Word (requires LibreOffice)", ".doc"),
            new FormatOption("markdown", "Markdown", ".md"),
            new FormatOption("html", "HTML", ".html"),
            Map.entry("epub", new FormatOption("epub", "EPUB E-Book", ".epub")).getValue(),
            new FormatOption("odt", "OpenDocument Text", ".odt"),
            new FormatOption("rtf", "Rich Text", ".rtf"),
            new FormatOption("latex", "LaTeX Source", ".tex"),
            new FormatOption("rst", "reStructuredText", ".rst"),
            new FormatOption("typst", "Typst", ".typ"));

    private static final List<OutputSpec> OUTPUT_SPECS = List.of(
            new OutputSpec("docx", "Word Document (.docx)", "docx", ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, false, false),
            new OutputSpec("markdown", "Markdown (.md)", "gfm", ".md", "text/markdown;charset=UTF-8", false, false, true),
            new OutputSpec("html", "Self-contained HTML (.html)", "html5", ".html", "text/html;charset=UTF-8", true, true, true),
            new OutputSpec("epub", "EPUB 3 E-Book (.epub)", "epub3", ".epub", "application/epub+zip", false, false, false),
            new OutputSpec("odt", "OpenDocument (.odt)", "odt", ".odt", "application/vnd.oasis.opendocument.text", false, false, false),
            new OutputSpec("rtf", "Rich Text (.rtf)", "rtf", ".rtf", "application/rtf", true, false, false),
            new OutputSpec("latex", "LaTeX (.tex)", "latex", ".tex", "text/plain;charset=UTF-8", true, false, true),
            new OutputSpec("beamer", "Beamer Slides (.tex)", "beamer", ".tex", "text/plain;charset=UTF-8", true, false, true),
            new OutputSpec("pptx", "PowerPoint (.pptx)", "pptx", ".pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation", false, false, false),
            new OutputSpec("typst", "Typst (.typ)", "typst", ".typ", "text/plain;charset=UTF-8", false, false, true));

    private static final Set<String> JOB_EXPORT_FORMATS = Set.of("docx", "markdown", "html", "epub", "typst", "pptx");

    private final Tools tools;
    private final ToolRunner runner;
    private final DocxValidator validator;
    private final AppProperties properties;
    private final JobStore store;
    private final AiLayoutSettings aiSettings;
    private final SourcePageRenderer renderer;
    private final BooleanSupplier legacyDocReadyCheck;

    @Autowired
    MultiFormatService(
            Tools tools,
            ToolRunner runner,
            DocxValidator validator,
            AppProperties properties,
            JobStore store,
            AiLayoutSettings aiSettings) {
        this(tools, runner, validator, properties, store, aiSettings, new SourcePageRenderer(runner),
                () -> SourcePageRenderer.inspect(aiSettings.rendererExecutable(), aiSettings.dockerImage())
                        .state().equals("ready"));
    }

    MultiFormatService(
            Tools tools,
            ToolRunner runner,
            DocxValidator validator,
            AppProperties properties,
            JobStore store,
            AiLayoutSettings aiSettings,
            SourcePageRenderer renderer,
            BooleanSupplier legacyDocReadyCheck) {
        this.tools = tools;
        this.runner = runner;
        this.validator = validator;
        this.properties = properties;
        this.store = store;
        this.aiSettings = aiSettings;
        this.renderer = renderer;
        this.legacyDocReadyCheck = legacyDocReadyCheck;
    }

    FormatCatalog formats() {
        boolean pandocReady = tools.available(tools.pandoc());
        boolean legacyDocReady = legacyDocReadyCheck.getAsBoolean();
        List<FormatOption> outputs = OUTPUT_SPECS.stream()
                .map(spec -> new FormatOption(spec.id(), spec.label(), spec.extension()))
                .toList();
        return new FormatCatalog(pandocReady, legacyDocReady, INPUT_OPTIONS, outputs);
    }

    ConvertedFile convert(MultipartFile file, String targetFormat, boolean toc) throws IOException {
        if (file == null || file.isEmpty()) {
            throw bad("Choose a non-empty document to convert.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Each file must be 20 MB or smaller.");
        }
        OutputSpec outputSpec = findOutputSpec(targetFormat);
        String originalName = PdfStudioService.sanitizeDisplayFilename(file.getOriginalFilename(), "document");
        String rawName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int slash = Math.max(rawName.lastIndexOf('/'), rawName.lastIndexOf('\\'));
        String ext = extensionOf(slash >= 0 ? rawName.substring(slash + 1) : rawName);
        if (ext.isEmpty()) ext = extensionOf(originalName);
        boolean legacyDoc = ext.equals(".doc");
        String pandocInput = INPUT_FORMATS.get(ext);
        if (!legacyDoc && pandocInput == null) {
            throw bad("Unsupported file extension \"" + ext + "\". Supported inputs: .docx, .doc, .md, .html, .epub, .odt, .rtf, .tex, .rst, .typ.");
        }
        if (!tools.available(tools.pandoc())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Pandoc is unavailable. Run setup tools before converting.");
        }

        Path tempDir = Files.createTempDirectory("doc2tex-convert-");
        try {
            Path inputFile = tempDir.resolve("input" + ext);
            file.transferTo(inputFile);
            if (Files.size(inputFile) == 0 || Files.size(inputFile) > MAX_FILE_BYTES) {
                throw bad("Choose a non-empty file up to 20 MB.");
            }
            if (ext.equals(".docx")) {
                validator.validate(inputFile);
            } else if (legacyDoc) {
                if (!legacyDocReadyCheck.getAsBoolean()) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Converting legacy .doc files requires LibreOffice. Run setup-ai-layout or convert to .docx first.");
                }
                Path convertedDocx;
                try {
                    convertedDocx = renderer.convertToDocx(inputFile, tempDir, aiSettings.rendererExecutable(),
                            aiSettings.dockerImage(), properties.conversionTimeoutSeconds());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Legacy .doc conversion was interrupted.", exception);
                } catch (IOException exception) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Could not convert legacy .doc file \"" + originalName + "\". Check that the file is a valid Word 97-2003 document.");
                }
                validator.validate(convertedDocx);
                if (outputSpec.id().equals("docx") && !toc) {
                    if (Files.size(convertedDocx) > MAX_OUTPUT_BYTES) {
                        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The converted output exceeds the 50 MB size limit.");
                    }
                    byte[] outBytes = Files.readAllBytes(convertedDocx);
                    String downloadName = PdfStudioService.sanitizeDownloadName(
                            PdfStudioService.stripExtension(originalName) + outputSpec.extension(),
                            "converted" + outputSpec.extension(),
                            outputSpec.extension());
                    return new ConvertedFile(downloadName, outputSpec.mediaType(), outBytes, false, "");
                }
                // Stage the LibreOffice output directly inside tempDir so Pandoc resolves input.docx from its working directory.
                inputFile = tempDir.resolve("input.docx");
                Files.copy(convertedDocx, inputFile, StandardCopyOption.REPLACE_EXISTING);
                pandocInput = "docx";
            }

            Path outFile = tempDir.resolve("output" + outputSpec.extension());
            List<String> command = buildCommand(pandocInput, outputSpec, inputFile, outFile, toc, false);
            ToolRunner.Result result = runPandoc(command, tempDir, tempDir.resolve("convert.log"));
            if (result.exitCode() != 0 || !Files.isRegularFile(outFile)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Pandoc could not convert \"" + originalName + "\" to " + outputSpec.id() + ". Check that the file is valid.");
            }
            if (Files.size(outFile) > MAX_OUTPUT_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The converted output exceeds the 50 MB size limit.");
            }
            byte[] outBytes = Files.readAllBytes(outFile);
            String downloadName = PdfStudioService.sanitizeDownloadName(
                    PdfStudioService.stripExtension(originalName) + outputSpec.extension(),
                    "converted" + outputSpec.extension(),
                    outputSpec.extension());
            return new ConvertedFile(downloadName, outputSpec.mediaType(), outBytes, outputSpec.textPreviewable(), cleanWarnings(result.log()));
        } finally {
            SourceVersionStore.removeTreeQuietly(tempDir);
        }
    }

    ConvertedFile exportJob(String jobId, String targetFormat) throws IOException {
        if (!JOB_EXPORT_FORMATS.contains(targetFormat)) {
            throw bad("Choose one of the supported workspace export formats: docx, markdown, html, epub, typst, or pptx.");
        }
        OutputSpec outputSpec = findOutputSpec(targetFormat);
        if (!tools.available(tools.pandoc())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Pandoc is unavailable. Run setup tools before exporting.");
        }

        byte[] sourceBytes;
        BibliographyAssets bibliography;
        String baseName;
        Path mediaSourceDir;
        try (var ignored = store.read(jobId)) {
            Job job = store.get(jobId);
            if (!job.status().equals("COMPLETED")) {
                throw new ApiException(HttpStatus.CONFLICT, "Wait for the conversion to complete before exporting.");
            }
            store.requireHistory(jobId);
            Path directory = store.directory(jobId);
            sourceBytes = store.versions().current(job, directory);
            bibliography = store.versions().bibliography(job, directory, job.revision());
            baseName = PdfStudioService.stripExtension(
                    PdfStudioService.sanitizeDisplayFilename(job.displayName().isBlank() ? job.filename() : job.displayName(), "document"));
            mediaSourceDir = directory.resolve("output").resolve("media");
        }

        Path tempDir = Files.createTempDirectory("doc2tex-job-export-");
        try {
            Path texFile = tempDir.resolve("main.tex");
            Files.write(texFile, sourceBytes);
            Path localMediaDir = tempDir.resolve("media");
            boolean copiedMedia = copyDirectoryFiles(mediaSourceDir, localMediaDir);
            boolean hasBib = false;
            if (bibliography != null && bibliography.files().containsKey(BibliographyAssets.BIB)) {
                Path bibDir = tempDir.resolve(BibliographyAssets.FOLDER);
                Files.createDirectories(bibDir);
                Files.write(bibDir.resolve(BibliographyAssets.BIB), bibliography.files().get(BibliographyAssets.BIB));
                hasBib = true;
            }
            Path outFile = tempDir.resolve("export" + outputSpec.extension());
            ToolRunner.Result result;
            List<String> extraWarnings = new ArrayList<>();
            if (copiedMedia && EMBED_MEDIA_TARGETS.contains(outputSpec.id())) {
                // Keep Pandoc --sandbox enforced on both passes while inlining only validated job media as data URIs.
                Path astFile = tempDir.resolve("ast.json");
                List<String> toJsonCmd = new ArrayList<>(List.of(
                        tools.pandoc(), "--sandbox", "--from=latex", "--to=json"));
                if (hasBib) {
                    toJsonCmd.add("--citeproc");
                    toJsonCmd.add("--bibliography=" + BibliographyAssets.FOLDER + "/" + BibliographyAssets.BIB);
                }
                toJsonCmd.add("-o");
                toJsonCmd.add(astFile.getFileName().toString());
                toJsonCmd.add(texFile.getFileName().toString());
                ToolRunner.Result parseRes = runPandoc(toJsonCmd, tempDir, tempDir.resolve("export-ast.log"));
                if (parseRes.exitCode() != 0 || !Files.isRegularFile(astFile)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Could not export LaTeX source to " + outputSpec.id() + ". Check for unsupported custom macros.");
                }
                int skippedMedia = inlineLocalMediaInPandocAst(astFile, tempDir, localMediaDir);
                if (skippedMedia > 0) {
                    extraWarnings.add(skippedMedia + " image reference(s) could not be embedded (unsupported format, missing path, or 30 MB media limit).");
                }
                List<String> fromJsonCmd = buildCommand("json", outputSpec, astFile, outFile, false, false);
                result = runPandoc(fromJsonCmd, tempDir, tempDir.resolve("export.log"));
            } else {
                if (copiedMedia) {
                    extraWarnings.add("Single-file " + outputSpec.id() + " export does not bundle extracted media/ images; export DOCX/HTML/EPUB/PPTX or keep the ZIP media/ folder alongside this file.");
                }
                List<String> command = buildCommand("latex", outputSpec, texFile, outFile, false, hasBib);
                result = runPandoc(command, tempDir, tempDir.resolve("export.log"));
            }
            if (result.exitCode() != 0 || !Files.isRegularFile(outFile)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Could not export LaTeX source to " + outputSpec.id() + ". Check for unsupported custom macros.");
            }
            if (Files.size(outFile) > MAX_OUTPUT_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The exported document exceeds the 50 MB size limit.");
            }
            byte[] outBytes = Files.readAllBytes(outFile);
            String downloadName = PdfStudioService.sanitizeDownloadName(
                    baseName + outputSpec.extension(),
                    "export" + outputSpec.extension(),
                    outputSpec.extension());
            String pandocWarn = cleanWarnings(result.log());
            if (!pandocWarn.isEmpty()) extraWarnings.add(pandocWarn);
            String combinedWarnings = cleanWarnings(String.join(" | ", extraWarnings));
            return new ConvertedFile(downloadName, outputSpec.mediaType(), outBytes, outputSpec.textPreviewable(), combinedWarnings);
        } finally {
            SourceVersionStore.removeTreeQuietly(tempDir);
        }
    }

    private List<String> buildCommand(
            String inputFormat,
            OutputSpec outputSpec,
            Path inputFile,
            Path outputFile,
            boolean toc,
            boolean withBibliography) {
        List<String> cmd = new ArrayList<>();
        cmd.add(tools.pandoc());
        cmd.add("--sandbox");
        cmd.add("--from=" + inputFormat);
        cmd.add("--to=" + outputSpec.pandocFormat());
        if (outputSpec.standalone()) cmd.add("--standalone");
        if (outputSpec.embedResources()) cmd.add("--embed-resources");
        if (toc) cmd.add("--toc");
        if (List.of("latex", "beamer", "gfm").contains(outputSpec.pandocFormat())) cmd.add("--wrap=none");
        cmd.add("--resource-path=.");
        if (withBibliography) {
            cmd.add("--citeproc");
            cmd.add("--bibliography=" + BibliographyAssets.FOLDER + "/" + BibliographyAssets.BIB);
        }
        cmd.add("-o");
        cmd.add(outputFile.getFileName().toString());
        cmd.add(inputFile.getFileName().toString());
        return cmd;
    }

    private ToolRunner.Result runPandoc(List<String> command, Path workDir, Path logFile) throws IOException {
        try {
            return runner.run(command, workDir, logFile, properties.conversionTimeoutSeconds());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Format conversion was interrupted.", exception);
        }
    }

    private static boolean copyDirectoryFiles(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.isDirectory(sourceDir, LinkOption.NOFOLLOW_LINKS)) return false;
        JobStore.checkUnlinked(sourceDir);
        Files.createDirectories(targetDir);
        boolean copiedAny = false;
        try (var stream = Files.walk(sourceDir, 3)) {
            for (Path entry : stream.toList()) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                Path relative = sourceDir.relativize(entry);
                Path dest = targetDir.resolve(relative.toString()).normalize();
                if (!dest.startsWith(targetDir)) continue;
                Files.createDirectories(dest.getParent());
                Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING);
                copiedAny = true;
            }
        }
        return copiedAny;
    }

    private static int inlineLocalMediaInPandocAst(Path astFile, Path tempDir, Path mediaDir) throws IOException {
        JsonNode root = JSON.readTree(Files.readAllBytes(astFile));
        long[] budget = {0L};
        int[] skipped = {0};
        Path canonicalMedia = mediaDir.toAbsolutePath().normalize();
        Path canonicalTemp = tempDir.toAbsolutePath().normalize();
        if (rewriteImageNodes(root, canonicalTemp, canonicalMedia, budget, skipped)) {
            Files.write(astFile, JSON.writeValueAsBytes(root));
        }
        return skipped[0];
    }

    private static boolean rewriteImageNodes(
            JsonNode node, Path tempDir, Path mediaDir, long[] budget, int[] skipped) throws IOException {
        boolean changed = false;
        if (node instanceof ObjectNode obj) {
            JsonNode type = obj.get("t");
            JsonNode content = obj.get("c");
            if (type != null && "Image".equals(type.asString()) && content instanceof ArrayNode arr && arr.size() == 3) {
                JsonNode target = arr.get(2);
                if (target instanceof ArrayNode targetArr && targetArr.size() >= 1 && targetArr.get(0).isString()) {
                    String src = targetArr.get(0).asString();
                    String dataUri = toSafeMediaDataUri(src, tempDir, mediaDir, budget);
                    if (dataUri != null) {
                        targetArr.set(0, dataUri);
                        changed = true;
                    } else if (src != null && !src.isBlank() && !src.startsWith("data:") && !src.contains("://")) {
                        skipped[0]++;
                    }
                }
            }
            for (var entry : obj.properties()) {
                if (rewriteImageNodes(entry.getValue(), tempDir, mediaDir, budget, skipped)) changed = true;
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                if (rewriteImageNodes(child, tempDir, mediaDir, budget, skipped)) changed = true;
            }
        }
        return changed;
    }

    private static String toSafeMediaDataUri(String src, Path tempDir, Path mediaDir, long[] budget) throws IOException {
        if (src == null || src.isBlank() || src.contains("://") || src.startsWith("data:") || src.startsWith("/")) {
            return null;
        }
        String normalized = src.replace('\\', '/');
        Path candidate = tempDir.resolve(normalized).normalize();
        if (!candidate.startsWith(mediaDir) || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String mime = imageMimeType(candidate.getFileName().toString());
        if (mime == null) return null;
        long size = Files.size(candidate);
        if (size <= 0 || budget[0] + size > MAX_INLINED_MEDIA_BYTES) return null;
        budget[0] += size;
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(candidate));
    }

    private static String imageMimeType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return null;
    }

    static boolean isSupportedOutputFormat(String targetFormat) {
        String key = targetFormat == null ? "" : targetFormat.trim().toLowerCase(Locale.ROOT);
        return OUTPUT_SPECS.stream().anyMatch(spec -> spec.id().equals(key));
    }

    private static OutputSpec findOutputSpec(String targetFormat) {
        String key = targetFormat == null ? "" : targetFormat.trim().toLowerCase(Locale.ROOT);
        return OUTPUT_SPECS.stream()
                .filter(spec -> spec.id().equals(key))
                .findFirst()
                .orElseThrow(() -> bad("Unsupported target format \"" + key + "\"."));
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static String cleanWarnings(String log) {
        if (log == null || log.isBlank()) return "";
        String singleLine = log.replaceAll("\\p{Cntrl}+", " ").trim();
        return singleLine.length() > 300 ? singleLine.substring(0, 300) + "..." : singleLine;
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
