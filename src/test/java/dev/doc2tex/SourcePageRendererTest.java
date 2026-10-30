package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SourcePageRendererTest {
    @TempDir Path directory;
    private static final String IMAGE = "doc2tex/libreoffice:probe";

    @Test void dockerMountContainsOnlyIsolatedSourceAndHardenedProfile() throws Exception {
        Path original = directory.resolve("private/input.docx"), job = directory.resolve("job, with spaces");
        Files.createDirectories(original.getParent()); Files.createDirectories(job);
        Files.writeString(original, "original Word bytes"); Files.writeString(original.resolveSibling("private.txt"), "private workspace file");
        var runner = new RendererRunner(Outcome.SUCCESS);
        Path pdf = new SourcePageRenderer(runner).render(original, job, "unused-native-renderer", IMAGE, 12);
        Path render = job.resolve("ai-layout-source");
        assertEquals(render.resolve("input.pdf"), pdf);
        assertEquals("original Word bytes", Files.readString(render.resolve("input.docx")));
        assertFalse(Files.exists(render.resolve("private.txt")));
        String profile = Files.readString(render.resolve("profile/user/registrymodifications.xcu"));
        assertTrue(profile.contains("DisableMacrosExecution")); assertTrue(profile.contains("BlockUntrustedRefererLinks"));
        var command = runner.commands.getFirst();
        assertEquals(List.of("docker", "run", "--rm"), command.subList(0, 3));
        assertEquals(List.of("--network", "none", "--cap-drop", "ALL", "--security-opt", "no-new-privileges"), command.subList(5, 11));
        if (System.getProperty("os.name").startsWith("Windows"))
            assertEquals(render + ":/work:rw", command.get(command.indexOf("--volume") + 1));
        else assertEquals("type=bind,\"source=" + render + "\",target=/work", command.get(command.indexOf("--mount") + 1));
        assertTrue(command.contains("--pull=never"));
        if (!System.getProperty("os.name").startsWith("Windows"))
            assertEquals(Files.getAttribute(render, "unix:uid") + ":" + Files.getAttribute(render, "unix:gid"), command.get(command.indexOf("--user") + 1));
        assertEquals("-env:UserInstallation=file:///work/profile", command.get(command.indexOf(IMAGE) + 1));
        assertFalse(command.contains("soffice"), "The image entrypoint supplies soffice");
        assertEquals("/work/input.docx", command.getLast());
        assertEquals(1, runner.commands.size(), "Successful --rm runs need no cleanup process");
        assertEquals(List.of(12), runner.timeouts);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"TIMEOUT", "INTERRUPTED", "EXIT_FAILURE", "MISSING_PDF", "OVERSIZED_PDF"})
    void dockerFailureCleansOnlyItsNamedContainerAndPreservesInterruption(Outcome outcome) throws Exception {
        Path original = directory.resolve("original.docx"); Files.writeString(original, "source");
        var runner = new RendererRunner(outcome);
        try {
            var renderer = new SourcePageRenderer(runner);
            if (outcome == Outcome.INTERRUPTED) {
                assertThrows(InterruptedException.class, () -> renderer.render(original, directory, "unused", IMAGE, 12));
                assertTrue(Thread.currentThread().isInterrupted());
            } else assertThrows(IOException.class, () -> renderer.render(original, directory, "unused", IMAGE, 12));
            assertEquals(2, runner.commands.size());
            var command = runner.commands.getFirst();
            String container = command.get(command.indexOf("--name") + 1);
            assertTrue(container.startsWith("doc2tex-layout-"));
            assertEquals(List.of("docker", "rm", "-f", container), runner.commands.get(1));
            assertEquals(List.of(12, 5), runner.timeouts);
            assertFalse(runner.cleanupInterrupted, "Clear the flag only while running bounded container cleanup");
        } finally { Thread.interrupted(); }
    }

    @Test void nativeRendererUsesTheSameIsolatedSourceAndSecurityProfile() throws Exception {
        Path original = directory.resolve("original.docx"); Files.writeString(original, "source");
        var runner = new RendererRunner(Outcome.SUCCESS);
        new SourcePageRenderer(runner).render(original, directory, "native-soffice", "", 12);
        var command = runner.commands.getFirst();
        assertEquals("native-soffice", command.getFirst());
        assertTrue(command.get(1).startsWith("-env:UserInstallation=file:/"));
        assertEquals(directory.resolve("ai-layout-source/input.docx").toString(), command.getLast());
        assertEquals(1, runner.commands.size());
    }

    @Test void cleanupFailureDoesNotReplaceTheOriginalTimeout() throws Exception {
        Path original = directory.resolve("original.docx"); Files.writeString(original, "source");
        var runner = new RendererRunner(Outcome.TIMEOUT);
        runner.failCleanup = true;
        var error = assertThrows(IOException.class, () -> new SourcePageRenderer(runner).render(original, directory, "unused", IMAGE, 12));
        assertEquals("renderer deadline exceeded", error.getMessage());
        assertEquals(2, runner.commands.size());
    }

    private enum Outcome { SUCCESS, TIMEOUT, INTERRUPTED, EXIT_FAILURE, MISSING_PDF, OVERSIZED_PDF }

    private static final class RendererRunner extends ToolRunner {
        private final Outcome outcome;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Integer> timeouts = new ArrayList<>();
        private boolean cleanupInterrupted, failCleanup;

        RendererRunner(Outcome outcome) { this.outcome = outcome; }

        @Override Result run(List<String> command, Path render, Path log, int timeoutSeconds) throws IOException, InterruptedException {
            commands.add(command); timeouts.add(timeoutSeconds);
            if (command.size() > 1 && command.get(1).equals("rm")) {
                cleanupInterrupted = Thread.currentThread().isInterrupted();
                if (failCleanup) throw new IOException("Docker cleanup failed");
                return new Result(0, "");
            }
            switch (outcome) {
                case TIMEOUT -> throw new IOException("renderer deadline exceeded");
                case INTERRUPTED -> throw new InterruptedException("renderer interrupted");
                case EXIT_FAILURE -> { return new Result(1, "renderer failed"); }
                case MISSING_PDF -> { return new Result(0, "no PDF produced"); }
                case OVERSIZED_PDF -> {
                    try (var file = new java.io.RandomAccessFile(render.resolve("input.pdf").toFile(), "rw")) { file.setLength(40L * 1024 * 1024 + 1); }
                }
                case SUCCESS -> Files.writeString(render.resolve("input.pdf"), "%PDF-1.7 fixture");
            }
            return new Result(0, "");
        }
    }
}
