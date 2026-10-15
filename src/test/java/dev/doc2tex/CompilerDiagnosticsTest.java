package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilerDiagnosticsTest {
    @TempDir Path directory;

    @Test void categorizesLocatedAndMultilineMessagesWithoutInventingMainFileLocations() {
        var report = CompilerDiagnostics.parse("""
                error: main.tex:7: Undefined control sequence
                l.7 \\UnknownCommand
                warning: ./main.tex:9: Overfull \\hbox (12pt too wide)
                warning: font.sty:15: Missing character: There is no 字 in font Example!
                ! Package fontspec Error: The font "Unavailable" cannot be found.
                (fontspec) Check the selected font name.
                error: main.tex:12: Unable to load picture or PDF file 'missing.png'
                warning: main.tex:13: File `missing-font.png' not found
                ! Undefined control sequence.
                l.24 \\OtherCommand
                error: ../../package/main.tex:33: Undefined control sequence
                """, 4, "FAILED", false, null);
        assertEquals(4, report.revision()); assertFalse(report.logTruncated());
        var unknown = report.items().getFirst();
        assertEquals("undefined-command", unknown.code()); assertEquals("error", unknown.severity());
        assertEquals("main.tex", unknown.file()); assertEquals(7, unknown.line()); assertTrue(unknown.raw().contains("l.7"));
        assertTrue(report.items().stream().anyMatch(item -> item.code().equals("overfull-box") && item.severity().equals("warning") && item.line() == 9));
        assertTrue(report.items().stream().anyMatch(item -> item.code().equals("missing-glyph") && item.file().equals("font.sty")));
        assertTrue(report.items().stream().anyMatch(item -> item.code().equals("missing-font") && item.raw().contains("(fontspec)")));
        assertTrue(report.items().stream().anyMatch(item -> item.code().equals("missing-file") && item.line() == 12));
        assertTrue(report.items().stream().anyMatch(item -> item.code().equals("missing-file") && item.line() == 13));
        assertTrue(report.items().stream().anyMatch(item -> item.file() == null && item.line() == null && item.raw().contains("l.24")));
        assertTrue(report.items().stream().anyMatch(item -> "../../package/main.tex".equals(item.file())));
    }

    @Test void preservesUnknownAnsiAndLinelessFailuresWithSafeLocationBounds() {
        String log = "\u001b[31merror: main.tex:999999999999999999: Unfamiliar failure\u001b[0m\n  useful multiline detail\n";
        var report = CompilerDiagnostics.parse(log, null, "FAILED", false, null);
        var item = report.items().getFirst();
        assertEquals("compiler-message", item.code()); assertNull(item.line()); assertTrue(item.raw().contains("useful multiline detail"));
        assertFalse(item.raw().contains("\u001b")); assertEquals(log, report.rawLog());
        var failed = CompilerDiagnostics.parse("engine stopped without a message", 0, "FAILED", false, "Worker timed out");
        assertEquals("build-failed", failed.items().getFirst().code());
        assertEquals("Worker timed out", failed.items().getFirst().raw()); assertNull(failed.items().getFirst().file());
    }

    @Test void boundsLogsAndItemsAndExposesBothTruncationKinds() throws Exception {
        Path log = directory.resolve("preview.log");
        Files.writeString(log, "x".repeat(CompilerDiagnostics.LOG_LIMIT + 10));
        var truncated = CompilerDiagnostics.fromLog(log, 3, "FAILED", "Build failed");
        assertTrue(truncated.logTruncated()); assertEquals(CompilerDiagnostics.LOG_LIMIT, truncated.rawLog().length());
        assertEquals("build-failed", truncated.items().getFirst().code());
        StringBuilder many = new StringBuilder();
        for (int index = 1; index <= 250; index++) many.append("warning: main.tex:").append(index).append(": unfamiliar warning\n");
        var limited = CompilerDiagnostics.parse(many.toString(), 2, "READY", false, null);
        assertEquals(200, limited.items().size()); assertTrue(limited.diagnosticsTruncated());
    }

    @Test void snapshotsRetainTheirRevisionAndRawOutputAcrossReload() throws Exception {
        var report = CompilerDiagnostics.parse("warning: main.tex:3: Overfull \\hbox\n", 6, "READY", false, null);
        CompilerDiagnostics.save(directory, report);
        Files.writeString(directory.resolve("compiler-diagnostics.json.tmp"), "interrupted future report");
        assertEquals(report, CompilerDiagnostics.load(directory));
        Files.writeString(directory.resolve("preview.log"), "another run has different text");
        assertEquals(report, CompilerDiagnostics.load(directory));
        Files.writeString(directory.resolve("compiler-diagnostics.json"), "{\"revision\":6}");
        assertThrows(Exception.class, () -> CompilerDiagnostics.load(directory));
    }
}
