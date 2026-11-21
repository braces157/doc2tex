package dev.doc2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SourcePageRenderer {
    private static final String PROFILE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <oor:items xmlns:oor="http://openoffice.org/2001/registry">
              <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
                <prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop>
                <prop oor:name="DisableActiveContent" oor:op="fuse"><value>true</value></prop>
                <prop oor:name="BlockUntrustedRefererLinks" oor:op="fuse"><value>true</value></prop>
              </item>
              <item oor:path="/org.openoffice.Office.Writer/Content/Update">
                <prop oor:name="Link" oor:op="fuse"><value>2</value></prop>
              </item>
            </oor:items>
            """;
    private final ToolRunner runner;

    SourcePageRenderer(ToolRunner runner) { this.runner = runner; }

    static Tools.Diagnostic inspect(String executable, String dockerImage) {
        return dockerImage.isBlank() ? Tools.inspect("Source document renderer", executable)
                : Tools.inspect("Source document renderer", "docker", List.of("image", "inspect", "--format={{.Id}}", dockerImage));
    }

    Path render(Path original, Path directory, String executable, String dockerImage, int timeoutSeconds)
            throws IOException, InterruptedException {
        return convert(original, directory, executable, dockerImage, timeoutSeconds,
                "ai-layout-source", "input.docx", "input.pdf", "pdf:writer_pdf_Export",
                directory.resolve("ai-layout-render.log"), "The original document could not be rendered within the layout limits.");
    }

    Path convertToDocx(Path original, Path directory, String executable, String dockerImage, int timeoutSeconds)
            throws IOException, InterruptedException {
        return convert(original, directory, executable, dockerImage, timeoutSeconds,
                "legacy-doc-source", "input.doc", "input.docx", "docx",
                directory.resolve("legacy-doc-render.log"), "The legacy .doc file could not be converted cleanly.");
    }

    private Path convert(
            Path original,
            Path directory,
            String executable,
            String dockerImage,
            int timeoutSeconds,
            String folderName,
            String inputFilename,
            String outputFilename,
            String convertFilter,
            Path logFile,
            String failureMessage) throws IOException, InterruptedException {
        boolean docker = !dockerImage.isBlank();
        if (docker && !dockerImage.matches("[A-Za-z0-9][A-Za-z0-9_.:/@-]{0,255}")) {
            throw new IllegalArgumentException("Choose a valid local Docker image for the source renderer.");
        }
        directory = directory.toAbsolutePath().normalize();
        Path render = directory.resolve(folderName), profile = render.resolve("profile");
        Path input = render.resolve(inputFilename), out = render.resolve(outputFilename);
        JobStore.checkUnlinked(original.toAbsolutePath());
        JobStore.checkUnlinked(profile.resolve("user/registrymodifications.xcu"));
        JobStore.checkUnlinked(input);
        JobStore.checkUnlinked(out);
        Files.createDirectories(profile.resolve("user"));
        Files.writeString(profile.resolve("user/registrymodifications.xcu"), PROFILE, StandardCharsets.UTF_8);
        Files.copy(original, input, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(out);
        String container = "doc2tex-layout-" + UUID.nameUUIDFromBytes(render.toString().getBytes(StandardCharsets.UTF_8));
        List<String> command;
        if (docker) {
            var arguments = new ArrayList<>(List.of("docker", "run", "--rm", "--name", container, "--network", "none", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--pull=never", "--env", "XDG_CACHE_HOME=/work/profile/cache"));
            if (!System.getProperty("os.name").startsWith("Windows")) {
                String uid = Files.getAttribute(render, "unix:uid").toString(), gid = Files.getAttribute(render, "unix:gid").toString();
                arguments.addAll(List.of("--user", uid + ":" + gid));
            }
            if (System.getProperty("os.name").startsWith("Windows")) arguments.addAll(List.of("--volume", render + ":/work:rw"));
            else {
                String source = "\"" + ("source=" + render).replace("\"", "\"\"") + "\"";
                arguments.addAll(List.of("--mount", "type=bind," + source + ",target=/work"));
            }
            arguments.addAll(List.of(dockerImage,
                    "-env:UserInstallation=file:///work/profile", "--headless", "--nologo", "--nodefault", "--norestore",
                    "--convert-to", convertFilter, "--outdir", "/work", "/work/" + inputFilename));
            command = arguments;
        } else {
            command = List.of(executable, "-env:UserInstallation=" + profile.toUri().toASCIIString(),
                    "--headless", "--nologo", "--nodefault", "--norestore", "--convert-to", convertFilter,
                    "--outdir", render.toString(), input.toString());
        }
        boolean completed = false;
        try {
            var result = runner.run(command, render, logFile, timeoutSeconds);
            JobStore.checkUnlinked(out);
            if (result.exitCode() != 0 || !Files.isRegularFile(out) || Files.size(out) == 0 || Files.size(out) > 40L * 1024 * 1024) {
                throw new IOException(failureMessage);
            }
            completed = true;
            return out;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            if (docker && !completed) removeContainer(container, render, directory.resolve("ai-layout-cleanup.log"));
        }
    }

    private void removeContainer(String container, Path render, Path log) {
        boolean interrupted = Thread.interrupted();
        try { runner.run(List.of("docker", "rm", "-f", container), render, log, 5); }
        catch (InterruptedException exception) { interrupted = true; }
        catch (IOException exception) { /* Preserve the renderer failure; cleanup output remains in its own log. */ }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
