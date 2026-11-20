package dev.doc2tex;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Builds deterministic ZIP project exports without holding conversion service plumbing. */
final class JobArchive {
    private static final Logger log = LoggerFactory.getLogger(JobArchive.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JobArchive() {}

    static void write(Job job, Path directory, byte[] source, BibliographyAssets bibliography,
                      DocumentInspector.Review review, OutputStream stream) throws IOException {
        Path output = directory.resolve("output");
        SourceLayout sourceLayout = null;
        AiLayoutReport aiReport = null;
        AutoPolish.Report autoPolish = null;
        if (job.layout().aiLayout()) {
            try { aiReport = AiLayoutReport.read(output); }
            catch (IOException | RuntimeException exception) { log.warn("Cannot export the AI layout report for {}", job.id()); }
        }
        if (job.layout().preserveSourceLayout()) {
            try { sourceLayout = SourceLayout.read(output); }
            catch (IOException | RuntimeException exception) { log.warn("Cannot export the page-layout report for {}", job.id()); }
        }
        try { autoPolish = AutoPolish.read(output); }
        catch (IOException | RuntimeException exception) { log.warn("Cannot export the auto-polish report for {}", job.id()); }

        try (ZipOutputStream zip = new ZipOutputStream(stream); var paths = Files.walk(output)) {
            zip.putNextEntry(new ZipEntry("main.tex"));
            zip.write(source);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("conversion-review.json"));
            zip.write(JSON.writeValueAsBytes(review));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("layout-settings.json"));
            zip.write(JSON.writeValueAsBytes(job.layout()));
            zip.closeEntry();
            if (aiReport != null) {
                zip.putNextEntry(new ZipEntry("ai-layout.json"));
                zip.write(JSON.writeValueAsBytes(aiReport));
                zip.closeEntry();
            }
            if (sourceLayout != null) {
                zip.putNextEntry(new ZipEntry("source-layout.json"));
                zip.write(JSON.writeValueAsBytes(sourceLayout));
                zip.closeEntry();
            }
            if (autoPolish != null) {
                zip.putNextEntry(new ZipEntry("auto-polish.json"));
                zip.write(JSON.writeValueAsBytes(autoPolish));
                zip.closeEntry();
            }
            if (bibliography != null) {
                for (var entry : bibliography.files().entrySet()) {
                    zip.putNextEntry(new ZipEntry(BibliographyAssets.FOLDER + "/" + entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            Path readme = output.resolve("README.txt");
            JobStore.checkUnlinked(readme);
            zip.putNextEntry(new ZipEntry("README.txt"));
            if (Files.isRegularFile(readme)) {
                Files.copy(readme, zip);
            } else {
                zip.write("Exported with doc2TeX. Keep project folders beside main.tex.\n".getBytes(StandardCharsets.UTF_8));
            }
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
}
