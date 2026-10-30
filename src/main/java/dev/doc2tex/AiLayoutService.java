package dev.doc2tex;

import jakarta.annotation.PreDestroy;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
final class AiLayoutService {
    private static final Logger LOG = LoggerFactory.getLogger(AiLayoutService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AiLayoutSettings settings;
    private final SourcePageRenderer renderer;
    private final Semaphore inferenceSlot = new Semaphore(1, true);
    private DocLayoutModel model;

    AiLayoutService(AiLayoutSettings settings, ToolRunner runner) {
        this.settings = settings; this.renderer = new SourcePageRenderer(runner);
    }

    record Status(String state, String guidance) {}

    Status status() {
        if (!settings.enabled()) return new Status("disabled", "AI layout assistance is disabled in this installation. Standard conversion remains available.");
        boolean packaged = PackagedRuntime.packaged();
        if (!Files.isRegularFile(settings.modelPath())) return new Status("missing", packaged
                ? "AI layout assistance is not installed in this package. Standard conversion remains available; use a source checkout to install the optional model."
                : "Install the local layout model and renderer with scripts/setup-ai-layout.ps1 (Windows) or scripts/setup-ai-layout.sh (Linux/macOS), then retry.");
        var rendererStatus = SourcePageRenderer.inspect(settings.rendererExecutable(), settings.dockerImage());
        if (!rendererStatus.state().equals("ready")) return new Status(rendererStatus.state(), packaged
                ? "The optional document renderer is unavailable. Standard conversion remains available."
                : settings.dockerImage().isEmpty()
                        ? "LibreOffice is unavailable. Run the AI layout setup script or set SOFFICE_PATH, then retry. Standard conversion remains available."
                        : "The Docker document renderer is unavailable. Start Docker Desktop and run scripts/setup-ai-layout.ps1 -DockerRenderer, then retry. Standard conversion remains available.");
        return new Status("ready", "Local AI layout files and renderer are available. Conversion verifies the model checksum before inference. Documents stay on this computer.");
    }

    AiLayoutReport prepare(Path original, Path directory, Path output, LayoutOptions layout, List<String> command) throws IOException, InterruptedException {
        if (!layout.aiLayout()) return null;
        Status available = status();
        if (!available.state().equals("ready")) {
            var report = AiLayoutReport.unavailable(available.guidance()); report.save(output); return report;
        }
        AiLayoutReport report;
        try { report = analyze(original, directory); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw exception; }
        catch (Exception | LinkageError exception) {
            LOG.warn("AI layout assistance failed for {}; retaining Word structure", directory.getFileName(), exception);
            report = AiLayoutReport.failed();
        }
        report.save(output);
        if (!report.headings().isEmpty() || report.twoColumns()) {
            var starts = report.twoColumns() ? WordLayout.read(original).columnStarts() : List.<String>of();
            Files.write(output.resolve("ai-layout-plan.json"), JSON.writeValueAsBytes(Map.of("headings", report.headings(), "columnStarts", starts)));
            try (var input = new ClassPathResource("ai-layout.lua").getInputStream()) {
                Files.copy(input, output.resolve("ai-layout.lua"), StandardCopyOption.REPLACE_EXISTING);
            }
            command.add("--lua-filter=ai-layout.lua");
        }
        if (report.twoColumns()) {
            var source = WordLayout.read(original);
            Files.writeString(output.resolve("ai-layout-columns.tex"), "\\setlength{\\columnsep}{" + source.columnGapMm().toPlainString() + "mm}\n");
            command.add("--variable=classoption:twocolumn"); command.add("--include-in-header=ai-layout-columns.tex");
        }
        return report;
    }

    private AiLayoutReport analyze(Path original, Path directory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.timeoutSeconds());
        var source = WordLayout.read(original);
        Path pdf = renderer.render(original, directory, settings.rendererExecutable(), settings.dockerImage(), remaining(deadline));
        if (!inferenceSlot.tryAcquire(remaining(deadline), TimeUnit.SECONDS)) throw new IOException("The AI layout worker is busy.");
        try (var document = Loader.loadPDF(pdf.toFile())) {
            if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > 1000) throw new IOException("Unsupported source page count.");
            DocLayoutModel detector = model();
            var pages = new ArrayList<AiLayoutReport.Page>();
            var headings = new LinkedHashMap<Integer, AiLayoutReport.Heading>();
            Map<String, WordLayout.Paragraph> candidates = new LinkedHashMap<>();
            for (var paragraph : source.paragraphs()) if (paragraph.headingCandidate()) candidates.put(WordLayout.key(paragraph.text()), paragraph);
            var imageRenderer = new PDFRenderer(document);
            int count = Math.min(settings.maxPages(), document.getNumberOfPages());
            for (int index = 0; index < count; index++) {
                remaining(deadline);
                PDPage page = document.getPage(index);
                float longest = Math.max(page.getCropBox().getWidth(), page.getCropBox().getHeight());
                float dpi = Math.min(110f, 1600f * 72f / longest);
                var image = imageRenderer.renderImageWithDPI(index, dpi);
                var regions = detector.detect(image, remaining(deadline));
                pages.add(new AiLayoutReport.Page(index + 1, regions));
                for (var region : regions) {
                    if (!region.label().equals("paragraph_title") || region.confidence() < .8f) continue;
                    String text = regionText(page, region);
                    var paragraph = candidates.get(WordLayout.key(text));
                    if (paragraph == null) continue;
                    var heading = new AiLayoutReport.Heading(paragraph.index(), index + 1, paragraph.text(), region.confidence());
                    var previous = headings.get(paragraph.index());
                    if (previous == null || previous.confidence() < heading.confidence()) headings.put(paragraph.index(), heading);
                }
            }
            var warnings = new ArrayList<String>();
            if (count < document.getNumberOfPages()) warnings.add("AI layout inspected the first " + count + " pages. Later pages retain their original Word structure.");
            if (headings.size() > 100) warnings.add("Automatic heading repairs were limited to 100 source paragraphs.");
            boolean columns = source.uniformTwoColumns() && !source.hasTables() && pages.stream().allMatch(AiLayoutService::twoColumns);
            if (source.uniformTwoColumns() && !columns) warnings.add(source.hasTables()
                    ? "The source uses two columns with tables. Automatic columns were skipped to preserve editable tables."
                    : "The source requests two columns, but visual evidence did not confirm both columns on every inspected page. Column formatting was left unchanged.");
            if (columns && count < document.getNumberOfPages()) { columns = false; warnings.add("Two-column formatting was left unchanged because some source pages were outside the inspection limit."); }
            return new AiLayoutReport(AiLayoutReport.Status.ANALYZED, "PP-DocLayoutV3", document.getNumberOfPages(), pages,
                    headings.values().stream().limit(100).toList(), List.of(), columns, 0, warnings);
        } finally { inferenceSlot.release(); }
    }

    private static boolean twoColumns(AiLayoutReport.Page page) {
        var body = page.regions().stream().filter(region -> region.label().equals("text") && region.confidence() >= .65f).toList();
        boolean left = body.stream().anyMatch(region -> region.left() < .4f && region.right() < .58f && region.right() - region.left() > .12f);
        boolean right = body.stream().anyMatch(region -> region.left() > .42f && region.right() > .6f && region.right() - region.left() > .12f);
        return left && right;
    }

    private static String regionText(PDPage page, DocLayoutModel.Region region) throws IOException {
        float width = page.getCropBox().getWidth(), height = page.getCropBox().getHeight();
        if (Math.floorMod(page.getRotation(), 180) != 0) { float swap = width; width = height; height = swap; }
        float left = Math.max(0, region.left() - .002f), top = Math.max(0, region.top() - .002f);
        float right = Math.min(1, region.right() + .002f), bottom = Math.min(1, region.bottom() + .002f);
        var stripper = new PDFTextStripperByArea(); stripper.setSortByPosition(true);
        stripper.addRegion("heading", new Rectangle2D.Float(left * width, top * height, (right - left) * width, (bottom - top) * height));
        stripper.extractRegions(page);
        return WordLayout.normalized(stripper.getTextForRegion("heading"));
    }

    private synchronized DocLayoutModel model() throws Exception {
        if (model == null) model = new DocLayoutModel(settings.modelPath());
        return model;
    }

    private static int remaining(long deadline) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("AI layout analysis interrupted.");
        long seconds = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
        if (seconds < 1) throw new IOException("AI layout analysis exceeded its time limit.");
        return (int) seconds;
    }

    AiLayoutReport finish(Path output, AiLayoutReport report) throws IOException {
        if (report == null || (report.headings().isEmpty() && !report.twoColumns())) return report;
        Path file = output.resolve("ai-layout-applied.json"); JobStore.checkUnlinked(file);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(131073);
            if (bytes.length > 131072) throw new IOException("The applied heading report exceeds its limit.");
            var applied = JSON.readValue(bytes, Applied.class);
            var plan = JSON.readTree(Files.readAllBytes(output.resolve("ai-layout-plan.json")));
            int expected = plan.path("columnStarts").size();
            var finished = report.applied(applied.headings(), applied.columnBreaks().size(), applied.columnBreaks().size() != expected);
            finished.save(output); return finished;
        }
    }

    record Applied(List<String> headings, List<String> columnBreaks) {
        Applied { headings = List.copyOf(headings); columnBreaks = List.copyOf(columnBreaks); }
    }

    @PreDestroy synchronized void close() throws Exception { if (model != null) model.close(); }
}
